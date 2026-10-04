/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.messaging.pravaha.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Whether rows are still reaching a registered query, source by source, and why not when they are
 * not.
 *
 * <p>FEED-1. A source that fails mid-read stops its feed on purpose -- retrying a deleted file or a
 * revoked credential produces a log line a millisecond and no progress -- and the query stays
 * {@code RUNNING}, its view answering at the frontier it reached. The record of why was a sentence
 * inside {@link SourceFeed#describe()}, which nothing an operator uses called. This is that record
 * as data: every surface that describes a query (the HTTP API, the Flight listing, {@code pravaha
 * queries}, the metrics, {@code /status}, the actuator endpoint and health, the console) reads it
 * from here rather than parsing a sentence.
 *
 * <p><strong>Not a query state.</strong> {@link QueryState} keeps its four values and their meaning.
 * {@code FAILED} is terminal and makes the view refuse reads, which would be wrong here: the view is
 * correct up to its frontier, can still be paused, resumed and dropped, and is exactly as readable as
 * it was a moment before the source failed. A fifth state would also be a value that every client
 * switching on the state has never seen. So the query stays {@code RUNNING} and this says, beside it,
 * that its input has stopped -- {@link #stopped()} -- which is what a {@code degraded} flag would
 * have said, with the reason attached.
 *
 * @param state the query's feed as a whole: {@link State#STOPPED} when any source has stopped,
 *     because a view is only as current as its most stale input
 * @param description what the feed reads, in words, without the failure: "reading txn (1
 *     partition)"
 * @param sources one entry per partition the feed reads, in the order it opened them; empty when
 *     nothing is bound
 */
public record FeedStatus(State state, @Nullable String description, List<Source> sources) {

    /** The feed as a whole. */
    public enum State {
        /** Nothing is bound to the query's streams: rows arrive only if something pushes them. */
        NONE,
        /** Every source is reading. */
        RUNNING,
        /** Every source is paused, because the query is. */
        PAUSED,
        /** At least one source has stopped for good, and says why. */
        STOPPED
    }

    /** One partition's reader. */
    public enum SourceState {
        RUNNING,
        PAUSED,
        /** Stopped by a failure and not retried: fix the cause, then re-register or restart the node. */
        STOPPED
    }

    /**
     * One partition of one bound stream.
     *
     * @param shared the reader is one that several queries share (SRC-3), so its stop stops all of them
     * @param stop why it stopped, or null while it is reading
     */
    public record Source(
            String stream,
            int partition,
            boolean shared,
            SourceState state,
            @Nullable Stop stop) {

        public Source {
            if (state == SourceState.STOPPED && stop == null) {
                throw new IllegalArgumentException("a stopped source says why it stopped");
            }
        }

        /** {@code txn#0}: the stream and partition, as a log line and the CLI name them. */
        public String where() {
            return stream + "#" + partition;
        }
    }

    /**
     * Why a source stopped.
     *
     * @param failure the recorded failure: the source's own code ({@code PRV-5040} for a line a file
     *     source could not decode, say), or {@code PRV-5092} when the feed stopped for a reason that
     *     was not the source's -- a commit that threw, an error of the JVM's
     * @param at when the feed recorded it
     * @param origin true when this partition's own read raised it; false when it stopped because it
     *     shares a thread with one that did, or because publishing the query's frontier failed
     */
    public record Stop(PravahaException failure, @Nullable Instant at, boolean origin) {

        /** {@code PRV-5092}, or the source's own code. */
        public String code() {
            return failure.errorCode().code();
        }

        public @Nullable String message() {
            return failure.getMessage();
        }
    }

    public FeedStatus {
        sources = List.copyOf(sources);
    }

    /** What {@link SourceFeed#NONE} reports. */
    public static FeedStatus none(String description) {
        return new FeedStatus(State.NONE, description, List.of());
    }

    /**
     * A feed whose sources are these, its overall state derived from them: stopped if any has
     * stopped, paused if every one is paused, running otherwise -- and running for a feed that
     * reports no sources at all, which is what an implementation that predates this says.
     */
    public static FeedStatus of(@Nullable String description, List<Source> sources) {
        State state = State.RUNNING;
        if (sources.stream().anyMatch(source -> source.state() == SourceState.STOPPED)) {
            state = State.STOPPED;
        } else if (!sources.isEmpty() && sources.stream().allMatch(source -> source.state() == SourceState.PAUSED)) {
            state = State.PAUSED;
        }
        return new FeedStatus(state, description, sources);
    }

    /** True when at least one source has stopped: the query is {@code RUNNING} and its view is not moving. */
    public boolean stopped() {
        return state == State.STOPPED;
    }

    /** The sources that have stopped, in the order the feed opened them. */
    public List<Source> stoppedSources() {
        return sources.stream()
                .filter(source -> source.state() == SourceState.STOPPED)
                .toList();
    }

    /**
     * The stopped source to show first: one whose own read failed when there is one, since a
     * partition that stopped because its neighbour did is a consequence and not the cause.
     */
    public Optional<Source> firstStopped() {
        List<Source> stopped = stoppedSources();
        return stopped.stream()
                .filter(source -> java.util.Objects.requireNonNull(source.stop(), "a stopped source has its stop")
                        .origin())
                .findFirst()
                .or(() -> stopped.stream().findFirst());
    }

    /**
     * How many distinct failures stopped this feed.
     *
     * <p>Not the number of stopped partitions: one failure on a feed thread reading four partitions
     * stops all four, and counting it four times would turn a count of failures into a count of
     * partitions.
     */
    public int failures() {
        Set<PravahaException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Source source : stoppedSources()) {
            seen.add(java.util.Objects.requireNonNull(source.stop(), "a stopped source has its stop")
                    .failure());
        }
        return seen.size();
    }

    /** This status with {@code more} sources after its own, the overall state derived again. */
    public FeedStatus with(List<Source> more) {
        List<Source> all = new ArrayList<>(sources);
        all.addAll(more);
        return of(description, all);
    }
}

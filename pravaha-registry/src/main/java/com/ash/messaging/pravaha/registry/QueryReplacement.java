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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.backfill.BackfillJob;
import com.ash.messaging.pravaha.backfill.ShadowDeployment;

/**
 * One name being replaced: the version serving it, the version being prepared, and where each is.
 *
 * <p>ADR-046 and design section 16.3. The decision about <em>which version's output counts</em> is
 * {@link ShadowDeployment}'s and is kept here rather than reimplemented: it holds the seams as a
 * list of segments, refuses a cutover before the candidate has caught up, refuses a seam that would
 * go backwards, and leaves an audit trail of who served what. What this class adds is everything a
 * seam decision cannot know about -- the two running computations, the backfill's progress, the
 * retention window a rollback lives inside, and what a surface needs to show.
 */
public final class QueryReplacement {

    /** Where a replacement is in its life. */
    public enum State {
        /** The new version is reading history. */
        BACKFILLING,

        /** The new version has reached the live stream and a cutover may be asked for. */
        CAUGHT_UP,

        /** The new version has the name; the old one is retained so a rollback is instant. */
        CUT_OVER,

        /** The cutover was undone: the old version has the name again and the new one is gone. */
        ROLLED_BACK,

        /** Ended before the cutover, on purpose. */
        ABANDONED,

        /** Ended before the cutover because the backfill or the new version failed. */
        FAILED,

        /** Cut over and confirmed: the old version has been released and there is no rollback. */
        FINISHED
    }

    /**
     * Everything a surface shows about a replacement, in one immutable answer.
     *
     * <p>This is the shape the REST API, the Flight action, the CLI and the console all render, and
     * it is deliberately the whole of it: a screen that has to make three calls to describe one
     * operation shows three moments rather than one.
     */
    public record Status(
            String name,
            State state,
            String sql,
            List<Integer> keyColumns,
            String sink,
            ReplacementOptions options,
            String owner,
            Instant startedAt,
            Instant cutOverAt,
            Instant rollbackUntil,
            boolean rollbackAvailable,
            BackfillJob.Progress progress,
            long lagNanos,
            String replacing,
            String candidate,
            List<ShadowDeployment.Segment> segments,
            String failureCode,
            String failure) {

        public Status {
            keyColumns = List.copyOf(keyColumns);
            segments = List.copyOf(segments);
        }

        /** {@link #segments()}, each as its sentence: {@code "from 4471: <fingerprint>"}. */
        public List<String> history() {
            return segments.stream().map(ShadowDeployment.Segment::sentence).toList();
        }

        /** Whether this replacement is still doing something: neither ended nor confirmed. */
        public boolean active() {
            return state == State.BACKFILLING || state == State.CAUGHT_UP || state == State.CUT_OVER;
        }
    }

    private final String name;
    private final String sql;
    private final List<Integer> keyColumns;
    private final String sink;
    private final ReplacementOptions options;
    private final String owner;
    private final Instant startedAt;
    private final String shadowDirectory;
    private final BackfillJob job;
    private final ShadowDeployment deployment;

    /** The registration the replaced version had, so a rollback can journal it back. */
    private final RegistryJournal.Entry previous;

    private RegisteredQuery serving;
    private RegisteredQuery candidate;
    private State state = State.BACKFILLING;
    private Instant cutOverAt;
    private PravahaException failure;
    private long lagNanos;

    QueryReplacement(
            String name,
            String sql,
            List<Integer> keyColumns,
            String sink,
            ReplacementOptions options,
            String owner,
            Instant startedAt,
            String shadowDirectory,
            BackfillJob job,
            RegisteredQuery serving,
            RegisteredQuery candidate,
            RegistryJournal.Entry previous) {
        this.name = name;
        this.sql = sql;
        this.keyColumns = List.copyOf(keyColumns);
        this.sink = sink;
        this.options = options;
        this.owner = owner;
        this.startedAt = startedAt;
        this.shadowDirectory = shadowDirectory;
        this.job = job;
        this.serving = serving;
        this.candidate = candidate;
        this.previous = previous;
        this.deployment = new ShadowDeployment(serving.fingerprint().shortForm());
        this.deployment.startShadow(candidate.fingerprint().shortForm());
    }

    String name() {
        return name;
    }

    String sql() {
        return sql;
    }

    List<Integer> keyColumns() {
        return keyColumns;
    }

    String sink() {
        return sink;
    }

    String owner() {
        return owner;
    }

    String shadowDirectory() {
        return shadowDirectory;
    }

    ReplacementOptions options() {
        return options;
    }

    BackfillJob job() {
        return job;
    }

    ShadowDeployment deployment() {
        return deployment;
    }

    RegisteredQuery serving() {
        return serving;
    }

    RegisteredQuery candidate() {
        return candidate;
    }

    RegistryJournal.Entry previous() {
        return previous;
    }

    State state() {
        return state;
    }

    Instant cutOverAt() {
        return cutOverAt;
    }

    /**
     * Notices what the two versions are doing: how far the backfill has got, and how far behind the
     * candidate's event time is.
     *
     * <p>The candidate is caught up when every partition has reached the live stream -- the seam is
     * behind all of them -- and not when it has read some number of rows or run for some length of
     * time. Both of those are proxies that are wrong exactly when the input rate changes, which is
     * when somebody is most likely to be deploying.
     */
    synchronized void observe() {
        if (state == State.BACKFILLING || state == State.CAUGHT_UP) {
            deployment.observeFrontiers(1, job.historyComplete() ? 1 : 0);
            state = job.historyComplete() ? State.CAUGHT_UP : State.BACKFILLING;
            lagNanos = Math.max(
                    0, serving.view().appliedFrontier() - candidate.view().appliedFrontier());
            candidate.failure().ifPresent(this::failed);
        }
    }

    synchronized void cutOver(Instant when) {
        this.state = State.CUT_OVER;
        this.cutOverAt = when;
        RegisteredQuery replaced = serving;
        this.serving = candidate;
        this.candidate = replaced;
    }

    synchronized void rolledBack() {
        this.state = State.ROLLED_BACK;
        RegisteredQuery restored = candidate;
        this.candidate = serving;
        this.serving = restored;
    }

    synchronized void abandoned() {
        this.state = State.ABANDONED;
    }

    synchronized void finished() {
        this.state = State.FINISHED;
    }

    synchronized void failed(PravahaException why) {
        if (state == State.BACKFILLING || state == State.CAUGHT_UP) {
            this.state = State.FAILED;
        }
        this.failure = why;
    }

    /** The version retained for a rollback, when there is one to roll back to. */
    synchronized Optional<RegisteredQuery> retained() {
        return state == State.CUT_OVER ? Optional.of(candidate) : Optional.empty();
    }

    /** When the rollback window closes, or empty before the cutover. */
    synchronized Optional<Instant> rollbackUntil() {
        return cutOverAt == null ? Optional.empty() : Optional.of(cutOverAt.plus(options.rollbackRetention()));
    }

    synchronized boolean rollbackWindowClosed(Instant now) {
        return state == State.CUT_OVER && rollbackUntil().map(now::isAfter).orElse(false);
    }

    synchronized boolean active() {
        return state == State.BACKFILLING || state == State.CAUGHT_UP || state == State.CUT_OVER;
    }

    /** How long a cutover will wait for the two versions to meet at the same position. */
    static final Duration ALIGNMENT_BUDGET = Duration.ofSeconds(30);

    public synchronized Status status() {
        return new Status(
                name,
                state,
                sql,
                keyColumns,
                sink,
                options,
                owner,
                startedAt,
                cutOverAt,
                rollbackUntil().orElse(null),
                state == State.CUT_OVER,
                job.progress(),
                lagNanos,
                state == State.CUT_OVER || state == State.FINISHED
                        ? candidate.fingerprint().shortForm()
                        : serving.fingerprint().shortForm(),
                state == State.CUT_OVER || state == State.FINISHED
                        ? serving.fingerprint().shortForm()
                        : candidate.fingerprint().shortForm(),
                deployment.segments(),
                failure == null ? null : failure.errorCode().code(),
                failure == null ? null : failure.getMessage());
    }

    @Override
    public String toString() {
        return "QueryReplacement[" + name + ", " + state + ", " + deployment + "]";
    }
}

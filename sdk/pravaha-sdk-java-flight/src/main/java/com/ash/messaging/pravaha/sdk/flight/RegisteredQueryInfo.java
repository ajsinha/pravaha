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
package com.ash.messaging.pravaha.sdk.flight;

/**
 * What a server says about one registered continuous query.
 *
 * @param name the name it answers to. A computation may have several; this is the one you asked
 *     about
 * @param state {@code RUNNING}, {@code PAUSED}, {@code FAILED} or {@code DROPPED}
 * @param sql the SQL it was registered with
 * @param fingerprint the short form of its plan fingerprint. Two names sharing a fingerprint are
 *     one computation with one copy of the state, which is worth being able to see
 * @param rowsIn rows it has accepted, or {@code -1} when the server withholds the count because the
 *     caller may read only a row-filtered slice of the view
 * @param keyColumns the view's key, as output column ordinals; empty from a server that predates it
 * @param sink the sink binding its changes are also written to, or null when there is none (or the
 *     server predates the field)
 * @param retention how much event time its view keeps, ISO-8601 such as {@code PT24H}, or {@code
 *     forever}; null from a server that predates the field
 * @param feed whether rows still reach it: {@code RUNNING}, {@code PAUSED}, {@code STOPPED} (a source
 *     failed mid-read and is not retried; the query stays {@code RUNNING} and its view stops moving)
 *     or {@code NONE} (nothing is bound); null from a server that predates the field (FEED-1)
 * @param feedStop why the first stopped source stopped, or null while every source reads
 * @param sinkState whether the sink is still writing: {@code ATTACHED}, {@code DETACHED} (it
 *     refused a batch and was detached -- the query and its view carry on and nothing more is
 *     written) or {@code NONE} (the query writes nowhere); null from a server that predates the
 *     field (SINK-3)
 * @param sinkFailure why it was detached, or null while it writes
 * @param owner the id of the principal who registered it, or replaced it last -- who may drop, pause or
 *     replace it without a grant; null from a server that predates the field
 */
public record RegisteredQueryInfo(
        String name,
        String state,
        String sql,
        String fingerprint,
        long rowsIn,
        java.util.List<Integer> keyColumns,
        String sink,
        String retention,
        String feed,
        FeedStop feedStop,
        String sinkState,
        SinkFailure sinkFailure,
        String owner) {

    public RegisteredQueryInfo {
        keyColumns = keyColumns == null ? java.util.List.of() : java.util.List.copyOf(keyColumns);
    }

    /** The original five fields, as a server that predates keys, sink and retention reports them. */
    public RegisteredQueryInfo(String name, String state, String sql, String fingerprint, long rowsIn) {
        this(name, state, sql, fingerprint, rowsIn, java.util.List.of(), null, null);
    }

    /** Eight fields, as a server that predates the feed reports them. */
    public RegisteredQueryInfo(
            String name,
            String state,
            String sql,
            String fingerprint,
            long rowsIn,
            java.util.List<Integer> keyColumns,
            String sink,
            String retention) {
        this(name, state, sql, fingerprint, rowsIn, keyColumns, sink, retention, null, null, null, null, null);
    }

    /** Thirteen fields, as a server that predates the sink's state reports them (FEED-1's shape). */
    public RegisteredQueryInfo(
            String name,
            String state,
            String sql,
            String fingerprint,
            long rowsIn,
            java.util.List<Integer> keyColumns,
            String sink,
            String retention,
            String feed,
            FeedStop feedStop) {
        this(name, state, sql, fingerprint, rowsIn, keyColumns, sink, retention, feed, feedStop, null, null, null);
    }

    /** Sixteen fields, as a server that predates the owner reports them (SINK-3's shape). */
    public RegisteredQueryInfo(
            String name,
            String state,
            String sql,
            String fingerprint,
            long rowsIn,
            java.util.List<Integer> keyColumns,
            String sink,
            String retention,
            String feed,
            FeedStop feedStop,
            String sinkState,
            SinkFailure sinkFailure) {
        this(
                name,
                state,
                sql,
                fingerprint,
                rowsIn,
                keyColumns,
                sink,
                retention,
                feed,
                feedStop,
                sinkState,
                sinkFailure,
                null);
    }

    /**
     * Why a source stopped (FEED-1).
     *
     * @param code {@code PRV-5092}, or the source's own code
     * @param message what it said, or a note that the server withheld it: a row-filtered caller is not
     *     sent a failure's text, which can quote a row
     * @param where {@code stream#partition}
     * @param at when, ISO-8601
     */
    public record FeedStop(String code, String message, String where, String at) {}

    /**
     * Why a sink was detached (SINK-3).
     *
     * @param code {@code PRV-8009}
     * @param message what happened, with every configured sink option struck out of it, or a note
     *     that the server withheld it: a row-filtered caller is not sent a failure's text, which
     *     can quote a row
     */
    public record SinkFailure(String code, String message) {}

    public boolean isRunning() {
        return "RUNNING".equals(state);
    }

    /**
     * True when this query's sink refused a batch and was detached ({@code PRV-8009}). The query
     * is still {@code RUNNING} and its view is still right; nothing more reaches the sink.
     * {@link #sinkFailure()} says what happened.
     */
    public boolean isSinkDetached() {
        return "DETACHED".equals(sinkState);
    }

    /**
     * True when a source of this query has stopped: it reports {@code RUNNING} and its view has
     * stopped moving. {@link #feedStop()} says why.
     */
    public boolean isSourceStopped() {
        return "STOPPED".equals(feed);
    }

    @Override
    public String toString() {
        return name + " [" + state + ", " + fingerprint + ", " + rowsIn + " rows]";
    }
}

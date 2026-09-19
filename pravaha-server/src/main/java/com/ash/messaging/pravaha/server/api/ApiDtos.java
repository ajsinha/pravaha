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
package com.ash.messaging.pravaha.server.api;

import java.time.Instant;
import java.util.List;

/**
 * The wire types of the public API.
 *
 * <p>Kept apart from the engine's own types on purpose. A DTO that is also a domain object couples
 * the wire format to internal structure, so an internal refactor becomes a breaking API change and
 * the API stops being safe to evolve. This layer is the seam that lets the engine change shape
 * without the contract moving (§23.2a).
 *
 * <p>Every field name here is part of the contract and appears in {@code openapi.lock.json}. Adding
 * one is a reviewed diff; removing or renaming one is a breaking change.
 */
public final class ApiDtos {

    private ApiDtos() {}

    /**
     * A stream the engine can read.
     *
     * @param eventTime the column the stream's event time is read from, or null when it has none --
     *     in which case no window over it can ever close
     * @param outOfOrderness how late a row's event time may be before a window stops waiting for it,
     *     as ISO-8601 (such as {@code PT10S}); null when there is no event time for it to be about
     * @param source the plugin a {@code pravaha.sources.<stream>} binding feeds it from, or null when
     *     nothing does. The plugin's name only: a binding's options can hold credentials
     * @param allowedLateness how long after a window closes a late row may still correct it, as
     *     ISO-8601; null when there is no event time, {@code PT0S} when windows are final at close
     */
    public record StreamSummary(
            String name,
            int version,
            int fieldCount,
            List<FieldInfo> fields,
            String eventTime,
            String outOfOrderness,
            String source,
            String allowedLateness) {

        public StreamSummary(String name, int version, int fieldCount, List<FieldInfo> fields) {
            this(name, version, fieldCount, fields, null, null, null, null);
        }
    }

    public record FieldInfo(String name, String type, boolean nullable, int ordinal) {}

    /** A validation result. Deliberately not an error response: an invalid query is a normal answer. */
    public record ValidationResult(
            boolean valid, List<Diagnostic> diagnostics, List<FieldInfo> outputFields, long elapsedMicros) {

        public static ValidationResult ok(List<FieldInfo> outputFields, long elapsedMicros) {
            return new ValidationResult(true, List.of(), outputFields, elapsedMicros);
        }
    }

    /**
     * One problem with a query.
     *
     * <p>Carries the stable {@code PRV-nnnn} code and a documentation link, so the console can render
     * an actionable fix rather than a wall of text (design section 24.4).
     */
    public record Diagnostic(String code, String message, String helpUrl, String severity, SourceRange range) {

        public Diagnostic(String code, String message, String helpUrl, String severity) {
            this(code, message, helpUrl, severity, null);
        }
    }

    /**
     * Where in the SQL a diagnostic belongs: 1-based lines and columns, the end column inclusive (the
     * last character of the offending text). Null on a diagnostic when the parser and validator did not
     * record a position -- a refusal about the plan rather than the text has none, and a guessed one
     * would underline the wrong thing with confidence.
     */
    public record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {}

    /**
     * A plan, at the level asked for.
     *
     * @param graph the physical plan as nodes and edges, present when {@code format=graph} was asked
     *     for; null otherwise
     */
    public record ExplainResult(String level, String plan, List<FieldInfo> outputFields, PlanGraph graph) {

        public ExplainResult(String level, String plan, List<FieldInfo> outputFields) {
            this(level, plan, outputFields, null);
        }
    }

    /**
     * A physical plan as a graph.
     *
     * <p>Node {@code n0} is the root, and ids follow the order the text plan prints operators. Edges run
     * the way rows flow, from an input to the operator that consumes it.
     *
     * @param operatorMetrics per-operator telemetry, keyed by node id. <strong>Always null
     *     today</strong>: the runtime does not count rows or state per operator, and inventing a split
     *     of the query's totals across its operators would be a number that looks measured and is not.
     *     {@code metricsNote} says so in words.
     * @param query what the engine does measure, for the query as a whole, when this is the plan of a
     *     registered query; null for SQL that is not registered
     */
    public record PlanGraph(
            List<PlanNode> nodes,
            List<PlanEdge> edges,
            java.util.Map<String, Object> operatorMetrics,
            String metricsNote,
            QueryTelemetry query) {}

    /**
     * One operator.
     *
     * @param operator its kind, such as {@code Scan}, {@code Filter}, {@code WindowedAggregate}
     * @param detail the engine's own label for it, as the text plan prints it
     * @param stateful whether it keeps state, which is what a state ceiling bounds
     * @param fields the columns it emits
     */
    public record PlanNode(String id, String operator, String detail, boolean stateful, List<String> fields) {}

    /** Rows flow from {@code from} into {@code to}. */
    public record PlanEdge(String from, String to) {}

    /**
     * What the engine measures for one registered query as a whole. Row counts are {@code -1} when
     * withheld from a principal entitled only to a row-filtered slice of the view.
     */
    public record QueryTelemetry(
            long rowsIn, long stateHeld, long stateCeiling, long viewSize, String watermark, int subscribers) {}

    /** A key column of a view: its name, and the ordinal registration took. */
    public record KeyColumn(String name, int ordinal) {}

    /**
     * The sink a registered query writes to.
     *
     * @param attached false once the sink refused a batch and was detached ({@code PRV-8009}); the
     *     query and its view carry on
     * @param failure why it was detached, or null while it is writing
     * @param rowsWritten rows the sink accepted, {@code -1} when withheld
     */
    public record QuerySink(String name, boolean attached, Problem failure, long rowsWritten) {}

    /** A refusal or failure, by code and message. */
    public record Problem(String code, String message, String helpUrl) {}

    /**
     * Whether rows are still reaching a registered query (FEED-1).
     *
     * <p>A source that fails mid-read stops its feed and leaves the query {@code RUNNING}, its view
     * answering at the frontier it reached. This is where that shows, beside the state rather than
     * in it: the query is still running, and its input is not.
     *
     * @param state {@code RUNNING}, {@code PAUSED}, {@code STOPPED} (at least one source has stopped)
     *     or {@code NONE} (nothing is bound; rows arrive only if something pushes them)
     * @param description what the feed reads, in words
     * @param stoppedSources how many of {@code sources} have stopped
     * @param failure why the first stopped source stopped -- the source's own code, or {@code
     *     PRV-5092} -- or null while every source is reading
     */
    public record QueryFeed(
            String state, String description, List<FeedSource> sources, int stoppedSources, Problem failure) {}

    /**
     * One partition of one bound stream.
     *
     * @param state {@code RUNNING}, {@code PAUSED} or {@code STOPPED}
     * @param shared the reader is shared with other queries, so its stop stops them too
     * @param origin true when this partition's own read raised the failure; false when it stopped
     *     alongside one that did
     * @param failure why it stopped, or null
     * @param stoppedAt when it stopped, or null
     */
    public record FeedSource(
            String stream,
            int partition,
            String state,
            boolean shared,
            boolean origin,
            Problem failure,
            Instant stoppedAt) {}

     * How a backfill is getting on (design section 16.2).
     *
     * <p>No estimate and no ETA: a source does not say how much history it holds, and a progress
     * bar that invents a denominator is a promise the engine cannot keep. What is here is what is
     * known -- rows read, the rate, how many partitions have reached the live stream, and how far
     * behind the running version the candidate's event time is.
     *
     * @param historyRows records of history read so far
     * @param liveRows records read from the live stream since the seam
     * @param rowsPerSecond what the backfill is reading at, over the last sample
     * @param partitions partitions the backfill has
     * @param partitionsLive how many of them have reached the live stream
     * @param historyComplete whether every one has: the seam is behind all of them
     * @param rateLimit the ceiling in records a second, or zero for none
     * @param paused whether the backfill is paused
     * @param lagSeconds how far behind the running version the candidate's event time is
     */
    public record BackfillProgress(
            long historyRows,
            long liveRows,
            double rowsPerSecond,
            int partitions,
            int partitionsLive,
            boolean historyComplete,
            long rateLimit,
            boolean paused,
            double lagSeconds) {}

    /**
     * A blue/green replacement, whole (ADR-046).
     *
     * <p>One answer rather than three calls: the console's cutover screen shows the state, the
     * progress and the rollback window together, and a screen that has to ask separately shows
     * three moments instead of one.
     *
     * @param state {@code BACKFILLING}, {@code CAUGHT_UP}, {@code CUT_OVER}, {@code ROLLED_BACK},
     *     {@code ABANDONED}, {@code FAILED} or {@code FINISHED}
     * @param candidate the fingerprint of the computation being prepared
     * @param replacing the fingerprint of the one serving the name
     * @param history who served this name from which seam, oldest first: the audit trail a cutover
     *     leaves behind
     * @param rollbackAvailable whether the replaced version is still retained
     */
    public record ReplacementStatus(
            String name,
            String state,
            String sql,
            String candidate,
            String replacing,
            String sink,
            String options,
            String owner,
            Instant startedAt,
            Instant cutOverAt,
            Instant rollbackUntil,
            boolean rollbackAvailable,
            BackfillProgress backfill,
            List<String> history,
            Problem failure) {}

    /**
     * One registered query, as the caller may see it.
     *
     * <p>Visibility is decided exactly as the Flight {@code pravaha.list} action decides it -- by name,
     * by what the query reads (SX-11), with counts withheld from a row-filtered principal (SX-18) --
     * because both call the same code.
     *
     * @param sharedWith other names on the same computation that this caller may also see
     * @param retention the view's retention in event time, ISO-8601, or {@code forever}
     * @param rowsIn rows accepted since registration, {@code -1} when withheld
     * @param countsWithheld true when this caller's access is row-filtered, so the totals are not theirs
     * @param failure why the query failed, when its state is {@code FAILED}
     * @param reads the streams the query reads, from its plan's provenance rather than from matching
     *     names in its text. Every one of them is a stream this caller may read, or the query would
     *     not be visible to them at all (SX-11)
     * @param feed whether rows are still reaching it, source by source, and why not when a source has
     *     stopped (FEED-1)
     */
    public record QueryDetail(
            String name,
            String state,
            String sql,
            String fingerprint,
            List<String> sharedWith,
            List<KeyColumn> keyColumns,
            String retention,
            QuerySink sink,
            long rowsIn,
            boolean countsWithheld,
            Instant registeredAt,
            Problem failure,
            List<String> reads,
            QueryFeed feed) {}

    /**
     * A registered query's view, described without reading it.
     *
     * @param schema the view's columns
     * @param sink the sink its query writes to, by binding name, or null
     */
    public record ViewDescription(
            String name,
            List<FieldInfo> schema,
            List<KeyColumn> keyColumns,
            String retention,
            String sink,
            String fingerprint) {}

    /**
     * A sink binding, as a client choosing where to write needs to see it.
     *
     * <p>Never its options: a binding's options are where a password, a key or a connection string with
     * credentials in it lives, and nothing here is ever built from them.
     *
     * @param fields the row shape the sink was configured with; empty when it takes any shape
     * @param keyColumns the columns it keys records by; empty for an append-only sink
     * @param emitModes what it accepts: {@code APPEND}, {@code UPSERT}, {@code RETRACT}
     * @param acceptsRetractions whether a query that revises its answer may write to it; a revising
     *     query pointed at a sink that cannot take a retraction is refused with {@code PRV-2041}
     * @param guarantee what a query writing to it is promised on this node: {@code EXACTLY_ONCE}
     *     (transactional, and the node checkpoints), {@code EFFECTIVELY_ONCE} (idempotent upsert) or
     *     {@code AT_LEAST_ONCE}; the same decision the registration log states
     * @param writers the registered queries writing to it that this caller may see
     * @param problem why it could not be described, when its plugin refused its configuration; the
     *     plugin's own text is not repeated, because it can quote the options
     */
    public record SinkSummary(
            String name,
            String plugin,
            List<FieldInfo> fields,
            List<String> keyColumns,
            List<String> emitModes,
            boolean acceptsRetractions,
            String guarantee,
            List<String> writers,
            Problem problem) {}

    /**
     * What the node is and how it is doing. Served even when the console process is down.
     *
     * @param registeredQueries how many registrations the node holds, by name -- two names for one
     *     computation are two. It was the stream count until HLP-8
     * @param streams how many streams the node has declared
     * @param stoppedFeeds how many registered names have a source that stopped mid-read and is not
     *     retried (FEED-1). A count and not names: this endpoint answers anyone who can reach the
     *     port, and which queries exist is the listing's to decide
     */
    public record NodeStatus(
            String instanceId,
            String version,
            String engineState,
            long uptimeSeconds,
            int registeredQueries,
            List<PluginStatus> plugins,
            int streams,
            int stoppedFeeds) {}

    public record PluginStatus(String name, String version, String health, String detail) {}

    /** The standard error body. Every non-2xx response is one of these and nothing else. */
    public record ApiError(String code, String message, String helpUrl, Instant timestamp, String path) {}
}

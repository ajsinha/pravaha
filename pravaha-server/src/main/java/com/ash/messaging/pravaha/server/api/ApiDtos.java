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
 * <p>Every field name here is part of the contract and appears in {@code openapi.lock.json}, with
 * its type and whether it is required (OPENAPILOCK-1). Adding one is a reviewed diff (the contract
 * test fails until the lock is regenerated); removing, renaming or retyping one is a breaking
 * change, and {@code OpenApiContractTest} names it as one.
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
     * @param fingerprint the fingerprint a registration of this SQL would get for the caller, with
     *     the {@code keys}, {@code retention} and {@code sink} the request gave -- the short form
     *     {@code GET /api/v1/queries} lists (EXPLAINFP-1); null when no {@code keys} were given, or
     *     when a registration would be refused
     * @param fingerprintRefusal why a registration would be refused, and so why there is no
     *     fingerprint: the code and sentence {@code register} would answer; null otherwise
     */
    public record ExplainResult(
            String level,
            String plan,
            List<FieldInfo> outputFields,
            PlanGraph graph,
            String fingerprint,
            Diagnostic fingerprintRefusal) {

        public ExplainResult(String level, String plan, List<FieldInfo> outputFields) {
            this(level, plan, outputFields, null, null, null);
        }

        public ExplainResult(String level, String plan, List<FieldInfo> outputFields, PlanGraph graph) {
            this(level, plan, outputFields, graph, null, null);
        }
    }

    /**
     * A physical plan as a graph.
     *
     * <p>Node {@code n0} is the root, and ids follow the order the text plan prints operators. Edges run
     * the way rows flow, from an input to the operator that consumes it.
     *
     * @param operatorMetrics per-operator telemetry, keyed by node id -- {@code n0} is the root, the
     *     same ids {@code nodes} carries. Null for SQL that is not registered (there is nothing
     *     running to measure) and null on a node started with {@code pravaha.metrics.operators} off,
     *     which is deliberately distinguishable from every counter reading zero. {@code metricsNote}
     *     says which of those it is
     * @param bottleneck the node id most of this query's own time goes into, or null when nothing
     *     has been sampled yet. Measured, not inferred from row counts: a filter that drops 99 % of
     *     its input is not the bottleneck for dropping them
     * @param metricsNote what is and is not measured here, in words, so a client never has to guess
     *     what a null means
     * @param query what the engine measures for the query as a whole, when this is the plan of a
     *     registered query; null for SQL that is not registered
     */
    public record PlanGraph(
            List<PlanNode> nodes,
            List<PlanEdge> edges,
            java.util.Map<String, OperatorTelemetry> operatorMetrics,
            String bottleneck,
            String metricsNote,
            QueryTelemetry query) {}

    /**
     * What one operator of a running query has done.
     *
     * @param rowsIn rows handed to it; a join counts both sides here
     * @param rowsOut rows it emitted. Against {@code rowsIn} this is the operator's selectivity
     * @param stateBytes bytes its state store holds, or null where it keeps no state of its own or
     *     keeps it on the heap, where there is no byte count that is not a guess
     * @param watermark the event time it has been advanced to, ISO-8601, or null before the first
     *     advance. Every node of one plan carries the same figure: an advance reaches all of them
     *     in one call on the lane thread
     * @param selfNanos its own work on the sampled rows, its children's time subtracted
     * @param sampledRows how many rows that time covers -- one in every 1,024 that entered the
     *     pipeline. Published beside the time so the rate can be checked rather than assumed
     * @param selfTimeShare its share of the query's sampled time, 0 to 1. This is the number that
     *     names a bottleneck
     */
    public record OperatorTelemetry(
            long rowsIn,
            long rowsOut,
            Long stateBytes,
            String watermark,
            long selfNanos,
            long sampledRows,
            double selfTimeShare) {}

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
     *
     * @param backpressureWaits episodes in which one of this query's writers found nowhere to put a
     *     row. A count of episodes, not of rows: a source held off for an hour is one
     * @param backpressureWaitSeconds how long those episodes lasted altogether, including one still
     *     open. Against uptime this is the share of time the query could not be fed
     * @param blockedFraction the same share as the lanes see it, 0 to 1, counting every writer into
     *     those lanes. On a shared lane that includes other queries' writers, which is the point:
     *     a query can be blocked by a neighbour and this is where that shows
     * @param inboxDepth rows queued into the lane and not yet taken, now
     * @param inboxCells what that depth is out of
     */
    public record QueryTelemetry(
            long rowsIn,
            long stateHeld,
            long stateCeiling,
            long viewSize,
            String watermark,
            int subscribers,
            long backpressureWaits,
            double backpressureWaitSeconds,
            double blockedFraction,
            int inboxDepth,
            int inboxCells) {}

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

    /**
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
     *     leaves behind, each entry one sentence ({@code "from 4471: <fingerprint>"})
     * @param historyEntries the same trail with its parts, one entry per sentence in {@code history}
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
            List<HistoryEntry> historyEntries,
            Problem failure) {}

    /**
     * One version that has served a name, and where it took over.
     *
     * @param fromFrontier the input position the version took over at, or null for the first version,
     *     which has served from the beginning
     * @param version the version's fingerprint
     */
    public record HistoryEntry(Long fromFrontier, String version) {}

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
     * @param execution which path each filter and projection chain runs on, one line per chain,
     *     starting {@code generated:} or {@code interpreted:} with the reason (C-7)
     * @param lane where the query's computation runs: {@code dedicated} (a lane of its own because it
     *     was registered {@code WITH (lane = 'dedicated')}), {@code shared} or {@code own}
     * @param sharedLane the shared lane it runs on when {@code lane} is {@code shared}, else null
     * @param readsFrom the registered queries whose answers this one follows (ADR-056), that this
     *     caller may see; empty for a query over streams
     * @param dependants the registered queries that follow this one's answer, then the alerts on it
     *     as {@code ALERT <name>} (ALERTDEPS-1), that this caller may see; a drop is refused while
     *     there are any ({@code PRV-8024})
     * @param accessPaths how the reads of this query's view found their rows, counted since it
     *     started (IDXVIS-1)
     * @param owner the id of the principal who registered it, or replaced it last: who may drop, pause
     *     or replace it without a grant ({@code pravaha.security.administer}); null when none is recorded
     * @param checkpoint whether it checkpoints, when it last did, and why its last checkpoint failed
     *     (CKPTWHY-1); null for a registration refused at recovery
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
            QueryFeed feed,
            List<String> execution,
            String lane,
            Integer sharedLane,
            List<String> readsFrom,
            List<String> dependants,
            AccessPaths accessPaths,
            String owner,
            QueryCheckpoint checkpoint) {}

    /**
     * A registered query's checkpoints (CKPTWHY-1): the failure count was a metric and the reason was
     * kept on the query with nothing reading it.
     *
     * @param enabled whether this query checkpoints at all ({@code pravaha.checkpoint.directory})
     * @param last when it last stored one, or null when it never has
     * @param failures checkpoints that failed since it started, a restore that could not be used included
     * @param lastFailure the most recent failure's reason, or null when none has failed
     */
    public record QueryCheckpoint(boolean enabled, Instant last, long failures, String lastFailure) {}

    /**
     * How the reads of one view found their rows (IDXVIS-1): every read of a view -- Flight SQL,
     * {@code /api/v1/views/{name}/query}, the PostgreSQL gateway -- takes exactly one path, chosen
     * from its {@code WHERE} clause, and the answer does not depend on which.
     *
     * @param point reads answered by one hash probe on the whole key ({@code WHERE key = ...})
     * @param range reads answered by a run of the ordered index {@code RANGE (column)} keeps
     * @param index reads answered by probing an equality index {@code INDEX (column)} keeps
     * @param scan reads that walked every committed row
     * @param indexes the columns an equality index is kept over, by name, with the entries each holds
     */
    public record AccessPaths(long point, long range, long index, long scan, java.util.Map<String, Long> indexes) {}

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
     * How the node places queries on lanes ({@code GET /api/v1/lanes}), as in effect in its registry.
     *
     * @param mode {@code auto}, {@code true} or {@code false}, as {@code pravaha.lane.multiplex.enabled}
     *     is in effect: {@code auto} with an {@code auto-from} of zero shares at once and reads as {@code true}
     * @param autoFrom computations hosted on lanes of their own before sharing starts, under {@code auto};
     *     null otherwise
     * @param maxQueriesPerLane the ceiling on each shared lane; zero when not sharing
     * @param sharedLanes each shared lane and how many query pipelines it carries
     * @param ownLaneQueries computations on lanes of their own, dedicated ones included
     * @param dedicatedQueries computations registered {@code WITH (lane = 'dedicated')}
     * @param hosted computations the node runs, however many names each answers to
     */
    public record LaneSummary(
            String mode,
            Integer autoFrom,
            int maxQueriesPerLane,
            List<SharedLane> sharedLanes,
            int ownLaneQueries,
            int dedicatedQueries,
            int hosted) {}

    /** One shared lane: its number and the query pipelines on it. */
    public record SharedLane(int lane, int queries) {}

    /**
     * What the node is and how it is doing. Served even when the console process is down.
     *
     * @param registeredQueries how many registrations the node holds, by name -- two names for one
     *     computation are two. It was the stream count until HLP-8
     * @param streams how many streams the node has declared
     * @param stoppedFeeds how many registered names have a source that stopped mid-read and is not
     *     retried (FEED-1). A count and not names: this endpoint answers anyone who can reach the
     *     port, and which queries exist is the listing's to decide
     * @param flight where Flight SQL is listening, {@code host:port} with an IPv6 host bracketed,
     *     or {@code "disabled"}. CFG-2(b): {@code pravaha.flight.port: 0} binds an ephemeral port
     *     and nothing served it, so a client told to connect had nowhere to look -- the log line on
     *     the node is not reachable from the client that needs the number
     */
    public record NodeStatus(
            String instanceId,
            String version,
            String engineState,
            long uptimeSeconds,
            int registeredQueries,
            List<PluginStatus> plugins,
            int streams,
            int stoppedFeeds,
            String flight) {}

    public record PluginStatus(String name, String version, String health, String detail) {}

    /**
     * The standard error body. Every non-2xx response is one of these and nothing else.
     *
     * <p>CFG-20. The published document described {@code components.schemas.ApiError} with
     * <strong>zero properties</strong>, so a generated client modelled every error as an empty
     * object and none of the five fields was discoverable -- on the one schema a client is
     * guaranteed to meet. Each component is described here rather than in prose somewhere else,
     * because the document is what an integrator reads.
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            name = "ApiError",
            description = "The one error shape this API returns. Every non-2xx response is one of these.")
    public record ApiError(
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "The stable PRV- code. Look it up in docs/guides/TROUBLESHOOTING.md.",
                    example = "PRV-2003")
            String code,

            @io.swagger.v3.oas.annotations.media.Schema(description = "What went wrong, and what to do about it.")
            String message,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "Documentation for this code, or empty when there is none.")
            String helpUrl,

            @io.swagger.v3.oas.annotations.media.Schema(description = "When the node produced this answer.")
            Instant timestamp,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "The request path this is about.",
                    example = "/api/v1/streams")
            String path) {}
}

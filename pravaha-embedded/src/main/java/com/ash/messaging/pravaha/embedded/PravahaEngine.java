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
package com.ash.messaging.pravaha.embedded;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.connect.PluginRegistry;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * An engine instance.
 *
 * <p><strong>This is the seam ADR-019 is built on.</strong> Everything above it -- the Spring Boot
 * server, the Spring Boot starter, the CLI, {@code @PravahaTest} -- is a bootstrap that creates one
 * of these and manages its lifecycle. Nothing below it knows what created it, which is what keeps
 * Spring out of the engine core and lets the same engine run in a host application on whatever
 * Spring version that application happens to use.
 *
 * <p>Not a singleton, and the interface has no static accessor by design. Embedded mode runs several
 * engines in one JVM and {@code @PravahaTest} gives each test its own; a process-wide instance would
 * make both impossible and make tests order-dependent.
 *
 * <p>Lifecycle is {@code create -> declare -> start -> ... -> stop}. {@link #close()} stops if
 * needed, so an engine works in try-with-resources.
 *
 * <h2>The whole loop, in process</h2>
 *
 * <pre>{@code
 * try (PravahaEngine engine = PravahaEngine.createDefault()) {
 *     engine.declareStream("txn", "user_id:STRING,amount:INT64");
 *     engine.start();
 *     engine.register("spend", "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id", "user_id");
 *     engine.subscribe("spend", changes -> changes.forEach(System.out::println));
 *     engine.push("txn", new Object[] {"u1", 300L}, new Object[] {"u1", 200L});
 *     engine.query("SELECT total FROM spend WHERE user_id = ?", "u1"); // 500
 * }
 * }</pre>
 *
 * <p><strong>Declare before start.</strong> Streams, bindings and declared queries are fixed at
 * {@link #start()}: the registry plans every query against the streams it was built with, so a stream
 * added afterwards is one no query could name. The same blocks can be given in the {@link
 * Configuration} instead -- {@code pravaha.streams}, {@code pravaha.sources}, {@code pravaha.lookups},
 * {@code pravaha.sinks} and {@code pravaha.queries}, exactly as the server reads them.
 *
 * <p><strong>Persistence is opt-in</strong> and uses the server's keys: {@code
 * pravaha.registry.journal} (a file) brings registrations back after a restart, and {@code
 * pravaha.checkpoint.directory} (with {@code .interval}, {@code .keep}, {@code .timeout}) brings their
 * state back. Without them an engine is memory only, which is right for a test.
 *
 * <p><strong>No authorization.</strong> An embedded engine sits behind its host's own wall: every
 * call runs as the anonymous principal under a permissive policy. The server is the mode with
 * authentication, policies and an audit trail.
 */
public interface PravahaEngine extends AutoCloseable {

    /** Builds an engine from configuration. Does not start it. */
    static PravahaEngine create(Configuration configuration) {
        return new DefaultPravahaEngine(configuration);
    }

    /** An engine with default configuration, for tests and {@code pravaha dev}. */
    static PravahaEngine createDefault() {
        return create(Configuration.empty());
    }

    // ------------------------------------------------------------------ declarations, before start

    /**
     * Declares a stream by schema, with whatever event time and lateness the schema carries.
     *
     * @throws IllegalStateException once the engine has started
     */
    PravahaEngine declareStream(StreamSchema schema);

    /** Declares a stream from a {@code name:TYPE,...} spec -- the spelling the CLI and the server use. */
    PravahaEngine declareStream(String name, String schemaSpec);

    /**
     * Declares a stream whose rows carry their event time in {@code eventTimeColumn}.
     *
     * <p>Without an event-time column no watermark can advance and no window ever closes, so a
     * windowed query over a stream declared without one ingests every row and emits nothing.
     */
    PravahaEngine declareStream(String name, String schemaSpec, String eventTimeColumn);

    /** Feeds {@code stream} from the source plugin that reports itself as {@code plugin}. */
    PravahaEngine bindSource(String stream, String plugin, Map<String, String> options);

    /** Makes {@code table} a dimension table queries may join against, served by a lookup plugin. */
    PravahaEngine bindLookup(String table, String plugin, Map<String, String> options);

    /** Makes {@code sink} a name a continuous query may write its changelog to. */
    PravahaEngine bindSink(String sink, String plugin, Map<String, String> options);

    /**
     * A query registered at start, after anything the journal brings back. A declared query the
     * journal already recovered is left as recovered rather than registered twice.
     */
    PravahaEngine declareQuery(ContinuousQuery query);

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts the engine: opens plugins, builds the registry over the declared streams, binds sources,
     * lookups and sinks, recovers journalled queries, then registers declared ones.
     *
     * @throws IllegalStateException if already started, or if it previously failed. A failed engine
     *     is not restarted in place -- whatever failed is still in whatever state it failed in, and
     *     restarting over it hides the cause.
     */
    void start();

    /** Stops the engine, draining work. Idempotent. */
    void stop();

    EngineState state();

    /** The configuration this engine was built with. Never mutated. */
    Configuration configuration();

    /** The plugins available to this engine. */
    PluginRegistry plugins();

    /** A stable identifier for this instance, used in logs and metrics. */
    String instanceId();

    // ------------------------------------------------------------------ running

    /** The streams this engine plans against, as the registry identified them. */
    List<StreamSchema> streams();

    /**
     * Registers a continuous query, or attaches its name to the computation that already answers
     * the same question.
     */
    RegisteredQuery register(ContinuousQuery query);

    /** Registers {@code sql} as {@code name}, keyed by the named output columns. */
    RegisteredQuery register(String name, String sql, String... keyColumns);

    /**
     * Runs one query over the maintained views, at their committed frontier.
     *
     * <p>Also runs the continuous-query statements -- {@code CREATE CONTINUOUS QUERY name KEYED BY
     * (...) AS SELECT ...}, {@code DROP}/{@code PAUSE}/{@code RESUME CONTINUOUS QUERY name} and
     * {@code SHOW CONTINUOUS QUERIES} -- answering with the registration, the new state or the
     * listing as rows. {@code docs/CONTINUOUS_QUERIES.md} section 3 has the grammar.
     *
     * @param parameters values for the query's {@code ?} placeholders, in order
     */
    ViewQuery.Result query(String sql, Object... parameters);

    /** {@link #query(String, Object...)}, each row read into a record by column name. */
    <R> List<R> query(Class<R> rowType, String sql, Object... parameters);

    /**
     * Receives every commit of {@code queryName}'s view from now on: whole commits, in order, with
     * retractions as changes of weight {@code -1}.
     *
     * <p>The consumer runs on the thread that committed and must not block it; hand work to an
     * executor of your own. Close the returned subscription to stop.
     */
    Subscription subscribe(String queryName, Consumer<List<RowChange>> consumer);

    /** {@link #subscribe(String, Consumer)} with an explicit buffer and overflow policy. */
    Subscription subscribe(String queryName, SubscriptionOptions options, Consumer<List<RowChange>> consumer);

    /**
     * Pushes rows into {@code stream}, one {@code Object[]} per row in column order, and returns once
     * every running query reading the stream has applied and committed them.
     *
     * <p>The whole batch is checked before any row is delivered, so a push with one bad row in it
     * delivers nothing. A paused query drops what it is pushed -- a pause promises the view keeps
     * answering where it stopped, not that nothing is missed.
     *
     * @return how many computations the rows reached; zero means no running query reads the stream
     */
    int push(String stream, Object[]... rows);

    /** {@link #push(String, Object[]...)} for a list of rows. */
    int push(String stream, List<Object[]> rows);

    /** Pushes one row given by column name; a column the map does not name is null. */
    int push(String stream, Map<String, ?> row);

    /**
     * Declares that no row of {@code stream} earlier than {@code watermark} is still coming, which is
     * what closes windows over rows that were pushed rather than read from a bound source.
     */
    void advanceEventTime(String stream, Instant watermark);

    /** The query registered under {@code name}, if any. */
    Optional<RegisteredQuery> find(String name);

    /** Every registered name, in registration order. */
    Set<String> queries();

    /** Stops a query without releasing it; its view keeps answering at the frontier it reached. */
    void pause(String name);

    void resume(String name);

    /** Removes a name, releasing the computation when it was the last one. */
    void drop(String name);

    /**
     * The registry underneath, for what this interface does not say. Available while running.
     *
     * <p>The embedded engine is a host for this registry rather than a second API over it: anything
     * the registry can do -- classifying parameters, sink status, lane sharing -- is reachable here.
     */
    QueryRegistry registry();

    @Override
    void close();
}

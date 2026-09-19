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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.ParameterPlacement;
import com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery;

/**
 * Where a piece of SQL becomes a computation with a name, a state and an end (ADR-025).
 *
 * <p>Registration is the surface that was missing. Everything that makes Pravaha a system rather
 * than a library needs one: a subscription attaches to a registered query, the console lists them,
 * a cluster assigns them to nodes, and a continuous query's parameters can only be classified
 * against one.
 *
 * <p><strong>Sharing is by fingerprint, never by name or text.</strong> Two registrations whose
 * plans normalise to the same thing are one computation with two names, holding one copy of the
 * state. That is the mechanism behind the claim that ten analysts opening the same dashboard cost
 * one query rather than ten, and it is enforced here rather than left to whoever writes the SQL.
 *
 * <p>Registration is authorized, and not as an afterthought. It is a more consequential act than a
 * read: a read costs a scan and ends, while a registration takes memory and a share of every lane
 * for as long as it exists. A principal who may not read a stream must not be able to commit the
 * cluster to maintaining an answer over it.
 */
public final class QueryRegistry implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(QueryRegistry.class.getName());

    /** The default ceiling on keys in a view a registration creates. */
    public static final int DEFAULT_MAX_KEYS = 1_000_000;

    private final ViewCatalog views;
    private final SecurityPolicy policy;
    private final AuditSink audit;
    private final StreamSchema[] streams;
    /**
     * What a registration keeps when it does not say. Forever, since TY-21.
     *
     * <p>This was {@link Retention#DEFAULT} -- 24 hours of <em>event</em> time -- and nothing on the
     * server ever called {@link #retaining}, so every query registered against a node got it without
     * asking and without being told. A view's {@code appliedFrontier} tracks the highest event time
     * ever applied, and {@code ServedView.commit} evicts any row more than that age behind it. Over
     * data whose timestamps span years -- which is ordinary for a backfill, a replay, or a reference
     * mirror -- one row near "now" silently evicts most of the view: measured at 2 of 5 rows
     * surviving on a fixture used across many cases as "just some rows".
     *
     * <p>The view still answered. That is what makes it a blocker rather than a tuning default: a
     * short answer and a complete answer are indistinguishable to the caller, so nobody finds out.
     *
     * <p>Forever is the safe direction and not an unbounded one. The view's capacity ceiling is
     * still enforced and still <em>fails</em> -- {@code PRV-4001}, loudly -- so a query whose key
     * space was misjudged is refused rather than quietly shortened. ADR-037 makes the same argument
     * for spilling over shedding: losing rows silently is the outcome worth avoiding, and an error
     * is better than a wrong answer.
     */
    private Retention defaultRetention = Retention.forever();

    // Insertion-ordered so that listing a registry is stable, which matters for a console that
    // renders the list and for a test that asserts on it.
    private final Map<String, RegisteredQuery> byName = new LinkedHashMap<>();
    private RegistryJournal journal;
    /**
     * Lanes for a registry, which is a different machine from lanes for one query.
     *
     * <p>{@code LaneConfig.defaults()} spins, and spinning is right for the case it was written for:
     * one query, a source that never stops, latency that matters more than a core. A registry is the
     * opposite case. It holds many queries, most of them idle most of the time, and each one owns a
     * lane thread -- so the default spent a core per eleven idle queries doing nothing. Measured:
     * three idle queries at 26% of a core, nine at 92%, with the source dry and no rows arriving. A
     * two-core container saturates at about twenty idle registrations.
     *
     * <p>{@code BACKOFF_PARK} is what {@code WaitStrategy} documents for exactly this, and nothing
     * ever selected it.
     */
    /**
     * The threads every query's lane runs on, created on first use and shared by all of them.
     *
     * <p>ADR-027. A lane used to own a thread, so a thousand registrations were a thousand platform
     * threads -- which is what "fine at tens" meant and why it was true. A runner drives many lanes
     * from a fixed set of threads, sized by cores, so the count stops following the registrations.
     *
     * <p>Confinement is unchanged and is the reason this is a runner rather than a pool: a lane
     * belongs to one runner thread from the moment it is hosted until it is dropped, and a runner
     * steps its lanes one at a time. Nothing about the lock-free hot path changes.
     *
     * <p>Lazily created so a registry that never registers anything starts no threads, which is what
     * a great many of this project's tests are.
     */
    private volatile com.ash.messaging.pravaha.runtime.lane.LaneRunner laneRunner;

    private synchronized com.ash.messaging.pravaha.runtime.lane.LaneRunner laneRunner() {
        if (laneRunner == null) {
            laneRunner = new com.ash.messaging.pravaha.runtime.lane.LaneRunner(
                    com.ash.messaging.pravaha.runtime.lane.LaneRunner.defaultThreads(),
                    "pravaha-lane-runner",
                    laneConfig.waitStrategy());
        }
        return laneRunner;
    }

    /**
     * Whether registrations share multiplexed lanes rather than each taking one of their own, and
     * if so, on how many lanes and at what ceiling. Null when every query gets a lane of its own.
     *
     * <p>W9-8. ADR-027 already removed the thread per query — {@link
     * com.ash.messaging.pravaha.runtime.lane.LaneRunner} drives many lanes from a pool sized to the
     * cores — so what is left on the table is the <em>inbox and arena</em> a lane owns, about 1,024
     * KiB idle per query. Multiplexing shares one of each between every pipeline on the lane.
     *
     * <p>A row carries the identity of its stream (W9-9), and a watermark advance no longer clamps
     * the lane's batch (W9-10). Which lane a registration lands on is {@link SharedLanes}'s
     * decision, and its javadoc gives the rules and why they are what they are.
     *
     * <p>Off unless asked for. A node reaches it through {@code pravaha.lane.multiplex.*}; an
     * embedder through {@link #multiplexingLanes(int, int)}. It stays off by default because
     * sharing a lane shares its fate: a pipeline that throws kills the lane, and with it every
     * query on it, where a lane per query loses one.
     */
    private SharedLanes sharedLanes;

    /** How many lanes and what ceiling {@link #sharedLanes} is built with, once multiplexing is on. */
    private int sharedLaneCount;

    private int maxQueriesPerSharedLane;

    /** Which shared lane each hosted computation is on. Absent means a lane of its own. */
    private final Map<QueryFingerprint, Integer> sharedLaneOf = new java.util.HashMap<>();

    private synchronized SharedLanes sharedLanes() {
        if (sharedLanes == null) {
            sharedLanes =
                    new SharedLanes(sharedLaneCount, maxQueriesPerSharedLane, laneConfig, access, this::laneRunner);
        }
        return sharedLanes;
    }

    /**
     * How many query pipelines are sharing each multiplexed lane, or empty when not multiplexing.
     *
     * <p>One entry per configured lane, zero for a lane nothing has been placed on yet. The number
     * ADR-036's density argument is actually about: with a lane per query this is meaningless, and
     * with multiplexing on it is how many queries a lane's single inbox and arena are serving. An
     * operator watching a node approach its budget wants this beside the per-query byte counts,
     * because those attribute the <em>shared</em> lane to every query on it — two queries on one
     * lane each report that lane's inbox, so summing them overstates the node.
     */
    public synchronized java.util.List<Integer> pipelinesPerSharedLane() {
        if (sharedLaneCount == 0) {
            return java.util.List.of();
        }
        return sharedLanes().pipelinesPerLane();
    }

    /**
     * The shared lane a registered name's computation runs on, or empty when it has a lane of its
     * own -- because multiplexing is off, or because admission control found no lane for it.
     */
    public synchronized java.util.Optional<Integer> sharedLaneOf(String name) {
        return java.util.Optional.ofNullable(sharedLaneOf.get(require(name).fingerprint()));
    }

    /**
     * Computations running on a lane of their own.
     *
     * <p>With multiplexing off, all of them. With it on, the ones admission control could not host:
     * every lane at its ceiling, a lane for their stream already taken on each, or a query reading
     * more than one stream. Each costs the inbox multiplexing was turned on to save, so a number
     * that keeps rising on a multiplexing node is the signal to add shared lanes or raise the
     * ceiling.
     */
    public synchronized int queriesOnOwnLanes() {
        return byFingerprint.size()
                - (int) byFingerprint.keySet().stream()
                        .filter(sharedLaneOf::containsKey)
                        .count();
    }

    /** The per-lane ceiling in force, or zero when not multiplexing. */
    public synchronized int maxQueriesPerSharedLane() {
        return sharedLaneCount == 0 ? 0 : maxQueriesPerSharedLane;
    }

    /** Where a registration's named sink is resolved. {@link SinkFactory#NONE} until one is given. */
    private SinkFactory sinks = SinkFactory.NONE;

    /**
     * The sink each registration writes to, by query name -- per name and not per computation,
     * because ADR-043 fans a shared computation out to every sink bound to any of its names, and a
     * drop lets go of that name's sink alone.
     */
    private final java.util.Map<String, SinkDelivery> deliveries = new java.util.LinkedHashMap<>();

    /**
     * Resolves the sinks registrations name (ADR-043, W8-13).
     *
     * <p>The mirror of {@link #feedingFrom}: {@code PluginSinks} knows about ServiceLoader and
     * configuration, and the registry knows only that a name resolves to something it can ask about.
     */
    public QueryRegistry writingTo(SinkFactory factory) {
        this.sinks = factory == null ? SinkFactory.NONE : factory;
        return this;
    }

    /** The sink a registered query writes to, or empty when it writes only to its view. */
    public synchronized java.util.Optional<String> sinkOf(String queryName) {
        return java.util.Optional.ofNullable(deliveries.get(queryName)).map(SinkDelivery::sinkName);
    }

    /**
     * Why a registration's sink stopped, or empty while it is writing or when there is none.
     *
     * <p>A sink that refuses a batch is detached rather than written past ({@code PRV-8009}); this
     * is where an operator finds out, since the query itself keeps running and its view keeps
     * answering.
     */
    public synchronized java.util.Optional<PravahaException> sinkFailure(String queryName) {
        SinkDelivery delivery = deliveries.get(queryName);
        return delivery == null ? java.util.Optional.empty() : delivery.failure();
    }

    /** Rows a registration's sink has accepted, or zero when it has none. */
    public synchronized long rowsWrittenToSink(String queryName) {
        SinkDelivery delivery = deliveries.get(queryName);
        return delivery == null ? 0 : delivery.rowsWritten();
    }

    /**
     * Registrations share one multiplexed lane instead of each owning one, or stop sharing.
     *
     * <p>The embedder's original switch, kept with its original meaning: one shared lane and no
     * ceiling, so {@code true} is {@code multiplexingLanes(1, Integer.MAX_VALUE)}. Placement still
     * applies -- a second query over a stream already on that lane, or a join, gets a lane of its
     * own (see {@link SharedLanes}).
     */
    public QueryRegistry multiplexingLanes(boolean on) {
        return on ? multiplexingLanes(1, Integer.MAX_VALUE) : multiplexingLanes(0, 0);
    }

    /**
     * Registrations share {@code lanes} multiplexed lanes, at most {@code maxQueriesPerLane} to a
     * lane, and a registration that fits on none of them gets a lane of its own (W9-8).
     *
     * <p>{@code lanes} of zero turns multiplexing off. Settled before the first registration and not
     * after: a lane already carrying queries cannot be resized under them.
     */
    public synchronized QueryRegistry multiplexingLanes(int lanes, int maxQueriesPerLane) {
        if (lanes < 0) {
            throw new IllegalArgumentException("shared lane count cannot be negative, got " + lanes);
        }
        if (lanes > 0 && maxQueriesPerLane < 1) {
            throw new IllegalArgumentException(
                    "a shared lane must be allowed at least one query, got " + maxQueriesPerLane);
        }
        if (!byFingerprint.isEmpty()) {
            throw new IllegalStateException("queries are already registered on the lanes they were placed on; "
                    + "configure multiplexing before the first registration");
        }
        if (sharedLanes != null) {
            // Built by an earlier configuration and carrying nothing, since nothing is registered.
            sharedLanes.close();
            sharedLanes = null;
        }
        this.sharedLaneCount = lanes;
        this.maxQueriesPerSharedLane = lanes == 0 ? 0 : maxQueriesPerLane;
        return this;
    }

    private LaneConfig laneConfig = LaneConfig.defaults()
            .withWaitStrategy(com.ash.messaging.pravaha.common.queue.WaitStrategy.Kind.BACKOFF_PARK)
            .withThreads("pravaha-query", true);

    private MemoryAccess access = MemoryAccess.best();
    private Duration watermarkIdleAfter;
    private Duration watermarkTick;
    private final Map<QueryFingerprint, RegisteredQuery> byFingerprint = new LinkedHashMap<>();

    /** Attaches data to a query's inputs. Nothing, until a deployment says otherwise. */
    private SourceFeedFactory feeds = SourceFeedFactory.NONE;

    /** Where checkpoints are written, and how often. Null when nothing is checkpointed. */
    private java.nio.file.Path checkpointRoot;

    private com.ash.messaging.pravaha.common.config.Configuration checkpointConfiguration;

    public QueryRegistry(ViewCatalog views, StreamSchema... streams) {
        this(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, streams);
    }

    public QueryRegistry(ViewCatalog views, SecurityPolicy policy, AuditSink audit, StreamSchema... streams) {
        this.views = views;
        this.policy = policy;
        this.audit = audit;
        this.streams = identify(streams);
    }

    /**
     * The streams this registry knows, as it knows them -- identified.
     *
     * <p>A caller that built a schema and handed it here gets back the copy carrying an id, which is
     * the one rows are written against. The two are not interchangeable and the difference is W9-9.
     */
    public StreamSchema[] streams() {
        return streams.clone();
    }

    /**
     * Gives every stream in this catalogue an identity, so a row can say which one it came from.
     *
     * <p>Sequential from one, in the order the registry was given them. Zero is reserved for "nobody
     * assigned one" and is what a schema built by hand carries, which is why it is reserved rather
     * than simply unused: the one consumer that dispatches by this value refuses zero instead of
     * treating it as a stream (W9-9).
     *
     * <p>Assigned here rather than derived from the name, and the alternative is worth naming
     * because it is the tempting one. Hashing a stream name needs no plumbing at all and is how two
     * streams come to share an identity silently -- one stream's rows delivered to the other's
     * queries, the same defect as PF-10 and W8-8. A counter cannot collide.
     *
     * <p>A schema that already carries an id keeps it: a registry that re-wraps a schema another
     * registry identified must not renumber it underneath the rows already written.
     */
    private static StreamSchema[] identify(StreamSchema[] given) {
        StreamSchema[] identified = new StreamSchema[given.length];
        int next = 1;
        for (int i = 0; i < given.length; i++) {
            identified[i] =
                    given[i].streamId() == StreamSchema.UNASSIGNED_STREAM_ID ? given[i].withStreamId(next++) : given[i];
        }
        return identified;
    }

    /** Dimension tables registered queries may join against, by the name the SQL refers to. */
    private final Map<String, com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin> lookups = new LinkedHashMap<>();

    private final Map<String, StreamSchema> lookupSchemas = new LinkedHashMap<>();

    /**
     * How each registered query's execution is built.
     *
     * <p>Defaulted so an embedder does not have to care, and overridable because a deployment might:
     * a registry holding forty queries and one holding four want different arena sizes, and only the
     * deployment knows which it is.
     *
     * <p>One lane per query, and no longer one thread per query: every lane runs on the shared
     * {@code LaneRunner} this registry owns, so a node's thread count follows its cores rather than
     * its registrations (W9-5). Measured at 200 queries for 24 threads, where it was 200.
     *
     * <p>Keyed aggregates are still refused on more than one lane (ADR-034), because nothing routes
     * a row to the lane that owns its group.
     *
     * <p>What "fine at tens" used to mean was the thread, and that is fixed. What is left is the
     * lane's own inbox -- about a megabyte a query -- which is per query only because each query has
     * a lane. `LaneMultiplexer` is what makes many share one, and {@link #multiplexingLanes(int, int)}
     * is how a registry is told to use it (W9-8).
     */
    public QueryRegistry executingWith(LaneConfig laneConfig, MemoryAccess access) {
        this.laneConfig = laneConfig;
        this.access = access;
        return this;
    }

    /**
     * Derives watermarks for every query registered after this call.
     *
     * <p>Without it a registered query's windows close only when its input ends, which on a
     * continuous query is never -- so joins never evict and views never forget. See ADR-034 and
     * CONCEPTS section 3.
     */
    /**
     * Attaches a source of rows to registered queries.
     *
     * <p>Without this a registration builds an execution with lanes, arenas and watermarks and then
     * waits forever, because nothing hands it a row. That is correct for an engine embedded in a
     * process that pushes its own rows through {@link RegisteredQuery#accept}, and it is why a
     * server needs to say so explicitly rather than inherit a default that reads files.
     */
    /**
     * Binds a dimension table that registered queries may join against.
     *
     * <p>Lookup joins were implemented, optimised, tested and documented, and unreachable: {@code
     * withLookups} had no caller anywhere in main, so every registration planned every schema as a
     * consumed stream. A query joining a dimension planned as a stream-to-stream join and waited
     * for rows a dimension table never sends -- a documented feature that no shipped surface could
     * execute.
     *
     * <p>The plugin supplies its own schema and its own key columns, because the store is what
     * knows them: a dimension declared by hand can disagree with the table, and a join on a column
     * the store is not indexed for is a full scan per record.
     */
    public QueryRegistry lookingUp(com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin plugin) {
        StreamSchema schema = plugin.schema();
        lookups.put(schema.name(), plugin);
        lookupSchemas.put(schema.name(), schema);
        return this;
    }

    public QueryRegistry feedingFrom(SourceFeedFactory factory) {
        this.feeds = factory == null ? SourceFeedFactory.NONE : factory;
        return this;
    }

    /**
     * Checkpoints every registered query into its own directory under {@code root}.
     *
     * <p>{@link com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer} and {@code
     * FileCheckpointStore} were both built and tested and neither was ever constructed outside a
     * test, so a registered query kept no checkpoints at all. The journal brought query
     * <em>definitions</em> back after a restart -- with re-authorization, which is the right design
     * -- and their aggregates, join state and open windows came back empty.
     *
     * <p>A directory per query, named by the registration. Sharing one store between queries would
     * make pruning global: the newest three checkpoints across a node rather than the newest three
     * of each query, so a busy query would evict a quiet one's only fallback.
     */
    public QueryRegistry checkpointingTo(
            java.nio.file.Path root, com.ash.messaging.pravaha.common.config.Configuration configuration) {
        this.checkpointRoot = root;
        this.checkpointConfiguration = configuration == null
                ? com.ash.messaging.pravaha.common.config.Configuration.builder()
                        .build()
                : configuration;
        return this;
    }

    public QueryRegistry generatingWatermarks(Duration idleAfter, Duration tick) {
        this.watermarkIdleAfter = idleAfter;
        this.watermarkTick = tick;
        return this;
    }

    /** With the engine's defaults: a thirty-second idle timeout and a one-second tick. */
    public QueryRegistry generatingWatermarks() {
        return generatingWatermarks(QueryExecution.DEFAULT_IDLE_AFTER, QueryExecution.DEFAULT_TICK);
    }

    /**
     * Sets the retention every subsequent registration gets unless it chooses its own.
     *
     * <p>Configurable because the right answer is a deployment's, not ours: an intraday trade feed
     * wants a day, a fraud view wants an hour, a reference-data mirror genuinely wants forever --
     * which is now what a registration gets when it does not choose (TY-21).
     *
     * <p>Expressed in event time, because that is what a streaming answer is about. A row count
     * would make the view's meaning depend on throughput; the row bound that does exist is the
     * view's capacity ceiling, which is a different question with a different answer.
     */
    public QueryRegistry retaining(Retention retention) {
        // Null means "back to the default", and TY-21 changed what that is: forever, not a day.
        this.defaultRetention = retention == null ? Retention.forever() : retention;
        return this;
    }

    /** The retention a registration gets when it does not ask for one. */
    public Retention defaultRetention() {
        return defaultRetention;
    }

    /**
     * Registers {@code sql} under {@code name}, or attaches the name to the computation that already
     * answers it.
     *
     * @param keyColumns the output columns the view is keyed by, as ordinals. A view with no key is
     *     a log rather than a view, so at least one is required
     */
    public synchronized RegisteredQuery register(
            String name, String sql, List<Integer> keyColumns, Principal principal) {
        return register(name, sql, keyColumns, principal, defaultRetention);
    }

    /**
     * Says where each of a query's {@code ?} parameters would have to be applied, and what it costs
     * (ADR-032).
     *
     * <p>Worth asking before registering a parameterised continuous query, because the answer
     * decides how many computations a deployment runs. A parameter the view carries is free: one
     * computation, and every subscriber filters at its own tap. A parameter the query aggregates
     * away needs a computation per distinct value.
     */
    public synchronized List<ParameterPlacement> classify(String sql) {
        return PreparedContinuousQuery.classify(sql, streams);
    }

    /**
     * Registers a parameterised continuous query with values bound into it.
     *
     * <p>Binding into the query is always <em>correct</em> and is sometimes wasteful: each distinct
     * binding is a separate computation with its own state, because the bound values are part of the
     * plan and therefore part of the fingerprint. When the view carries the parameter's column the
     * same effect is available for nothing -- register once, and let each subscriber filter at its
     * tap -- so this reports which parameters were in that position rather than leaving the cost to
     * be found later.
     *
     * @return the registration, whose {@link RegisteredQuery#parameterPlacements()} says what was
     *     decided and why
     */
    public synchronized RegisteredQuery register(
            String name, String sql, List<Integer> keyColumns, Principal principal, BoundParameters parameters) {
        return register(name, sql, keyColumns, principal, defaultRetention, parameters);
    }

    /** Registers with an explicit retention, overriding this registry's default. */
    public synchronized RegisteredQuery register(
            String name, String sql, List<Integer> keyColumns, Principal principal, Retention retention) {
        return register(name, sql, keyColumns, principal, retention, BoundParameters.none());
    }

    /**
     * Registers a query that also writes its changelog to a named sink (ADR-043, W8-13).
     *
     * <p>The sink is named as an argument rather than in the SQL. {@code INSERT INTO <sink> SELECT}
     * is the better eventual form and every comparable engine uses it, but the planner refuses
     * {@code INSERT} with {@code PRV-2020} on the grounds that Pravaha answers questions and sinks
     * write results -- giving the keyword a second meaning needs a DML surface that exists only as a
     * refusal today. ADR-043 records that, and the reasoning for fanning a shared computation out to
     * every sink bound to it rather than forking the computation, since a sink does not change the
     * answer.
     *
     * <p><strong>The changelog check happens here, before the feed opens.</strong> Design section
     * 15.5's failure is silent: a query that revises its answer, pointed at a sink that can only
     * append, corrupts that sink with rows which are each individually correct and a total that is
     * wrong for ever. So the sink is asked what it can take -- without being opened -- and the pair
     * is refused before a row exists.
     */
    public synchronized RegisteredQuery registerWritingTo(
            String name, String sql, List<Integer> keyColumns, Principal principal, String sinkName) {
        if (sinkName == null || sinkName.isBlank()) {
            throw new IllegalArgumentException("a sink name is required; use register(...) to write only a view");
        }
        return register(name, sql, keyColumns, principal, Retention.forever(), sinkName);
    }

    /** Registers with both an explicit retention and bound parameters. */
    public synchronized RegisteredQuery register(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters) {
        return register(name, sql, keyColumns, principal, retention, parameters, null);
    }

    private synchronized RegisteredQuery register(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            String sinkName) {
        return register(name, sql, keyColumns, principal, retention, BoundParameters.none(), sinkName);
    }

    private synchronized RegisteredQuery register(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters,
            String sinkName) {
        requireName(name);
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "a registration needs at least one key column: a view with no key is a log, and a "
                            + "point read against it has nothing to look up");
        }

        PreparedContinuousQuery prepared = PreparedContinuousQuery.of(
                sql, parameters, java.util.List.of(streams), List.copyOf(lookupSchemas.values()));
        List<ParameterPlacement> placements = prepared.placements();
        PhysicalOperator plan = prepared.plan();

        if (sinkName != null) {
            // Before the feed, before the view, before a row can exist. capabilitiesOf configures
            // the plugin and asks it, without opening a connection, so a refusal costs nothing --
            // and a query whose changelog the sink cannot take is refused as a PAIR: the query may
            // be perfectly good against a different sink, and the fix is usually the sink rather
            // than the SQL.
            SinkFactory.Description sink = sinks.describe(sinkName);
            com.ash.messaging.pravaha.sql.plan.ChangelogAnalysis.checkAgainst(plan, sink.capabilities(), sinkName);
            requireSinkShape(sink, plan.outputSchema(), keyColumns, sinkName);
        }

        AccessDecision decision = policy.mayRegisterQuery(principal);
        audit.record(AuditEvent.of(principal, "register", name, decision, sql));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not register a query: " + decision.reason());
        }

        // May this principal read what the query reads?
        //
        // Registration used to ask only whether somebody may register *anything*, and never
        // whether they may read the streams the query names. So a principal who could register
        // but could not read `payroll` could register `SELECT * FROM payroll` under a name of
        // their choosing and then read that view -- because the read check is against the view's
        // name, and the policy was never told what the view derives from. A careful policy author
        // could not have refused it; the engine gave them nothing to refuse on.
        List<String> rowFilters = new ArrayList<>();
        for (String source : sourceStreams(plan)) {
            AccessDecision read = policy.mayRead(principal, source);
            audit.record(AuditEvent.of(principal, "register:source", source, read, sql));
            if (!read.allowed()) {
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        principal.id() + " may not register '" + name + "' because it reads '" + source
                                + "', which they may not read: " + read.reason()
                                + ". A registration is a standing read of everything the query names, so it "
                                + "is refused here rather than at the first row.");
            }
            read.rowFilter().ifPresent(rowFilters::add);
        }
        // Sorted, so two principals holding the same filters in a different order share, and two
        // holding different ones do not.
        Collections.sort(rowFilters);

        // Bound values are in the plan, so they are in the fingerprint: two bindings of the same SQL
        // are two computations. That is the truth rather than a policy, and it is precisely why a
        // parameter the view carries should be a tap filter instead -- same answer, one computation.
        //
        // The principal's row filters are in it too, and that is the point of the two-argument
        // form. Without them, a principal restricted to one region and a principal restricted to
        // none produced the same fingerprint, shared one computation and one copy of the state --
        // and the read path was the only thing standing between that and the restricted principal
        // seeing everything.
        // I-3: the key columns and the retention are part of what makes a computation itself.
        // Without them `--keys 1` and `--keys 0,1` over identical SQL shared one view, keyed as the
        // first registrant asked and with the second one's retention dropped, silently.
        QueryFingerprint fingerprint = QueryFingerprint.of(plan, rowFilters, keyColumns, retention);

        // Opened after every refusal above and before anything runs, so a registration refused for
        // its SQL, its sink's changelog or its principal never opened a connection -- and a fresh
        // computation can attach the sink before its feed delivers a row.
        SinkDelivery delivery = sinkName == null ? null : openDelivery(name, sinkName, plan.outputSchema());
        try {
            return register(
                    name,
                    sql,
                    keyColumns,
                    principal,
                    retention,
                    parameters,
                    sinkName,
                    plan,
                    placements,
                    fingerprint,
                    delivery);
        } catch (RuntimeException e) {
            if (delivery != null) {
                delivery.close();
            }
            throw e;
        }
    }

    /**
     * Refuses a sink that would read this query's rows as something else.
     *
     * <p>A sink reads each row through the schema it was configured with, and the engine hands it rows
     * laid out as the query produced them. A column added or two swapped means every value is read
     * from the wrong offset and written with the wrong name, and nothing fails -- the sink fills with
     * plausible nonsense. Types are compared by name and nullability is ignored, since a sink writes a
     * null wherever the query produces one.
     *
     * <p>And a keyed sink must key records by exactly the view's key. On fewer columns, distinct rows
     * collapse onto one record and retracting one deletes the other; on more, a changed row leaves its
     * old record behind. The engine emits a retraction and an insert per change, and both land on the
     * wrong record.
     */
    private static void requireSinkShape(
            SinkFactory.Description sink, StreamSchema output, List<Integer> keyColumns, String sinkName) {
        sink.schema().ifPresent(declared -> {
            boolean same = declared.fieldCount() == output.fieldCount();
            for (int ordinal = 0; same && ordinal < output.fieldCount(); ordinal++) {
                same = declared.field(ordinal)
                                .name()
                                .equalsIgnoreCase(output.field(ordinal).name())
                        && declared.field(ordinal).type().typeName()
                                == output.field(ordinal).type().typeName();
            }
            if (!same) {
                throw new PravahaException(
                        RegistryErrors.SINK_SHAPE_MISMATCH,
                        "sink '" + sinkName + "' is configured for rows " + describe(declared)
                                + ", and this query produces " + describe(output)
                                + ". The sink reads each row through its own schema, so every column would be "
                                + "read from the wrong place and nothing would fail. Make the query's SELECT "
                                + "list match the sink's schema in order, name and type, or change the "
                                + "binding's schema.");
            }
        });
        if (!sink.keyColumns().isEmpty()) {
            java.util.Set<String> viewKey = new java.util.TreeSet<>();
            for (int ordinal : keyColumns) {
                viewKey.add(output.field(ordinal).name().toLowerCase(java.util.Locale.ROOT));
            }
            java.util.Set<String> sinkKey = new java.util.TreeSet<>();
            sink.keyColumns().forEach(column -> sinkKey.add(column.toLowerCase(java.util.Locale.ROOT)));
            if (!viewKey.equals(sinkKey)) {
                throw new PravahaException(
                        RegistryErrors.SINK_SHAPE_MISMATCH,
                        "sink '" + sinkName + "' keys its records by " + sinkKey
                                + ", and this query's view is keyed by "
                                + viewKey + ". A retraction deletes the sink record its key names: keyed on "
                                + "fewer columns than the view, distinct rows share a record and withdrawing "
                                + "one deletes the other; keyed on more, a changed row leaves its old record "
                                + "behind. Register with --keys naming " + sinkKey + ", or key the sink by "
                                + viewKey + ".");
            }
        }
    }

    private static String describe(StreamSchema schema) {
        List<String> columns = new ArrayList<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            columns.add(schema.field(ordinal).name() + ":"
                    + schema.field(ordinal).type().typeName());
        }
        return "(" + String.join(", ", columns) + ")";
    }

    /**
     * Says what a registration's sink is promised, where an operator will see it.
     *
     * <p>At registration and once, because the answer depends on the sink's declaration and on
     * whether this node checkpoints, and neither changes while the query runs. An operator who reads
     * "at-least-once" here knows before the first reconciliation that duplicates are possible.
     */
    private static void announce(String name, SinkDelivery delivery) {
        LOG.log(
                System.Logger.Level.INFO,
                "query '" + name + "' writes to sink '" + delivery.sinkName() + "', " + delivery.guarantee());
    }

    /**
     * What a registration's sink is promised -- exactly-once, effectively-once or at-least-once, and
     * why -- or empty when it writes only to its view. The same words {@link #announce} logs.
     */
    public synchronized java.util.Optional<String> sinkGuarantee(String queryName) {
        return java.util.Optional.ofNullable(deliveries.get(queryName)).map(SinkDelivery::guarantee);
    }

    private SinkDelivery openDelivery(String name, String sinkName, StreamSchema schema) {
        SinkFactory factory = sinks;
        return new SinkDelivery(name, sinkName, factory.open(sinkName), schema, access, factory::release);
    }

    private RegisteredQuery register(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters,
            String sinkName,
            PhysicalOperator plan,
            List<ParameterPlacement> placements,
            QueryFingerprint fingerprint,
            SinkDelivery delivery) {
        RegisteredQuery existing = byFingerprint.get(fingerprint);
        if (existing != null && !existing.state().isTerminal()) {
            // The same question, asked again. One computation, one copy of the state, two names.
            existing.addName(name);
            byName.put(name, existing);
            // The other two things a registration does, which this path used to skip -- so sharing,
            // the feature, made the second name useless in two different ways. It answered nothing
            // ("Object not found" from a name that had just been acknowledged RUNNING), and it
            // vanished at the next restart while the node reported "recovered 2 of 2".
            views.registerAs(name, existing.view());
            if (delivery != null) {
                // ADR-043's fan-out: this name's sink listens on the running computation, seeded
                // with what the view already holds, since everything committed before now happened
                // before this sink existed.
                delivery.attachTo(existing, true);
                deliveries.put(name, delivery);
                announce(name, delivery);
            }
            try {
                journalRegistration(name, sql, keyColumns, principal, retention, parameters, sinkName);
            } catch (RuntimeException e) {
                deliveries.remove(name);
                // The same unwind the fresh path has. Without it a refusal the client could see left
                // the name held and the shared computation pinned open by a registration that,
                // as far as its caller knew, had failed.
                existing.removeName(name);
                byName.remove(name);
                views.remove(name);
                throw e;
            }
            return existing;
        }

        RegisteredQuery query = start(name, sql, plan, keyColumns, fingerprint, retention, placements, delivery);
        byName.put(name, query);
        byFingerprint.put(fingerprint, query);
        views.register(query.view());
        if (delivery != null) {
            deliveries.put(name, delivery);
            announce(name, delivery);
        }
        try {
            journalRegistration(name, sql, keyColumns, principal, retention, parameters, sinkName);
        } catch (RuntimeException e) {
            deliveries.remove(name);
            // Unwound, because a refusal the caller can see and a query that is running anyway is
            // the worst of both: the client is told the registration failed, the computation serves
            // rows regardless, and nothing will bring it back after a restart. Registering again
            // under another name then succeeded with nothing journalled at all.
            byName.remove(name);
            byFingerprint.remove(fingerprint);
            views.remove(name);
            query.close();
            throw e;
        }
        return query;
    }

    /**
     * Writes the registration down before it is acknowledged.
     *
     * <p>Ordering matters here and is the opposite of what it looks like. The journal append happens
     * after the query is running, so a query that cannot start is not recorded as if it had. But it
     * happens before {@code register} returns, so a client never gets an acknowledgement for a
     * registration that would vanish at the next restart. If the append fails the registration fails
     * with it, loudly, rather than succeeding in a way that will be silently undone later.
     */
    private void journalRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters,
            String sinkName) {
        if (journal == null) {
            return;
        }
        List<String> encoded = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            encoded.add(RegistryJournal.encodeParameter(parameters.at(index)));
        }
        // The sink goes in the same record as the registration. Two appends could leave a restart
        // with the query and without its sink, which recovers "successfully" while the table the
        // query fed stops moving.
        journal.recordRegistration(name, sql, keyColumns, principal.id(), retention, encoded, sinkName);
    }

    /** Registration during recovery: the journal is being read, so nothing is written back to it. */
    private RegisteredQuery registerWithoutJournalling(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters,
            String sinkName) {
        RegistryJournal suspended = journal;
        journal = null;
        try {
            return register(name, sql, keyColumns, principal, retention, parameters, sinkName);
        } finally {
            journal = suspended;
        }
    }

    /**
     * Every stream the plan reads, in the order it reads them.
     *
     * <p>Taken from the plan rather than from the SQL text, because the text can name a stream the
     * planner optimised away and can omit one a view expanded into. What the plan scans is what the
     * query will actually read.
     */
    /**
     * Restores the newest readable checkpoint, returning the offsets its sources should resume from.
     *
     * <p>Nothing called {@code restore} anywhere in shipped code. Checkpoints were written on a
     * schedule, pruned, permissioned -- and never read, so a restart recovered a query's definition
     * from the journal and none of what it had computed, while {@code application.yaml} said a
     * restart recovers answers.
     *
     * <p>The newest checkpoint is the likeliest to be unreadable, because it is the one that was
     * being written when the process died. Falling back to the previous one costs reprocessing;
     * failing the registration costs the query.
     */
    private Map<String, String> restoreFrom(String name, QueryExecution execution, RegisteredQuery query) {
        if (checkpointRoot == null) {
            return Map.of();
        }
        com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore store =
                new com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore(
                        checkpointRoot.resolve(checkpointDirectoryFor(name)));
        try {
            Optional<com.ash.messaging.pravaha.state.checkpoint.Checkpoint> latest = store.latest();
            if (latest.isEmpty()) {
                return Map.of();
            }
            execution.restore(latest.get(), Duration.ofSeconds(30));
            // What it recorded about sinks, for each registration to claim as it attaches: the
            // handles to commit, and the view each sink holds once they are.
            query.restoredFrom(latest.get());
            return latest.get().offsets();
        } catch (RuntimeException e) {
            // A query that starts from nothing is worse than one that starts from an older
            // checkpoint and better than one that does not start. Reprocessing is visible in the
            // numbers; a refusal to register is visible immediately; silent corruption is neither.
            return Map.of();
        }
    }

    private void startCheckpointing(String name, QueryExecution execution, RegisteredQuery query) {
        if (checkpointRoot == null) {
            return;
        }
        // The registration's name reaches the filesystem here, so it is sanitised rather than
        // trusted. requireName already refuses the obvious, but a directory is a different alphabet
        // from an identifier and "../" in a view name should not be able to choose where a
        // checkpoint lands.
        String directory = checkpointDirectoryFor(name);
        java.nio.file.Path checkpointDirectory = checkpointRoot.resolve(directory);
        com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer checkpointer =
                com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer.from(
                                execution,
                                new com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore(checkpointDirectory),
                                checkpointConfiguration,
                                // The narrative channel: start-up line, a line per success, a line per
                                // failure. Not the failure counter -- wiring the counter here counted all
                                // three, so a query whose checkpoints were all succeeding reported a rising
                                // failure count and named a success as its last failure.
                                message -> {})
                        // Failures only. PeriodicCheckpointer reports every one rather than the first,
                        // precisely so that a query which has silently not checkpointed for six hours does
                        // not look like one that has -- and the registry used to throw each report away,
                        // which produced exactly that. Recorded on the query, so an operator asking about
                        // it gets an answer.
                        .reportingFailuresTo(query::recordCheckpointFailure)
                        // The second phase: what each transactional sink prepared at the cut is
                        // committed once, and only once, the checkpoint recording it is durable.
                        .tellingWhenDurable(query::checkpointDurable);
        checkpointer.start();
        query.checkpointWith(checkpointer, checkpointDirectory);
    }

    /**
     * Deletes a checkpoint directory that a dropped computation was writing to.
     *
     * <p>Takes the path the checkpointer was given rather than a name to re-derive it from. The
     * previous version took a name and was called after the last name had already been removed, so
     * it resolved a fingerprint digest that had never been a directory, deleted nothing, and the
     * {@code NoSuchFileException} went into the catch below indistinguishable from a real one.
     */
    private void deleteCheckpointDirectory(java.nio.file.Path directory) {
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(directory)) {
            for (java.nio.file.Path entry : entries.toList()) {
                java.nio.file.Files.deleteIfExists(entry);
            }
            java.nio.file.Files.deleteIfExists(directory);
        } catch (java.io.IOException e) {
            // A drop must succeed even if the disk will not co-operate. Leftover files cost space;
            // a drop that fails half way costs a query nobody can remove.
        }
    }

    /**
     * The directory a query's checkpoints live in.
     *
     * <p>Dots are stripped along with separators, so a query named {@code ..} cannot write above the
     * configured root. A digest tail keeps two names that sanitise alike -- {@code a.b} and {@code
     * a_b} -- in separate directories, which they must be: sharing one would have each pruning the
     * other's fallbacks away.
     */
    private static String checkpointDirectoryFor(String name) {
        // Percent-style hex encoding, which is injective: two different names cannot produce one
        // directory. Replacing unsafe characters with '_' is many-to-one, and a hash suffix does not
        // rescue it -- a collision was constructed from first principles on the first attempt, and
        // `a#!b` and `a"@b` still shared a directory. Two queries in one directory share a
        // checkpoint id sequence and prune each other's fallbacks away, which is the exact failure
        // the per-query directory exists to prevent.
        //
        // '_' is encoded too, or `a_b` and `a b` would still meet.
        StringBuilder encoded = new StringBuilder(name.length() + 8);
        for (byte b : name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                encoded.append(c);
            } else {
                encoded.append('_').append(String.format("%02x", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    private static List<String> sourceStreams(PhysicalOperator plan) {
        List<String> found = new ArrayList<>();
        collectSources(plan, found);
        return found;
    }

    private static void collectSources(PhysicalOperator operator, List<String> into) {
        if (operator instanceof ScanOperator scan && !into.contains(scan.streamName())) {
            into.add(scan.streamName());
        }
        operator.inputs().forEach(input -> collectSources(input, into));
    }

    private RegisteredQuery start(
            String name,
            String sql,
            PhysicalOperator plan,
            List<Integer> keyColumns,
            QueryFingerprint fingerprint,
            Retention retention,
            List<ParameterPlacement> placements,
            SinkDelivery delivery) {
        StreamSchema schema = plan.outputSchema();
        for (int ordinal : keyColumns) {
            if (ordinal < 0 || ordinal >= schema.fieldCount()) {
                throw new IllegalArgumentException("key column " + ordinal + " is not in the query's output, "
                        + "which has " + schema.fieldCount() + " columns");
            }
        }
        // The view carries the registration's name, because that is what a reader will write in a
        // FROM clause. The fingerprint names the computation; the name names the answer.
        ServedView view = new ServedView(name, schema, keyColumns, DEFAULT_MAX_KEYS, retention)
                // SX-11. What the query reads, recorded on the view, so a reader is judged against
                // the data and not against the name a registrant happened to choose for it.
                .derivedFrom(sourceStreams(plan));
        ViewSink sink = new ViewSink(view, schema);

        // The engine, not a pipeline of our own. Until now the registry compiled an
        // InterpretedPipeline and drove it on the caller's thread, which is why a registered query
        // had no lane, no arena, no checkpointing and no watermarks: everything the runtime offers
        // belonged to the other path, and the server ran this one.
        //
        // W9-8: when multiplexing, admission control picks the shared lane, and a query it cannot
        // place runs on a lane of its own exactly as it would with multiplexing off.
        Optional<SharedLanes.Placement> placement =
                sharedLaneCount == 0 ? Optional.empty() : sharedLanes().place(streamIdsOf(plan));
        QueryExecution execution = (placement.isPresent()
                        ? QueryExecution.startOn(
                                placement.get().group(), name, plan, () -> (RowOutput) sink::begin, lookups, access)
                        : QueryExecution.start(
                                plan, 1, laneConfig, access, () -> (RowOutput) sink::begin, lookups, laneRunner()))
                // The view goes in the checkpoint too. A filter or a projection has no operator
                // accumulators, so the view is the entire answer -- and a restart that restored
                // offsets without it resumed the source past every row it had read and served an
                // empty view, with the query reporting RUNNING over the emptiness.
                .checkpointingViewWith(view::snapshot, view::restore)
                // A view snapshot this engine cannot read is refused before any lane state is put
                // back, so the replay from the sources that follows starts from empty operators
                // rather than counting everything before the checkpoint twice (VIEW-2).
                .checkingViewWith(ServedView::requireReadable);
        if (watermarkIdleAfter != null) {
            execution.generatingWatermarks(null, watermarkIdleAfter, watermarkTick);
        }
        RegisteredQuery query =
                new RegisteredQuery(fingerprint, sql, name, view, sink, execution, Instant.now(), placements);
        // The view -- and every sink on it -- is cut on the lane at the checkpoint's marker, not
        // snapshotted from the checkpointing thread whenever it gets there. See
        // RegisteredQuery.cutOutput for why that is the whole of exactly-once output.
        execution.cuttingOutputWith(query::cutOutput);

        // Last, and after the watermark generator: a feed may deliver its first row on the way out
        // of open(), and a row that arrives before the watermark partitions exist is a row whose
        // event time nothing is tracking.
        //
        // Opened per computation rather than per name. A query registered twice under two names is
        // one execution behind one fingerprint, and the second registration returns early above
        // without reaching here -- which is what stops a shared computation being fed twice and
        // double-counting every row.
        try {
            // Restore before anything is fed. State without rewound sources double-counts every
            // record between the checkpoint and the failure; rewound sources without state replays
            // them into an empty query. Both halves or neither.
            Map<String, String> resumeFrom = restoreFrom(name, execution, query);

            // Before the feed, so the first rows a source delivers are already inside a query that
            // is being checkpointed. Started after the execution exists and before anything can
            // write to it is the only window where neither ordering is wrong.
            startCheckpointing(name, execution, query);
            // Before the feed, so the sink hears the first commit there is. Nothing can have
            // committed yet, so there is nothing to seed it with.
            if (delivery != null) {
                delivery.attachTo(query, false);
            }
            query.feedFrom(feeds.open(name, execution, sourceStreams(plan), query::commit, resumeFrom));
        } catch (RuntimeException e) {
            // A feed that cannot open must not leave a half-started query behind holding a lane
            // thread and an arena. Fail the registration instead, with the execution released.
            execution.close();
            throw e;
        }
        placement.ifPresent(where -> sharedLaneOf.put(fingerprint, where.index()));
        return query;
    }

    /** The ids of the streams a plan reads, as this registry identified them. */
    private List<Integer> streamIdsOf(PhysicalOperator plan) {
        List<Integer> ids = new ArrayList<>();
        for (String stream : sourceStreams(plan)) {
            for (StreamSchema known : streams) {
                if (known.name().equals(stream)) {
                    ids.add(known.streamId());
                }
            }
        }
        return ids;
    }

    /**
     * Writes registrations to {@code journal} so they survive a restart.
     *
     * <p>Without this a registry is entirely in memory: restart the server and every continuous
     * query a client registered is gone, with no error and nothing to look at. The client finds out
     * at its next subscribe, as "no such view", and the only fix is for every client to know to
     * register again.
     *
     * <p>What is journalled is the <em>registration</em> -- name, SQL, key columns, owner, retention,
     * bound values -- and not the state. The registration is small, rarely changes, and cannot be
     * recomputed because it came from a client that may never speak again. State is large, changes
     * constantly, and can be rebuilt by reading the stream. So a restart costs a warm-up rather than
     * an outage: the views are there immediately and fill as data arrives, and a windowed query's
     * first window or two are partial.
     */
    public synchronized QueryRegistry journalTo(RegistryJournal journal) {
        this.journal = journal;
        return this;
    }

    /**
     * Re-registers everything the journal remembers.
     *
     * <p>Authorization is checked again, for each entry, against the policy as it is now. A
     * registration is not a standing permission: if the principal who registered a query has since
     * lost access, the query does not quietly come back. Replaying blindly would make the journal a
     * way to keep an entitlement after it was revoked, by having registered before it was.
     *
     * @param principals resolves a recorded owner id back to a principal. Recovery needs the identity
     *     the registration was made under, and only the deployment knows how to look one up
     * @return what was recovered, and what was refused and why. Both matter: a query that did not
     *     come back is a view some client is about to ask for
     */
    public synchronized Recovery recover(java.util.function.Function<String, Optional<Principal>> principals) {
        if (journal == null) {
            return new Recovery(List.of(), List.of());
        }
        List<String> recovered = new ArrayList<>();
        List<Recovery.Refusal> refused = new ArrayList<>();
        for (RegistryJournal.Entry entry : journal.replay()) {
            Optional<Principal> owner = principals.apply(entry.owner());
            if (owner.isEmpty()) {
                refused.add(new Recovery.Refusal(
                        entry.name(),
                        Optional.of(RegistryErrors.REPLAY_UNAUTHORIZED),
                        "its owner '" + entry.owner()
                                + "' is not a principal this deployment knows, so there is nobody to authorize it "
                                + "as"));
                continue;
            }
            try {
                List<Object> values = entry.parameters().stream()
                        .map(RegistryJournal::decodeParameter)
                        .toList();
                registerWithoutJournalling(
                        entry.name(),
                        entry.sql(),
                        entry.keyColumns(),
                        owner.get(),
                        entry.retention(),
                        values.isEmpty() ? BoundParameters.none() : BoundParameters.of(values),
                        entry.sink());
                recovered.add(entry.name());
            } catch (RuntimeException failure) {
                // One bad entry must not stop the rest. A deployment recovering forty queries should
                // not lose thirty-nine because the fortieth names a stream that has since been removed.
                refused.add(new Recovery.Refusal(entry.name(), replayRefusalCode(failure), failure.getMessage()));
            }
        }
        // Every name the journal knows has now been registered or refused, so a sink a restored
        // checkpoint recorded and nobody claimed belongs to a name that is not coming back.
        byFingerprint.values().forEach(RegisteredQuery::forgetUnclaimedSinks);
        return new Recovery(recovered, refused);
    }

    /**
     * The code to record for a refusal raised while replaying one journalled entry.
     *
     * <p>{@code register} refuses a principal who no longer holds what the journal recorded by
     * throwing {@link SecurityErrors#FORBIDDEN} -- the same code a live registration would raise for
     * an unrelated caller. During replay that is not a caller being refused; it is the exact
     * condition {@link RegistryErrors#REPLAY_UNAUTHORIZED} documents, so it is relabelled here rather
     * than surfaced under the generic code. Any other coded failure (an unusable name, a stream the
     * deployment no longer has) keeps its own code, and a bare {@link RuntimeException} carrying none
     * is recorded without one rather than inventing a code it never raised.
     */
    private static Optional<ErrorCode> replayRefusalCode(RuntimeException failure) {
        if (!(failure instanceof PravahaException coded)) {
            return Optional.empty();
        }
        return Optional.of(
                coded.errorCode().equals(SecurityErrors.FORBIDDEN)
                        ? RegistryErrors.REPLAY_UNAUTHORIZED
                        : coded.errorCode());
    }

    /**
     * What a {@link #recover} put back, and what it would not.
     *
     * @param recovered names that are registered again
     * @param refused entries that are not, each carrying its code and reason. These are views
     *     clients expect to exist, so this belongs in a log an operator reads, not in a return value
     *     nobody looks at
     */
    public record Recovery(List<String> recovered, List<Refusal> refused) {

        public Recovery {
            recovered = List.copyOf(recovered);
            refused = List.copyOf(refused);
        }

        public boolean complete() {
            return refused.isEmpty();
        }

        @Override
        public String toString() {
            return "Recovery[" + recovered.size() + " recovered, " + refused.size() + " refused]";
        }

        /**
         * One journalled registration that did not come back.
         *
         * <p>A plain {@code String} could not carry a code, so a refused replay was reported as text
         * an operator could read but nothing else could act on -- and {@code PRV-8007
         * REGISTRY_REPLAY_UNAUTHORIZED} stayed declared and unreachable because there was nowhere for
         * it to be raised. This is the structured form: the code, when the refusal has one, is
         * available to a caller that wants to branch on it, while {@link #toString()} still reads
         * exactly as the log line and the journal's own history of this refusal always have.
         *
         * @param query the name recorded in the journal
         * @param code the coded failure behind the refusal, when there was one -- empty only for a
         *     bare {@link RuntimeException} that {@link #recover} did not itself raise with a code
         * @param reason what the failure said
         */
        public record Refusal(String query, Optional<ErrorCode> code, String reason) {

            public Refusal {
                Objects.requireNonNull(query, "query");
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(reason, "reason");
            }

            /** {@code "<query>: <reason>"} -- the plain-text form this has always logged as. */
            @Override
            public String toString() {
                return query + ": " + reason;
            }
        }
    }

    /**
     * The policy this registry authorizes against.
     *
     * <p>Exposed so a server hosting it can check that it is the same one, because there are two
     * and nothing used to say so.
     */
    public SecurityPolicy policy() {
        return policy;
    }

    /** The query answering to {@code name}. */
    public synchronized Optional<RegisteredQuery> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The query registered under {@code name}, or a refusal that does not enumerate the others.
     *
     * <p>The refusal used to end with "this node has [...]", every name in {@code byName},
     * unfiltered by any policy. It is a useful thing to see when a name is misspelled and an
     * unacceptable thing to hand to a caller who may read none of them: one principal, denied on
     * every view, learned the whole catalogue by misspelling one name (STRM-9). Debugging
     * convenience is not worth a tenant list.
     *
     * <p>A caller entitled to know what exists has {@code names()} and the LIST action, both of
     * which are the right place for that question because both can be authorized.
     */
    public synchronized RegisteredQuery require(String name) {
        return find(name)
                .orElseThrow(() -> new PravahaException(
                        RegistryErrors.NO_SUCH_QUERY, "no query named '" + name + "' is registered"));
    }

    /** Every name registered, in registration order. */
    public synchronized Set<String> names() {
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(byName.keySet()));
    }

    /** Every distinct computation, which is fewer than {@link #names()} when queries are shared. */
    public synchronized List<RegisteredQuery> queries() {
        return List.copyOf(new java.util.LinkedHashSet<>(byFingerprint.values()));
    }

    public synchronized int size() {
        return byFingerprint.size();
    }

    /** Stops a query without releasing it; its view keeps answering at the frontier it reached. */
    public synchronized void pause(String name) {
        require(name).pause();
    }

    public synchronized void resume(String name) {
        require(name).resume();
    }

    /**
     * Removes a name, releasing the computation when it was the last one.
     *
     * <p>The refcount is the whole reason sharing is safe to do implicitly. Dropping on the first
     * name would take the answer away from everybody else who registered the same question and has
     * no idea anyone else exists.
     */
    public synchronized void drop(String name) {
        RegisteredQuery query = require(name);
        // Journal first: see the note below on why this order is the only honest one.
        if (journal != null) {
            journal.recordDrop(name);
        }
        byName.remove(name);
        // This name's sink alone. Another name on the same computation may write to a sink of its
        // own, and keeps doing so. Finished rather than closed: nothing will restore this name, so a
        // transactional sink's open transaction is committed now or never.
        SinkDelivery delivery = deliveries.remove(name);
        if (delivery != null) {
            delivery.commitAndRelease();
        }
        // The view goes with the name. A dropped view that keeps answering serves whatever the
        // closed computation last committed, for ever, to a caller with no way to know that nothing
        // maintains it.
        views.remove(name);
        if (query.removeName(name)) {
            byFingerprint.remove(query.fingerprint());
            sharedLaneOf.remove(query.fingerprint());
            query.close();
            // The checkpoints go with the computation. They are a fallback for a query that exists;
            // once nothing holds this one open they are state outliving its owner, and they
            // accumulate for the life of the deployment -- 52 directories for 2 live queries, in a
            // QA run of 50 register/drop cycles.
            // The name the checkpointer was STARTED with, not the one being dropped. For a shared
            // computation those differ, so deleting by the dropped name removed nothing and left the
            // directory orphaned. Both are mine, from the same change.
            query.checkpointDirectory().ifPresent(this::deleteCheckpointDirectory);
        }
        // The journal entry was written before anything was released: a drop the client is told
        // failed must not have destroyed the computation, and a drop that succeeded must survive a
        // restart. Recording it here instead meant neither was guaranteed.
    }

    @Override
    public synchronized void close() {
        List<RegisteredQuery> all = new ArrayList<>(byFingerprint.values());
        deliveries.values().forEach(SinkDelivery::close);
        deliveries.clear();
        byName.clear();
        byFingerprint.clear();
        all.forEach(RegisteredQuery::close);
        // After the queries, not before: a hosted lane's final step is what releases its arena and
        // inbox, and only its runner may take that step. Closing the runner first would leave every
        // lane unable to finish, and each close would time out blaming a stall that never happened.
        // The shared lanes go before the runner and after the queries, for the same reason in both
        // directions: a hosted query's close only removes its pipelines and deliberately leaves the
        // lane running for the queries still on it, so somebody has to close the lane itself -- and
        // it can only finish while its runner is still stepping it.
        sharedLaneOf.clear();
        SharedLanes shared = sharedLanes;
        sharedLanes = null;
        if (shared != null) {
            shared.close();
        }
        com.ash.messaging.pravaha.runtime.lane.LaneRunner runner = laneRunner;
        laneRunner = null;
        if (runner != null) {
            runner.close();
        }
    }

    /**
     * Refuses a name no query will be able to say.
     *
     * <p>A view name is written in a FROM clause, so it has to survive the SQL parser. {@code
     * primary} does not: the registration is accepted, the server reports RUNNING, and every attempt
     * to read it fails with a parse error naming a column position, which reads like a broken query
     * rather than a name that was never usable. Refused at registration, where the person who chose
     * the name is still holding it.
     */
    private static void requireSayableName(String name) {
        // Unicode letters, not ASCII only. My first version refused a name like 金额 that the
        // planner resolves perfectly well -- a validation stricter than the thing it was protecting.
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) {
            throw new PravahaException(
                    RegistryErrors.NAME_UNUSABLE,
                    "'" + name + "' cannot be used as a view name: a name is written in a FROM clause, so it "
                            + "must be a plain identifier -- a letter or underscore, then letters, digits or "
                            + "underscores.");
        }
        try {
            // Calcite's own parser rather than a list of reserved words kept by hand here. The list
            // is long, it is version-specific, and a copy of it is wrong the first time Calcite
            // changes -- whereas the parser is the thing that will actually reject the name.
            org.apache.calcite.sql.parser.SqlParser.create("SELECT 1 FROM " + name)
                    .parseQuery();
        } catch (org.apache.calcite.sql.parser.SqlParseException | RuntimeException e) {
            // What the parser said, not a diagnosis of our own. This used to answer every parse
            // failure with "is a reserved word in SQL", which is the common cause and not the only
            // one: a 500-character name is refused by Calcite's lexer for its length and was told it
            // was a keyword -- false, and it sends the person who chose it looking for a list they
            // will not find themselves on.
            String reason =
                    e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            throw new PravahaException(
                    RegistryErrors.NAME_UNUSABLE,
                    "'" + name + "' cannot appear in a FROM clause, so no query could read the view: " + reason
                            + ". The usual cause is that the name is a reserved word in SQL.");
        }
    }

    private void requireName(String name) {
        // The null check first. I added requireSayableName above it, so a null name threw a bare
        // NullPointerException out of name.matches() instead of the message two lines down.
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a registration needs a name");
        }
        requireSayableName(name);
        if (byName.containsKey(name)) {
            throw new PravahaException(
                    RegistryErrors.NAME_IN_USE,
                    "'" + name + "' is already registered. Drop it first, or register under another name -- "
                            + "silently replacing a running query would take its answers away from whoever "
                            + "is reading them");
        }
    }
}

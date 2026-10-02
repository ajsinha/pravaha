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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.bindings.ingest.PluginLookupSources;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.io.StateOwnership;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.connect.PluginRegistry;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionListener;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.SqlErrors;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery;

/**
 * The standard {@link PravahaEngine}.
 *
 * <p>State transitions go through a compare-and-set so that two threads calling {@code start()}
 * concurrently cannot both proceed. That is not hypothetical: a Spring context and an application's
 * own initialiser both holding a reference is exactly how it happens, and the second start would
 * otherwise open every plugin twice.
 *
 * <p>What it assembles at start is what {@code PravahaNode} assembles in the server, from the same
 * pieces -- {@code QueryRegistry}, {@code PluginSourceFeeds}, {@code PluginLookupSources}, {@code
 * PluginSinks}, {@code RegistryJournal} -- minus everything that makes a server a server: no network
 * listener, no cluster coordinator, no authentication.
 */
final class DefaultPravahaEngine implements PravahaEngine {

    private static final System.Logger LOG = System.getLogger(DefaultPravahaEngine.class.getName());

    /** The principal every embedded call runs as: the host application has already decided who may call. */
    private static final Principal CALLER = Principal.ANONYMOUS;

    private final Configuration configuration;
    private final PluginRegistry plugins = new PluginRegistry();
    private final String instanceId;
    private final AtomicReference<EngineState> state = new AtomicReference<>(EngineState.CREATED);
    private final Duration pushTimeout;

    // Declarations. Guarded by `this`, and frozen once start() has read them.
    private final Map<String, StreamSchema> declaredStreams = new LinkedHashMap<>();
    private final Map<String, SourceBinding> declaredSources = new LinkedHashMap<>();
    private final Map<String, SourceBinding> declaredLookups = new LinkedHashMap<>();
    private final Map<String, SinkBinding> declaredSinks = new LinkedHashMap<>();
    private final Map<String, String> declaredEventTimes = new LinkedHashMap<>();
    private final List<ContinuousQuery> declaredQueries = new ArrayList<>();
    private final Map<String, Duration> declaredEventTimeTracking = new LinkedHashMap<>();

    /**
     * Per stream that {@link #trackEventTime} opted in: its allowed lateness, the greatest event time
     * pushed and the event time last declared, in nanoseconds. Guarded by {@link #pushLock}.
     */
    private final Map<String, long[]> eventTimeTracking = new java.util.HashMap<>();

    // What start() built. Null before it and after stop().
    private volatile QueryRegistry registry;
    private volatile ViewQuery reads;
    private volatile Map<String, RowEncoder> encoders = Map.of();
    private volatile List<StreamSchema> lookupSchemas = List.of();
    private PluginLookupSources lookups;
    private PluginSinks sinks;

    /** The source bindings, kept so the engine can answer for the dead letters they wrote (B5). */
    private PluginSourceFeeds sourceFeeds;

    private final List<StateOwnership> claims = new ArrayList<>();

    private final Object pushLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private RowArena pushArena;

    DefaultPravahaEngine(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.instanceId = configuration.getString("pravaha.node.id", "pravaha-embedded");
        this.pushTimeout = configuration.getDuration("pravaha.embedded.push-timeout", Duration.ofSeconds(30));
        // DOCX-21. Where a failure's help page lives, for a host that publishes one. Read here --
        // at construction, before anything can fail -- so a bad value is refused while the host is
        // still building the engine rather than inside the first error it tries to report. Unset
        // is supported and is the default: no base means no URL, anywhere.
        com.ash.messaging.pravaha.api.HelpUrls.configureOrFromEnvironment(
                configuration.getString(com.ash.messaging.pravaha.api.HelpUrls.KEY, null));
    }

    // ------------------------------------------------------------------ declarations

    @Override
    public synchronized PravahaEngine declareStream(StreamSchema schema) {
        Objects.requireNonNull(schema, "schema");
        requireDeclaring("declare stream '" + schema.name() + "'");
        String key = key(schema.name());
        if (declaredStreams.containsKey(key)) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED,
                    "stream '" + schema.name() + "' is declared twice; a stream has one shape, so the second "
                            + "declaration would silently replace the first");
        }
        declaredStreams.put(key, schema);
        schema.eventTimeOrdinal()
                .ifPresent(ordinal ->
                        declaredEventTimes.put(key, schema.field(ordinal).name()));
        return this;
    }

    @Override
    public PravahaEngine declareStream(String name, String schemaSpec) {
        return declareStream(name, schemaSpec, null);
    }

    @Override
    public PravahaEngine declareStream(String name, String schemaSpec, String eventTimeColumn) {
        return declareStream(parse(name, schemaSpec, eventTimeColumn, null));
    }

    @Override
    public synchronized PravahaEngine trackEventTime(String stream, Duration allowedLateness) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(allowedLateness, "allowedLateness");
        requireDeclaring("track event time on '" + stream + "'");
        if (allowedLateness.isNegative()) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED,
                    "stream '" + stream + "' is given an allowed lateness of " + allowedLateness
                            + "; lateness is how far behind the latest row one may still arrive, so zero or more");
        }
        declaredEventTimeTracking.put(key(stream), allowedLateness);
        return this;
    }

    @Override
    public synchronized PravahaEngine bindSource(String stream, String plugin, Map<String, String> options) {
        requireDeclaring("bind a source to '" + stream + "'");
        declaredSources.put(key(stream), new SourceBinding(stream, plugin, options));
        return this;
    }

    @Override
    public synchronized PravahaEngine bindLookup(String table, String plugin, Map<String, String> options) {
        requireDeclaring("bind lookup table '" + table + "'");
        declaredLookups.put(key(table), new SourceBinding(table, plugin, options));
        return this;
    }

    @Override
    public synchronized PravahaEngine bindSink(String sink, String plugin, Map<String, String> options) {
        requireDeclaring("bind sink '" + sink + "'");
        declaredSinks.put(key(sink), new SinkBinding(sink, plugin, options));
        return this;
    }

    @Override
    public synchronized PravahaEngine declareQuery(ContinuousQuery query) {
        Objects.requireNonNull(query, "query");
        requireDeclaring("declare query '" + query.name() + "'");
        declaredQueries.add(query);
        return this;
    }

    private void requireDeclaring(String what) {
        if (state.get() != EngineState.CREATED) {
            throw new IllegalStateException("cannot " + what + " on an engine that is " + state.get()
                    + ": streams and bindings are fixed at start, because the registry plans every query "
                    + "against the streams it was built with. Declare before start(), or in the "
                    + "configuration under pravaha.streams / pravaha.sources / pravaha.sinks.");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        if (!state.compareAndSet(EngineState.CREATED, EngineState.STARTING)) {
            EngineState current = state.get();
            throw new IllegalStateException("cannot start an engine that is " + current
                    + "; create a new one rather than restarting this instance");
        }
        try {
            for (PluginRegistry.Registration registration : plugins.registrations()) {
                registration.plugin().open();
            }
            synchronized (this) {
                assemble();
            }
            state.set(EngineState.RUNNING);
        } catch (RuntimeException e) {
            // FAILED rather than back to CREATED: whatever failed is still in whatever state it
            // failed in, and restarting over it would hide the cause. What start() did build is
            // released, so a failed engine holds no threads, files or directory claims.
            releaseRuntime();
            plugins.close();
            state.set(EngineState.FAILED);
            throw e;
        }
    }

    /** Builds the registry and everything attached to it, in the order the server does. */
    private void assemble() {
        mergeConfiguredDeclarations();

        StreamSchema[] streams = declaredStreams.values().toArray(new StreamSchema[0]);
        ViewCatalog views = new ViewCatalog();
        QueryRegistry built = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, streams);
        // Who may drop, pause or replace a view: its owner, a grant or an admin. The setting accepts only
        // 'ownership'; the removed 'legacy-read' is refused by name (PRV-7004).
        built.owners()
                .administering(com.ash.messaging.pravaha.security.Administration.Rule.parse(configuration
                        .getString(com.ash.messaging.pravaha.security.Administration.SETTING)
                        .orElse("")));
        // CELLBYTES-1: pravaha.lane.*, read as a server reads them. Nothing here read them, so the inbox
        // cell was 512 bytes whatever was configured, and the refusal of a wider row named a setting
        // that could not be applied.
        built.executingWith(laneConfig(configuration), MemoryAccess.best());
        // FINEHOP-1: the finest window a registration may ask for, read as a server reads it.
        built.limitingWindowsPerRow(com.ash.messaging.pravaha.runtime.window.WindowLimits.parse(configuration
                .getString(com.ash.messaging.pravaha.runtime.window.WindowLimits.SETTING)
                .orElse(null)));
        registry = built;
        reads = new ViewQuery(views);

        // Checkpoints before the feeds, so a query is checkpointed from its first row.
        configuration
                .getString("pravaha.checkpoint.directory")
                .filter(s -> !s.isBlank())
                .ifPresent(dir -> {
                    Path directory = Path.of(dir);
                    claim(directory);
                    built.checkpointingTo(directory, configuration);
                });

        built.generatingWatermarks(
                configuration.getDuration("pravaha.watermark.idle-after", Duration.ofSeconds(30)),
                configuration.getDuration("pravaha.watermark.tick", Duration.ofSeconds(1)));

        PluginSourceFeeds feeds = new PluginSourceFeeds();
        // B5. The bound on each query's dead-letter file, with the byte bound on by default: an
        // embedded engine writes these files into its host's filesystem, so an unbounded one is
        // this library filling somebody else's disk.
        feeds.retainingDeadLetters(new com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention(
                configuration.getLong(
                        "pravaha.dlq.max-bytes",
                        com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention.DEFAULT_MAX_BYTES),
                configuration.getLong("pravaha.dlq.max-entries", 0),
                configuration.getDuration("pravaha.dlq.max-age", Duration.ZERO)));
        configuration
                .getString("pravaha.dlq.directory")
                .filter(s -> !s.isBlank())
                .ifPresent(dir -> feeds.deadLetteringTo(Path.of(dir)));
        this.sourceFeeds = feeds;
        declaredSources.values().forEach(binding -> feeds.bind(withDeclaredEventTime(binding)));
        built.feedingFrom(feeds);

        lookups = new PluginLookupSources();
        declaredLookups.values().forEach(lookups::bind);
        List<LookupSourcePlugin> dimensions = lookups.open();
        dimensions.forEach(built::lookingUp);
        lookupSchemas = dimensions.stream().map(LookupSourcePlugin::schema).toList();

        sinks = new PluginSinks();
        declaredSinks.values().forEach(sinks::bind);
        // Asked now, so a misconfigured sink fails the start rather than the first registration.
        sinks.bindings().keySet().forEach(sinks::capabilitiesOf);
        built.writingTo(sinks);

        Map<String, RowEncoder> byName = new LinkedHashMap<>();
        for (StreamSchema identified : built.streams()) {
            byName.put(key(identified.name()), new RowEncoder(identified));
        }
        encoders = Map.copyOf(byName);
        trackEventTimes(byName);

        // After the feeds and sinks, so a recovered query comes back fed and writing.
        configuration
                .getString("pravaha.registry.journal")
                .filter(s -> !s.isBlank())
                .ifPresent(file -> {
                    Path journal = Path.of(file).toAbsolutePath();
                    if (journal.getParent() != null) {
                        claim(journal.getParent());
                    }
                    built.journalTo(new RegistryJournal(journal));
                    QueryRegistry.Recovery recovery = built.recover(owner -> Optional.of(CALLER));
                    recovery.refused()
                            .forEach(refusal ->
                                    LOG.log(System.Logger.Level.WARNING, "registration not recovered -- " + refusal));
                });

        for (ContinuousQuery query : declaredQueries) {
            if (built.find(query.name()).isPresent()) {
                continue;
            }
            register(built, query);
        }
    }

    /**
     * {@code pravaha.lane.*} as a lane configuration (CELLBYTES-1): the keys, defaults and threads a
     * server's {@code LaneProperties} has, so an engine embedded in an application sizes its lanes from
     * the same settings and the remedy an error names can be applied.
     */
    static com.ash.messaging.pravaha.runtime.lane.LaneConfig laneConfig(Configuration configuration) {
        com.ash.messaging.pravaha.runtime.lane.LaneConfig defaults =
                com.ash.messaging.pravaha.runtime.lane.LaneConfig.defaults();
        try {
            return defaults.withBatchSize(configuration.getInt("pravaha.lane.batch-size", defaults.batchSize()))
                    .withInbox(
                            configuration.getInt("pravaha.lane.inbox.cells", defaults.inboxCells()),
                            configuration.getInt("pravaha.lane.inbox.cell-bytes", defaults.inboxCellBytes()))
                    .withArena(
                            configuration.getInt("pravaha.lane.arena.slab-bytes", defaults.arenaSlabBytes()),
                            configuration.getInt("pravaha.lane.arena.max-slabs", defaults.arenaMaxSlabs()))
                    .withWaitStrategy(configuration.getEnum(
                            "pravaha.lane.wait-strategy",
                            com.ash.messaging.pravaha.common.queue.WaitStrategy.Kind.class,
                            com.ash.messaging.pravaha.common.queue.WaitStrategy.Kind.BACKOFF_PARK))
                    .withThreads("pravaha-query", true);
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED, "a pravaha.lane.* setting cannot be used: " + e.getMessage(), e);
        }
    }

    /** Adds what the configuration declares to what was declared in code; a name in both is refused. */
    private void mergeConfiguredDeclarations() {
        Declarations configured = Declarations.from(configuration);
        configured.streams.forEach((name, spec) -> {
            if (spec.schema() == null || spec.schema().isBlank()) {
                throw new PravahaException(
                        EmbeddedErrors.MISCONFIGURED,
                        "stream '" + name + "' is declared under pravaha.streams with no schema. A stream is a "
                                + "name and a shape; the name alone cannot be planned against.");
            }
            declareConfigured(parse(name, spec.schema(), spec.eventTime(), spec.outOfOrderness()));
        });
        configured.sources.forEach((name, spec) ->
                putOnce(declaredSources, name, new SourceBinding(name, spec.plugin(), spec.options()), "source"));
        configured.lookups.forEach((name, spec) ->
                putOnce(declaredLookups, name, new SourceBinding(name, spec.plugin(), spec.options()), "lookup"));
        configured.sinks.forEach((name, spec) ->
                putOnce(declaredSinks, name, new SinkBinding(name, spec.plugin(), spec.options()), "sink"));
        declaredQueries.addAll(configured.queries);
    }

    private void declareConfigured(StreamSchema schema) {
        String key = key(schema.name());
        if (declaredStreams.containsKey(key)) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED,
                    "stream '" + schema.name() + "' is declared both in code and under pravaha.streams; "
                            + "declare it once");
        }
        declaredStreams.put(key, schema);
        schema.eventTimeOrdinal()
                .ifPresent(ordinal ->
                        declaredEventTimes.put(key, schema.field(ordinal).name()));
    }

    private static <T> void putOnce(Map<String, T> into, String name, T binding, String what) {
        if (into.putIfAbsent(key(name), binding) != null) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED,
                    what + " '" + name + "' is bound both in code and in configuration; bind it once");
        }
    }

    /**
     * Hands the stream's declared event-time column to its source, so it is declared once. The same
     * reasoning as the server's: a plugin decoding with a schema that has no event time stamps every
     * row with zero, and no watermark derived from zero reaches a window in the present.
     */
    private SourceBinding withDeclaredEventTime(SourceBinding binding) {
        String column = declaredEventTimes.get(key(binding.streamName()));
        if (column == null || binding.options().containsKey("event.time")) {
            return binding;
        }
        Map<String, String> options = new LinkedHashMap<>(binding.options());
        options.put("event.time", column);
        return new SourceBinding(binding.streamName(), binding.plugin(), options);
    }

    private void claim(Path directory) {
        boolean allowShared = configuration.getBoolean("pravaha.state.allow-shared", false);
        StateOwnership.claimInto(
                claims,
                directory,
                StateOwnership.Owner.current(instanceId, "embedded", 0),
                StateOwnership.DEFAULT_LEASE,
                allowShared);
    }

    @Override
    public void stop() {
        EngineState current = state.get();
        if (current.isTerminal() || current == EngineState.CREATED) {
            state.compareAndSet(EngineState.CREATED, EngineState.STOPPED);
            return;
        }
        if (!state.compareAndSet(current, EngineState.STOPPING)) {
            return;
        }
        try {
            releaseRuntime();
            plugins.close();
        } finally {
            state.set(EngineState.STOPPED);
        }
    }

    /** Checks each stream {@link #trackEventTime} opted in, and starts it with no event time seen. */
    private void trackEventTimes(Map<String, RowEncoder> byName) {
        synchronized (pushLock) {
            eventTimeTracking.clear();
            for (Map.Entry<String, Duration> each : declaredEventTimeTracking.entrySet()) {
                RowEncoder encoder = byName.get(each.getKey());
                if (encoder == null || !encoder.stampsEventTime()) {
                    throw new PravahaException(
                            EmbeddedErrors.MISCONFIGURED,
                            "event time is tracked on stream '" + each.getKey() + "', which "
                                    + (encoder == null
                                            ? "is not declared"
                                            : "has no event-time column of TIMESTAMP or BIGINT nanoseconds")
                                    + "; declare it with one -- declareStream(name, spec, eventTimeColumn) -- "
                                    + "or advance its event time yourself");
                }
                long lateness = each.getValue().toNanos();
                eventTimeTracking.put(each.getKey(), new long[] {lateness, Long.MIN_VALUE, Long.MIN_VALUE});
            }
        }
    }

    /**
     * After a push, moves a tracked stream's event time to the greatest event time pushed less its
     * allowed lateness -- forward only, and only when that moved. Called under {@link #pushLock}.
     */
    private void followEventTime(RowEncoder encoder, List<Object[]> pushed) {
        long[] tracking = eventTimeTracking.get(key(encoder.schema().name()));
        if (tracking == null) {
            return;
        }
        for (Object[] values : pushed) {
            tracking[1] = Math.max(tracking[1], encoder.eventTimeOf(values));
        }
        if (tracking[1] == Long.MIN_VALUE) {
            return;
        }
        // Saturating: a lateness wider than the time since the epoch holds event time at its floor.
        long watermark = tracking[1] - tracking[0] > tracking[1] ? Long.MIN_VALUE : tracking[1] - tracking[0];
        if (watermark > tracking[2]) {
            tracking[2] = watermark;
            advanceWatermark(encoder, watermark);
        }
    }

    /** Reverse of assembly: the queries, then what they read and wrote, then the claims. */
    private void releaseRuntime() {
        QueryRegistry current = registry;
        registry = null;
        reads = null;
        encoders = Map.of();
        closeQuietly("registry", current);
        closeQuietly("lookup tables", lookups);
        lookups = null;
        closeQuietly("sinks", sinks);
        sinks = null;
        synchronized (pushLock) {
            closeQuietly("push arena", pushArena);
            pushArena = null;
        }
        claims.forEach(claim -> closeQuietly("state claim on " + claim.directory(), claim));
        claims.clear();
    }

    private static void closeQuietly(String what, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception failure) {
            // One component refusing to shut down must not stop the others from trying.
            LOG.log(System.Logger.Level.WARNING, what + " did not shut down cleanly: " + failure);
        }
    }

    @Override
    public EngineState state() {
        return state.get();
    }

    @Override
    public Configuration configuration() {
        return configuration;
    }

    @Override
    public PluginRegistry plugins() {
        return plugins;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ running

    @Override
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters() {
        PluginSourceFeeds open = sourceFeeds;
        return open == null ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE : open.deadLetters();
    }

    @Override
    public QueryRegistry registry() {
        QueryRegistry current = registry;
        if (current == null || state.get() != EngineState.RUNNING) {
            throw new IllegalStateException("engine " + instanceId + " is " + state.get()
                    + "; queries can be registered, read and fed only while it is RUNNING");
        }
        return current;
    }

    @Override
    public List<StreamSchema> streams() {
        return List.of(registry().streams());
    }

    @Override
    public RegisteredQuery register(ContinuousQuery query) {
        return register(registry(), query);
    }

    @Override
    public RegisteredQuery register(String name, String sql, String... keyColumns) {
        return register(ContinuousQuery.named(name).sql(sql).keyedBy(keyColumns).build());
    }

    private RegisteredQuery register(QueryRegistry target, ContinuousQuery query) {
        List<Integer> keys = keyOrdinals(target, query);
        if (query.sink().isPresent()) {
            return target.registerWritingTo(
                    query.name(), query.sql(), keys, CALLER, query.sink().get());
        }
        return query.retention().isPresent()
                ? target.register(
                        query.name(),
                        query.sql(),
                        keys,
                        CALLER,
                        query.retention().get())
                : target.register(query.name(), query.sql(), keys, CALLER);
    }

    /**
     * The key columns as the registry wants them, by output ordinal. Resolved by planning the query
     * over the same streams and lookup tables the registry will plan it over, so a name that would
     * not resolve there is refused here with the columns it could have been.
     */
    private List<Integer> keyOrdinals(QueryRegistry target, ContinuousQuery query) {
        StreamSchema output = PreparedContinuousQuery.of(
                        query.sql(), BoundParameters.none(), List.of(target.streams()), lookupSchemas)
                .plan()
                .outputSchema();
        List<Integer> ordinals = new ArrayList<>();
        for (String column : query.keyColumns()) {
            int ordinal = indexOfIgnoringCase(output, column);
            if (ordinal < 0) {
                throw new IllegalArgumentException("query '" + query.name() + "' is keyed by '" + column
                        + "', which it does not produce; its columns are "
                        + output.fields().stream().map(Field::name).toList());
            }
            ordinals.add(ordinal);
        }
        return ordinals;
    }

    private static int indexOfIgnoringCase(StreamSchema schema, String column) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equalsIgnoreCase(column.strip())) {
                return ordinal;
            }
        }
        return -1;
    }

    @Override
    public ViewQuery.Result query(String sql, Object... parameters) {
        QueryRegistry target = registry();
        // CREATE / DROP / PAUSE / RESUME CONTINUOUS QUERY and SHOW CONTINUOUS QUERIES arrive here as
        // SQL too, and run as the same anonymous caller under the same permissive policy as the
        // engine's own register and drop -- the host application has already decided who may call.
        Optional<ContinuousStatement> statement = ContinuousStatements.recognize(sql);
        if (statement.isPresent()) {
            if (parameters != null && parameters.length > 0) {
                throw new PravahaException(
                        SqlErrors.STATEMENT_MALFORMED,
                        statement.get().verb() + " takes no parameters; write the values into the statement.");
            }
            return new ContinuousQueryStatements(target, target.policy(), AuditSink.NONE)
                    .execute(statement.get(), CALLER);
        }
        ViewQuery current = reads;
        if (parameters == null || parameters.length == 0) {
            return current.execute(sql, CALLER);
        }
        return current.execute(current.prepare(sql, CALLER), BoundParameters.of(parameters), CALLER);
    }

    @Override
    public <R> List<R> query(Class<R> rowType, String sql, Object... parameters) {
        ViewQuery.Result result = query(sql, parameters);
        Function<Object[], R> reader = RowMapping.reader(rowType, result.schema());
        return result.rows().stream().map(reader).toList();
    }

    @Override
    public Subscription subscribe(String queryName, Consumer<List<RowChange>> consumer) {
        return subscribe(queryName, SubscriptionOptions.DEFAULT, consumer);
    }

    @Override
    public Subscription subscribe(String queryName, SubscriptionOptions options, Consumer<List<RowChange>> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        RegisteredQuery query = registry().require(queryName);
        StreamSchema schema = query.outputSchema();
        return query.subscribe(
                options,
                changes -> consumer.accept(changes.stream()
                        .map(change -> new RowChange(schema, change))
                        .toList()));
    }

    @Override
    public Subscription subscribeFromSnapshot(String queryName, RowChangeListener listener) {
        return subscribeFromSnapshot(queryName, SubscriptionOptions.DEFAULT, listener);
    }

    @Override
    public Subscription subscribeFromSnapshot(
            String queryName, SubscriptionOptions options, RowChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        RegisteredQuery query = registry().require(queryName);
        StreamSchema schema = query.outputSchema();
        return query.subscribeFromSnapshot(
                options, com.ash.messaging.pravaha.registry.SubscriptionFilter.none(), new SubscriptionListener() {
                    @Override
                    public void onSnapshot(List<ViewChange> rows, long frontier) {
                        listener.onSnapshot(rowChanges(schema, rows), frontier);
                    }

                    @Override
                    public void onCommit(List<ViewChange> changes, long frontier) {
                        listener.onCommit(rowChanges(schema, changes), frontier);
                    }
                });
    }

    private static List<RowChange> rowChanges(StreamSchema schema, List<ViewChange> changes) {
        return changes.stream().map(change -> new RowChange(schema, change)).toList();
    }

    @Override
    public int push(String stream, Object[]... rows) {
        return push(stream, rows == null ? List.of() : Arrays.asList(rows));
    }

    @Override
    public int push(String stream, Map<String, ?> row) {
        RowEncoder encoder = encoderFor(stream);
        List<Object[]> one = new ArrayList<>(1);
        one.add(encoder.fromMap(row));
        return push(stream, one);
    }

    @Override
    public int push(String stream, List<Object[]> rows) {
        return deliver(stream, rows, 1L);
    }

    @Override
    public int retract(String stream, Object[]... rows) {
        return deliver(stream, rows == null ? List.of() : Arrays.asList(rows), -1L);
    }

    /** Validates every row, then hands each to every running query on the stream at {@code weight}. */
    private int deliver(String stream, List<Object[]> rows, long weight) {
        RowEncoder encoder = encoderFor(stream);
        // Every row checked before any is delivered: a batch with one bad row delivers nothing.
        List<Object[]> validated = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            validated.add(encoder.validate(row));
        }
        synchronized (pushLock) {
            List<Target> targets = targetsFor(encoder.schema().name());
            if (targets.isEmpty() || validated.isEmpty()) {
                if (weight > 0) {
                    followEventTime(encoder, validated);
                }
                return targets.size();
            }
            // A pushed row's sequence is its position, and a view's frontier is the furthest position
            // it has published. A view restored from a checkpoint comes back at the frontier it was
            // saved at, so numbering from where this process happened to start would commit it
            // backwards (found by the restart test: "frontier went backwards: 1 after 2").
            // CELLBYTES-1: every row against every query's inbox cell before any row is delivered, as
            // validation is: a row no query on the stream can take refuses the push, by row and size,
            // and every query keeps running. It used to stop each query on the stream for good.
            for (int index = 0; index < validated.size(); index++) {
                int size = encoder.sizeOf(validated.get(index));
                for (Target target : targets) {
                    int cell = target.query().maxRowBytes();
                    if (size > cell) {
                        throw new PravahaException(
                                EmbeddedErrors.ROW_REJECTED,
                                "row " + (index + 1) + " of this push to '" + stream + "' is " + size + " bytes, and "
                                        + "query '" + target.query().name() + "' takes rows of at most " + cell
                                        + " bytes (pravaha.lane.inbox.cell-bytes). Nothing in this push was delivered, "
                                        + "and every query on the stream keeps running. Raise "
                                        + "pravaha.lane.inbox.cell-bytes to the widest row the stream carries, or push a "
                                        + "narrower one.");
                    }
                }
            }
            for (Target target : targets) {
                long reached = Math.max(
                        target.query().view().committedFrontier(),
                        target.query().view().appliedFrontier());
                sequence.accumulateAndGet(reached, Math::max);
            }
            // PUSHPARTIAL-1: each query takes the push independently. One that cannot apply it --
            // its lane failed, its inbox stayed full -- no longer stops the others being committed:
            // they used to be applied and left unpublished until some later push committed them, and
            // a caller retrying the push it was told failed then counted it twice.
            Map<Target, RuntimeException> failed = new LinkedHashMap<>();
            for (Object[] values : validated) {
                BinaryRowView row = write(encoder, values, weight);
                for (Target target : targets) {
                    if (!failed.containsKey(target)) {
                        try {
                            offer(target, row);
                        } catch (RuntimeException cannot) {
                            failed.put(target, cannot);
                        }
                    }
                }
            }
            // Applied and published before returning, so the caller's next read sees what it pushed.
            List<String> committed = new ArrayList<>(targets.size());
            for (Target target : targets) {
                if (!failed.containsKey(target)) {
                    try {
                        applyAndCommit(target.query(), stream);
                        committed.add(target.query().name());
                    } catch (RuntimeException cannot) {
                        failed.put(target, cannot);
                    }
                }
            }
            // After the rows are applied, so they are placed in their windows before any closes.
            if (weight > 0) {
                followEventTime(encoder, validated);
            }
            if (!failed.isEmpty()) {
                throw PushOutcome.failure(stream, committed, failed);
            }
            return targets.size();
        }
    }

    /** Waits for the lane to apply what it was handed, then publishes it. */
    private void applyAndCommit(RegisteredQuery query, String stream) {
        if (!query.awaitApplied(pushTimeout)) {
            throw new PravahaException(
                    EmbeddedErrors.BACKPRESSURE,
                    "query '" + query.name() + "' did not apply what was pushed to '" + stream + "' within "
                            + pushTimeout + " (pravaha.embedded.push-timeout)");
        }
        query.commit();
    }

    /** One computation a pushed row must reach, and its own spelling of the stream. */
    record Target(RegisteredQuery query, String streamName) {}

    private List<Target> targetsFor(String stream) {
        List<Target> targets = new ArrayList<>();
        for (RegisteredQuery query : registry().queries()) {
            if (query.state() != QueryState.RUNNING) {
                continue;
            }
            for (String source : query.sourceStreams()) {
                if (source.equalsIgnoreCase(stream)) {
                    targets.add(new Target(query, source));
                    break;
                }
            }
        }
        return targets;
    }

    /** Writes one row into the push arena; the lane copies it on offer, so the arena is reused per row. */
    private BinaryRowView write(RowEncoder encoder, Object[] values, long weight) {
        int size = encoder.sizeOf(values);
        if (pushArena == null) {
            pushArena = new RowArena(MemoryAccess.best(), Math.max(RowArena.DEFAULT_SLAB_BYTES, size), 1);
        }
        pushArena.reset();
        BinaryRowView row = encoder.write(values, pushArena, sequence.incrementAndGet(), weight);
        if (row == null) {
            // Larger than the slab: a one-off arena of its own size, kept for the next large row.
            pushArena.close();
            pushArena = new RowArena(MemoryAccess.best(), size, 1);
            row = encoder.write(values, pushArena, sequence.get(), weight);
        }
        return row;
    }

    private void offer(Target target, BinaryRowView row) {
        long deadline = System.nanoTime() + pushTimeout.toNanos();
        while (!target.query().accept(target.streamName(), row)) {
            if (target.query().state() != QueryState.RUNNING) {
                // Paused or dropped while this push was in flight: it drops the row, as a paused
                // query does, rather than failing everyone else's delivery.
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new PravahaException(
                        EmbeddedErrors.BACKPRESSURE,
                        "query '" + target.query().name() + "' kept its inbox full for " + pushTimeout
                                + " (pravaha.embedded.push-timeout); push smaller batches or give it time");
            }
            // The inbox is full; let the lane drain rather than spin.
            target.query().awaitApplied(Duration.ofMillis(50));
        }
    }

    private RowEncoder encoderFor(String stream) {
        registry();
        RowEncoder encoder = encoders.get(key(stream));
        if (encoder == null) {
            throw new PravahaException(
                    EmbeddedErrors.UNKNOWN_STREAM,
                    "no stream named '" + stream + "' is declared on engine " + instanceId + "; it knows "
                            + encoders.values().stream()
                                    .map(e -> e.schema().name())
                                    .toList()
                            + ". Streams are declared before start().");
        }
        return encoder;
    }

    @Override
    public void advanceEventTime(String stream, Instant watermark) {
        Objects.requireNonNull(watermark, "watermark");
        RowEncoder encoder = encoderFor(stream);
        long nanos = Math.addExact(Math.multiplyExact(watermark.getEpochSecond(), 1_000_000_000L), watermark.getNano());
        synchronized (pushLock) {
            long[] tracking = eventTimeTracking.get(key(encoder.schema().name()));
            if (tracking != null) {
                tracking[2] = Math.max(tracking[2], nanos);
            }
            advanceWatermark(encoder, nanos);
        }
    }

    /** Declares event time {@code nanos} to every running query on the stream. Under {@link #pushLock}. */
    private void advanceWatermark(RowEncoder encoder, long nanos) {
        for (Target target : targetsFor(encoder.schema().name())) {
            target.query().awaitApplied(pushTimeout);
            target.query().advanceWatermark(nanos);
        }
    }

    @Override
    public Optional<RegisteredQuery> find(String name) {
        return registry().find(name);
    }

    @Override
    public Set<String> queries() {
        return registry().names();
    }

    @Override
    public void pause(String name) {
        registry().pause(name);
    }

    @Override
    public void resume(String name) {
        registry().resume(name);
    }

    @Override
    public void drop(String name) {
        registry().drop(name);
    }

    // ------------------------------------------------------------------ helpers

    private static StreamSchema parse(String name, String spec, String eventTime, Duration outOfOrderness) {
        if (spec == null || spec.isBlank()) {
            throw new PravahaException(
                    EmbeddedErrors.MISCONFIGURED, "stream '" + name + "' has no schema; write it as name:TYPE,...");
        }
        StreamSchema parsed = FilesystemSourcePlugin.parseSchema(name, spec);
        if ((eventTime == null || eventTime.isBlank()) && outOfOrderness == null) {
            return parsed;
        }
        StreamSchema.Builder builder = StreamSchema.builder(name);
        parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
        if (eventTime != null && !eventTime.isBlank()) {
            String column = eventTime.strip();
            if (!parsed.hasField(column)) {
                throw new PravahaException(
                        EmbeddedErrors.MISCONFIGURED,
                        "stream '" + name + "' declares '" + column + "' as its event time and has no such column; "
                                + "its columns are "
                                + parsed.fields().stream().map(Field::name).toList());
            }
            builder.eventTime(column);
        }
        if (outOfOrderness != null) {
            builder.outOfOrderness(outOfOrderness);
        }
        return builder.build();
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "PravahaEngine[" + instanceId + ", " + state.get() + ", " + plugins.size() + " plugins]";
    }
}

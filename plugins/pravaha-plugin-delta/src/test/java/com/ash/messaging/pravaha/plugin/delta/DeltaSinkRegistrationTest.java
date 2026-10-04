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
package com.ash.messaging.pravaha.plugin.delta;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.SinkFactory;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A continuous query's revising answer, maintained in a Delta table by the engine's own delivery.
 *
 * <p>Nothing here calls the sink directly. A {@link QueryRegistry} is given a {@link SinkFactory}
 * over {@link DeltaSinkPlugin}, a query is registered against it, rows are pushed in, and
 * checkpoints are taken by hand -- so what the table holds is what the engine's delivery, checkpoint
 * cut and restore made of it, read back through Delta Kernel.
 */
class DeltaSinkRegistrationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    /**
     * A global aggregate: the one revising query the engine runs over an unbounded stream without a
     * window (a GROUP BY needs one, PRV-2050). Every change retracts the previous answer and writes
     * the new one, so a table that is not told about the retraction ends up with one row per answer
     * the query ever gave.
     */
    private static final String SPEND = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private static final String LATEST_SQL = "SELECT user_id, amount FROM txn";

    @TempDir
    Path root;

    private RowArena arena;
    private String spendTable;
    private String latestTable;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        spendTable = root.resolve("spend").toString();
        latestTable = root.resolve("latest").toString();
    }

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    @Test
    void aRevisingAggregateIsMaintainedInTheTableAndEachCheckpointAppearsWhole() throws Exception {
        QueryRegistry registry = checkpointed(new DeltaSinks().bindSpend("spend_table", Map.of()));
        RegisteredQuery query = registry.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_table");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        commitUntil(query, 2L);
        assertThat(DeltaTableReader.read(spendTable))
                .as("staged, and no Delta commit until the checkpoint recording it is durable")
                .isEmpty();

        checkpointerOf(query).checkpointNow();
        assertThat(DeltaTableReader.read(spendTable)).containsExactly("2|350");

        feed(query, "u1", 75L);
        commitUntil(query, 3L);
        assertThat(DeltaTableReader.read(spendTable)).containsExactly("2|350");
        checkpointerOf(query).checkpointNow();

        assertThat(DeltaTableReader.read(spendTable))
                .as("the old answer was retracted -- its row rewritten away -- and the revision written")
                .containsExactly("3|425");
        assertThat(registry.sinkGuarantee("spend_so_far"))
                .hasValueSatisfying(text -> assertThat(text).startsWith("exactly-once"));
    }

    /** The registered query end to end: whatever the view holds, the Delta table holds. */
    @Test
    void theDeltaTableEqualsTheQuerysView() throws Exception {
        QueryRegistry registry = checkpointed(new DeltaSinks().bindLatest("latest_table", Map.of()));
        RegisteredQuery query =
                registry.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_table");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        feed(query, "u3", 7L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        assertThat(DeltaTableReader.read(latestTable)).isEqualTo(viewOf(query));

        // Two revisions and a key that is only ever inserted.
        feed(query, "u1", 375L);
        feed(query, "u2", 51L);
        feed(query, "u4", 1L);
        query.commit();
        checkpointerOf(query).checkpointNow();

        assertThat(DeltaTableReader.read(latestTable))
                .isEqualTo(viewOf(query))
                .containsExactly("u1|375", "u2|51", "u3|7", "u4|1");
    }

    /**
     * The crash exactly-once exists for, end to end: the checkpoint is durable and the process dies
     * before its commit reaches the table. The restart restores the checkpoint, commits the handle
     * it recorded, discards what was staged after it, and the replay writes that again -- once.
     */
    @Test
    void aCrashAfterTheCheckpointBeforeTheCommitLosesNothingAndRepeatsNothing() throws Exception {
        DeltaSinks before = new DeltaSinks().bindLatest("latest_table", Map.of());
        QueryRegistry first = checkpointed(before);
        RegisteredQuery query = first.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_table");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        query.commit();
        before.dieInsteadOfCommitting();
        checkpointerOf(query).checkpointNow();
        assertThat(DeltaTableReader.read(latestTable))
                .as("the process died before the commit arrived")
                .isEmpty();

        // Work after the checkpoint, into a sink that is already dead.
        feed(query, "u1", 375L);
        query.commit();
        first.close();
        registries.remove(first);

        QueryRegistry second = checkpointed(new DeltaSinks().bindLatest("latest_table", Map.of()));
        RegisteredQuery restarted =
                second.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_table");
        assertThat(DeltaTableReader.read(latestTable))
                .as("the restore committed what the checkpoint recorded, and nothing after it")
                .containsExactly("u1|300", "u2|50");

        // The source rewinds to the checkpoint's offsets and delivers what came after it.
        feed(restarted, "u1", 375L);
        restarted.commit();
        checkpointerOf(restarted).checkpointNow();

        assertThat(DeltaTableReader.read(latestTable)).containsExactly("u1|375", "u2|50");
        assertThat(DeltaTableReader.read(latestTable)).isEqualTo(viewOf(restarted));
    }

    @Test
    void withoutCheckpointsEachViewCommitIsItsOwnTransaction() throws Exception {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(new DeltaSinks().bindSpend("spend_table", Map.of()))
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
        registries.add(registry);
        RegisteredQuery query = registry.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_table");

        feed(query, "u1", 300L);
        commitUntilTable(query, "1|300");
        feed(query, "u1", 1L);
        commitUntilTable(query, "2|301");

        assertThat(DeltaTableReader.read(spendTable)).containsExactly("2|301");
        // Not exactly once without checkpoints -- each commit is its own transaction and a restart
        // repeats it -- but the default mode upserts, and a repeated upsert rewrites the values
        // already there: effectively once (HLP-4).
        assertThat(registry.sinkGuarantee("spend_so_far"))
                .hasValueSatisfying(
                        text -> assertThat(text).startsWith("effectively-once").contains("takes no checkpoints"));
    }

    @Test
    void theRegistryRefusesAQueryWhoseShapeOrKeyDiffersFromTheSinksDeclaration() {
        QueryRegistry registry = checkpointed(new DeltaSinks()
                .bindSpend("spend_table", Map.of())
                .bindSpend("by_total", Map.of("key.columns", "total")));

        assertThatThrownBy(() -> registry.registerWritingTo(
                        "wrong_shape", "SELECT user_id, amount FROM txn", List.of(0), DANA, "spend_table"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is configured for rows");
        assertThatThrownBy(() -> registry.registerWritingTo("wrong_key", SPEND, List.of(0), DANA, "by_total"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("keys its records by [total]");
    }

    // ---------------------------------------------------------------------------------------

    private QueryRegistry checkpointed(SinkFactory sinks) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(sinks)
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))
                .checkpointingTo(
                        root.resolve("checkpoints"),
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    /** The view rendered the way {@link DeltaTableReader} renders the table, so the two compare. */
    private static List<String> viewOf(RegisteredQuery query) {
        List<String> rows = new ArrayList<>();
        for (Object[] row : query.view().scan()) {
            StringBuilder text = new StringBuilder();
            for (Object value : row) {
                if (text.length() > 0) {
                    text.append('|');
                }
                text.append(value);
            }
            rows.add(text.toString());
        }
        java.util.Collections.sort(rows);
        return rows;
    }

    private void feed(RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    /** Commits until the view's answer has counted {@code rows} rows: the aggregate emits on a tick. */
    private static void commitUntil(RegisteredQuery query, long rows) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            query.commit();
            if (query.view().scan().stream().anyMatch(row -> row[0] instanceof Number n && n.longValue() == rows)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the view never counted " + rows + " rows");
    }

    private void commitUntilTable(RegisteredQuery query, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline
                && !DeltaTableReader.read(spendTable).contains(expected)) {
            query.commit();
            Thread.sleep(20);
        }
        assertThat(DeltaTableReader.read(spendTable)).contains(expected);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Binds sink names to Delta sinks, as the server's binding would. */
    private final class DeltaSinks implements SinkFactory {
        private final Map<String, Map<String, String>> bindings = new HashMap<>();
        private volatile boolean dieInsteadOfCommitting;

        DeltaSinks bindSpend(String name, Map<String, String> extra) {
            return bind(name, spendTable, "n:INT64,total:INT64", "n", extra);
        }

        DeltaSinks bindLatest(String name, Map<String, String> extra) {
            return bind(name, latestTable, "user_id:STRING,amount:INT64", "user_id", extra);
        }

        private DeltaSinks bind(String name, String path, String schema, String keys, Map<String, String> extra) {
            Map<String, String> config = new HashMap<>(Map.of("path", path, "schema", schema, "key.columns", keys));
            config.putAll(extra);
            bindings.put(name, config);
            return this;
        }

        void dieInsteadOfCommitting() {
            dieInsteadOfCommitting = true;
        }

        private DeltaSinkPlugin configured(String name) {
            Map<String, String> config = bindings.get(name);
            if (config == null) {
                throw new IllegalArgumentException("no sink named '" + name + "' is bound");
            }
            DeltaSinkPlugin plugin = new DeltaSinkPlugin();
            plugin.configure(new Ctx(name, config));
            return plugin;
        }

        @Override
        public SinkCapabilities capabilitiesOf(@Nullable String sinkName) {
            return configured(Objects.requireNonNull(sinkName, "a sink name")).capabilities();
        }

        @Override
        public Description describe(String sinkName) {
            DeltaSinkPlugin plugin = configured(sinkName);
            return new Description(plugin.capabilities(), plugin.schema(), plugin.keyColumns());
        }

        @Override
        public StreamSinkPlugin open(@Nullable String sinkName) {
            DeltaSinkPlugin plugin = configured(Objects.requireNonNull(sinkName, "a sink name"));
            plugin.open();
            return new Dying(plugin);
        }

        /**
         * The sink as one process holds it: once told to, the process dies at the commit, and
         * nothing it would have done afterwards happens.
         */
        private final class Dying implements StreamSinkPlugin {
            private final DeltaSinkPlugin delegate;
            private boolean dead;

            Dying(DeltaSinkPlugin delegate) {
                this.delegate = delegate;
            }

            @Override
            public SinkCapabilities capabilities() {
                return delegate.capabilities();
            }

            @Override
            public Optional<StreamSchema> schema() {
                return delegate.schema();
            }

            @Override
            public List<String> keyColumns() {
                return delegate.keyColumns();
            }

            @Override
            public int write(List<RowView> batch) {
                return dead ? batch.size() : delegate.write(batch);
            }

            @Override
            public void flush() {
                if (!dead) {
                    delegate.flush();
                }
            }

            @Override
            public void beginTransaction(long checkpointId) {
                if (!dead) {
                    delegate.beginTransaction(checkpointId);
                }
            }

            @Override
            public String prepare(long checkpointId) {
                return dead ? "" : delegate.prepare(checkpointId);
            }

            @Override
            public void commit(String handle) {
                if (dieInsteadOfCommitting) {
                    dead = true;
                    delegate.close();
                }
                if (!dead) {
                    delegate.commit(handle);
                }
            }

            @Override
            public void abort(String handle) {
                if (!dead) {
                    delegate.abort(handle);
                }
            }

            @Override
            public void abortAfter(long checkpointId) {
                if (!dead) {
                    delegate.abortAfter(checkpointId);
                }
            }

            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public Version version() {
                return delegate.version();
            }

            @Override
            public void configure(PluginContext context) {}

            @Override
            public void open() {}

            @Override
            public HealthStatus health() {
                return delegate.health();
            }

            @Override
            public void close() {
                delegate.close();
            }
        }
    }
}

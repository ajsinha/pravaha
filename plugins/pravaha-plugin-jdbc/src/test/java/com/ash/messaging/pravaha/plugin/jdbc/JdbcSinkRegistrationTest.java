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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
 * A continuous query's revising answer, maintained in an H2 table by the engine's own delivery.
 *
 * <p>Nothing here calls the sink directly. A {@link QueryRegistry} is given a {@link SinkFactory}
 * over {@link JdbcSinkPlugin}, a revising aggregate is registered against it, rows are pushed in,
 * and checkpoints are taken by hand -- so what the table holds is what the engine's delivery,
 * checkpoint cut and restore made of it, and the assertions read the table with plain SQL.
 */
class JdbcSinkRegistrationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    /**
     * A global aggregate: the one revising query the engine runs over an unbounded stream without a
     * window (a GROUP BY needs one, PRV-2050). Every change retracts the previous answer and writes
     * the new one, keyed here by the count -- so a table that is not told about the retraction ends
     * up with one row per answer the query ever gave.
     */
    private static final String SPEND = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private static final String LATEST_SQL = "SELECT user_id, amount FROM txn";

    private static final Map<String, String> LATEST =
            Map.of("table", "latest", "schema", "user_id:STRING,amount:INT64", "key.columns", "user_id");

    private static final AtomicInteger DATABASE = new AtomicInteger();

    @TempDir
    Path root;

    private RowArena arena;
    private String url;
    private Connection admin;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        url = "jdbc:h2:mem:registered" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        admin = DriverManager.getConnection(url);
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE spend (n BIGINT NOT NULL PRIMARY KEY, total BIGINT)");
            statement.execute("CREATE TABLE latest (user_id VARCHAR(32) NOT NULL PRIMARY KEY, amount BIGINT)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        registries.forEach(QueryRegistry::close);
        admin.close();
        arena.close();
    }

    @Test
    void aRevisingAggregateIsMaintainedInTheTableAndEachCheckpointAppearsWhole() throws Exception {
        JdbcSinks sinks = new JdbcSinks().bind("spend_table", Map.of());
        QueryRegistry registry = checkpointed(sinks);
        RegisteredQuery query = registry.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_table");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        commitUntil(query, 2L);
        assertThat(table())
                .as("staged, not visible, until the checkpoint recording it is durable")
                .isEmpty();

        checkpointerOf(query).checkpointNow();
        assertThat(table()).containsExactly("2|350");

        feed(query, "u1", 75L);
        commitUntil(query, 3L);
        assertThat(table()).containsExactly("2|350");
        checkpointerOf(query).checkpointNow();

        assertThat(table())
                .as("the old answer was retracted -- its record deleted -- and the revision written; never both")
                .containsExactly("3|425");
        assertThat(registry.sinkGuarantee("spend_so_far"))
                .hasValueSatisfying(text -> assertThat(text).startsWith("exactly-once"));
    }

    /**
     * The crash exactly-once exists for, end to end: the checkpoint is durable and the process dies
     * before its commit reaches the database. The restart restores the checkpoint, commits the
     * handle it recorded, discards what was staged after it, and the replay writes that again.
     *
     * <p>A keyed projection rather than the aggregate: what is under test is the sink's part of a
     * restore, and a projection's output after a restart depends on nothing but the rows replayed.
     */
    @Test
    void aCrashAfterTheCheckpointBeforeTheCommitLosesNothingAndRepeatsNothing() throws Exception {
        JdbcSinks before = new JdbcSinks().bind("latest_table", LATEST);
        QueryRegistry first = checkpointed(before);
        RegisteredQuery query = first.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_table");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        query.commit();
        before.dieInsteadOfCommitting();
        checkpointerOf(query).checkpointNow();
        assertThat(latest()).as("the process died before the commit arrived").isEmpty();

        // Work after the checkpoint, into a connection that is already dead.
        feed(query, "u1", 375L);
        query.commit();
        first.close();
        registries.remove(first);

        QueryRegistry second = checkpointed(new JdbcSinks().bind("latest_table", LATEST));
        RegisteredQuery restarted =
                second.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_table");
        assertThat(latest())
                .as("the restore committed what the checkpoint recorded, and nothing after it")
                .containsExactly("u1|300", "u2|50");

        // The source rewinds to the checkpoint's offsets and delivers what came after it.
        feed(restarted, "u1", 375L);
        restarted.commit();
        checkpointerOf(restarted).checkpointNow();

        assertThat(latest()).containsExactly("u1|375", "u2|50");
        assertThat(staged()).isZero();
    }

    /**
     * The aggregate across a restart: the table holds one answer, the one that counts every row
     * (CKPT-2).
     *
     * <p>The aggregate's accumulators were in no checkpoint. The view and the table came back
     * holding {@code 2|350}, the aggregate came back at zero, and the next row wrote {@code 1|75}
     * beside it -- with nothing retracted, because the aggregate had not itself published the
     * answer the restore put back.
     */
    @Test
    void aRestartedAggregateRevisesTheAnswerTheTableHoldsRatherThanWritingOneBesideIt() throws Exception {
        QueryRegistry first = checkpointed(new JdbcSinks().bind("spend_table", Map.of()));
        RegisteredQuery query = first.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_table");
        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        commitUntil(query, 2L);
        checkpointerOf(query).checkpointNow();
        assertThat(table()).containsExactly("2|350");
        first.close();
        registries.remove(first);

        QueryRegistry second = checkpointed(new JdbcSinks().bind("spend_table", Map.of()));
        RegisteredQuery restarted = second.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_table");
        feed(restarted, "u3", 75L);
        commitUntil(restarted, 3L);
        checkpointerOf(restarted).checkpointNow();

        assertThat(restarted.view().scan())
                .as("the view: one answer, counting all three rows")
                .singleElement()
                .satisfies(row -> assertThat(row).containsExactly(3L, 425L));
        assertThat(table())
                .as("the table: 2|350 retracted and 3|425 written; 1|75 had the aggregate restarted from zero")
                .containsExactly("3|425");
    }

    /**
     * SINKKEYROWS-1: a keyed view holding two rows of a key shows the newer (VIEWW-1); retracting it
     * shows the older again. The changelog of that commit is the retraction alone, and the table
     * deleted the key. Fed the answer, the table holds the row the view shows.
     */
    @Test
    void retractingTheShownRowOfAKeyWithTwoRowsLeavesTheTableHoldingTheRowBehindIt() throws Exception {
        QueryRegistry registry = checkpointed(new JdbcSinks().bind("latest_table", LATEST));
        RegisteredQuery query =
                registry.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_table");

        feed(query, "u1", 10L, 1L);
        query.commit();
        feed(query, "u1", 20L, 1L);
        feed(query, "u2", 5L, 1L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        assertThat(latest()).containsExactly("u1|20", "u2|5");

        feed(query, "u1", 20L, -1L);
        query.commit();
        checkpointerOf(query).checkpointNow();

        assertThat(query.view().scan())
                .as("the view went back to the row behind the one retracted")
                .anySatisfy(row -> assertThat(row).containsExactly("u1", 10L));
        assertThat(latest()).as("the table holds what the view shows").containsExactly("u1|10", "u2|5");

        feed(query, "u1", 10L, -1L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        assertThat(latest()).containsExactly("u2|5");
    }

    @Test
    void withoutCheckpointsEachViewCommitIsItsOwnTransaction() throws Exception {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(new JdbcSinks().bind("spend_table", Map.of()))
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
        registries.add(registry);
        RegisteredQuery query = registry.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_table");

        feed(query, "u1", 300L);
        commitUntilTable(query, "1|300");
        feed(query, "u1", 1L);
        commitUntilTable(query, "2|301");
        assertThat(table()).containsExactly("2|301");
        // Not exactly once without checkpoints -- each commit is its own transaction and a restart
        // repeats it -- but the default mode upserts, and a repeated upsert rewrites the values
        // already there: effectively once (HLP-4).
        assertThat(registry.sinkGuarantee("spend_so_far"))
                .hasValueSatisfying(
                        text -> assertThat(text).startsWith("effectively-once").contains("takes no checkpoints"));
    }

    @Test
    void theRegistryRefusesAQueryWhoseShapeOrKeyDiffersFromTheSinksDeclaration() {
        QueryRegistry registry = checkpointed(
                new JdbcSinks().bind("spend_table", Map.of()).bind("by_total", Map.of("key.columns", "total")));

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
                        root,
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
            throw new AssertionError(e);
        }
    }

    private void feed(RegisteredQuery query, String user, long amount) {
        feed(query, user, amount, 1L);
    }

    private void feed(RegisteredQuery query, String user, long amount, long weight) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
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
        throw new AssertionError("the view never counted " + rows + " rows: "
                + query.view().scan().stream().map(java.util.Arrays::toString).toList());
    }

    private void commitUntilTable(RegisteredQuery query, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && !table().contains(expected)) {
            query.commit();
            Thread.sleep(20);
        }
        assertThat(table()).contains(expected);
    }

    private List<String> table() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT n, total FROM spend ORDER BY n")) {
            while (rs.next()) {
                rows.add(rs.getLong(1) + "|" + rs.getLong(2));
            }
        }
        return rows;
    }

    private List<String> latest() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT user_id, amount FROM latest ORDER BY user_id")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getLong(2));
            }
        }
        return rows;
    }

    private int staged() throws SQLException {
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + JdbcSinkPlugin.DEFAULT_STAGING_TABLE)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Binds sink names to JDBC sinks writing the {@code spend} table, as the server's binding would. */
    private final class JdbcSinks implements SinkFactory {
        private final Map<String, Map<String, String>> bindings = new HashMap<>();
        private final List<Dying> opened = new ArrayList<>();
        private volatile boolean dieInsteadOfCommitting;

        JdbcSinks bind(String name, Map<String, String> extra) {
            Map<String, String> config = new HashMap<>(
                    Map.of("url", url, "table", "spend", "schema", "n:INT64,total:INT64", "key.columns", "n"));
            config.putAll(extra);
            bindings.put(name, config);
            return this;
        }

        void dieInsteadOfCommitting() {
            dieInsteadOfCommitting = true;
        }

        private JdbcSinkPlugin configured(String name) {
            Map<String, String> config = bindings.get(name);
            if (config == null) {
                throw new IllegalArgumentException("no sink named '" + name + "' is bound");
            }
            JdbcSinkPlugin plugin = new JdbcSinkPlugin();
            plugin.configure(new Ctx(name, config));
            return plugin;
        }

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            return configured(sinkName).capabilities();
        }

        @Override
        public Description describe(String sinkName) {
            JdbcSinkPlugin plugin = configured(sinkName);
            return new Description(plugin.capabilities(), plugin.schema(), plugin.keyColumns());
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            JdbcSinkPlugin plugin = configured(sinkName);
            plugin.open();
            Dying sink = new Dying(plugin);
            opened.add(sink);
            return sink;
        }

        /**
         * The sink as one process holds it: once told to, the process dies at the commit -- its
         * connection goes, and nothing it would have sent afterwards arrives.
         */
        private final class Dying implements StreamSinkPlugin {
            private final JdbcSinkPlugin delegate;
            private boolean dead;

            Dying(JdbcSinkPlugin delegate) {
                this.delegate = delegate;
            }

            @Override
            public SinkCapabilities capabilities() {
                return delegate.capabilities();
            }

            @Override
            public java.util.Optional<StreamSchema> schema() {
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

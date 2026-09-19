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
package com.ash.messaging.pravaha.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.plugin.jdbc.JdbcSourcePlugin;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.sql.plan.SourcePushdown;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

/**
 * The real JDBC source against H2, driving a real execution twice over the same table: once with
 * whatever {@link SourcePushdown} asks the plugin for, once asking for nothing. ADR-039 item 6.
 *
 * <p>Both run in lockstep -- pumped to exhaustion, then the table is changed, then both pumped again
 * -- so each sees the same data at every step, updates and deletes included. What is compared is the
 * consolidated answer: every output row with its weights summed, zero-weight rows dropped. That is
 * what a view would hold, and it is indifferent to how many intermediate emissions either path made.
 */
final class JdbcPushdownHarness implements AutoCloseable {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** One side of the comparison. */
    static final class Run implements AutoCloseable {
        final ReadRequest request;
        final PartitionReader reader;
        final QueryExecution execution;
        final IngestPump pump;
        final List<CapturingRowWriter.Captured> output = Collections.synchronizedList(new ArrayList<>());

        Run(JdbcSourcePlugin plugin, PhysicalOperator plan, boolean pushDown) {
            ReadRequest wanted =
                    pushDown ? SourcePushdown.requestFor(plan, "txn", plugin.capabilities()) : ReadRequest.NOTHING;
            this.execution = QueryExecution.start(
                    plan,
                    1,
                    LaneConfig.defaults()
                            .withInbox(4096, 4096)
                            .withBatchSize(256)
                            .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                            .withThreads("pushdown-eq", true),
                    MemoryAccess.best(),
                    () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), output::add));
            this.request = execution.acceptsPartialAggregateFor("txn") ? wanted : wanted.withoutAggregates();
            this.reader = plugin.createReader(plugin.partitions("txn").get(0), null, request);
            this.pump = execution.pumpInto(0, "txn", reader, BackpressurePolicy.defaults());
        }

        /** Pumps until the table has nothing new and the lane has applied all of it. */
        void drain() {
            int moved;
            do {
                moved = pump.pumpOnce(256);
                execution.checkHealth();
                if (moved == 0) {
                    execution.awaitQuiescent(Duration.ofSeconds(30));
                    moved = pump.pumpOnce(256);
                }
            } while (moved > 0);
            execution.awaitQuiescent(Duration.ofSeconds(30));
        }

        /** Ends the input, so the aggregate emits its final answer, and consolidates what came out. */
        Map<List<Object>, Long> finish() {
            finished = true;
            execution.close();
            execution.checkHealth();
            Map<List<Object>, Long> consolidated = new HashMap<>();
            synchronized (output) {
                for (CapturingRowWriter.Captured row : output) {
                    consolidated.merge(java.util.Arrays.asList(row.values()), row.weight(), Long::sum);
                }
            }
            consolidated.values().removeIf(weight -> weight == 0L);
            return consolidated;
        }

        private boolean finished;

        @Override
        public void close() {
            if (!finished) {
                execution.close();
            }
            reader.close();
        }
    }

    private final String url;
    private final Connection admin;
    private final JdbcSourcePlugin plugin;
    private final Map<String, String> config;
    private long clock = 1;

    /**
     * @param options extra plugin configuration, over a keyed source polling {@code txn} by
     *     {@code updated_at} in pages of seven rows -- small, so every run crosses many pages
     */
    JdbcPushdownHarness(Map<String, String> options) throws SQLException {
        // Unquoted identifiers in lower case, so the stream's columns are named as the SQL names them.
        this.url = "jdbc:h2:mem:pushdown_eq" + DATABASE.incrementAndGet() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        this.admin = DriverManager.getConnection(url);
        execute("CREATE TABLE txn (id BIGINT NOT NULL, status VARCHAR(16), region INT, amount BIGINT, "
                + "note VARCHAR(32), updated_at BIGINT NOT NULL)");
        this.config = new HashMap<>(Map.of(
                "url",
                url,
                "table",
                "txn",
                "watermark.column",
                "updated_at",
                "key.column",
                "id",
                "stream",
                "txn",
                "fetch.size",
                "7"));
        config.putAll(options);
        this.plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("txn", config));
        plugin.open();
    }

    StreamSchema schema() {
        return plugin.schema();
    }

    /** The plugin's own configuration plus {@code extra}, as a deployment's binding would give it. */
    Map<String, String> binding(Map<String, String> extra) {
        Map<String, String> binding = new HashMap<>(config);
        binding.putAll(extra);
        return binding;
    }

    /**
     * Plans {@code sql} as a bounded read would be planned: the engine executes an unwindowed GROUP
     * BY the same way either way, and only a continuous registration refuses one (PRV-2050).
     */
    PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .overBoundedInput()
                .build(SqlPlanner.withStreams(plugin.schema()).plan(sql));
    }

    /** The first row of {@code sql}, asked of the database directly, as longs. */
    long[] ask(String sql) throws SQLException {
        try (Statement statement = admin.createStatement();
                java.sql.ResultSet results = statement.executeQuery(sql)) {
            results.next();
            long[] values = new long[results.getMetaData().getColumnCount()];
            for (int i = 0; i < values.length; i++) {
                values[i] = results.getLong(i + 1);
            }
            return values;
        }
    }

    Run run(PhysicalOperator plan, boolean pushDown) {
        return new Run(plugin, plan, pushDown);
    }

    void execute(String sql) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Inserts rows {@code [from, to)}, derived from {@code seed}, each with a fresh watermark. */
    void insert(long from, long to, long seed) throws SQLException {
        for (long id = from; id < to; id++) {
            long mix = (id * 2654435761L + seed) & 0x7fffffff;
            String status = mix % 3 == 0 ? "DONE" : (mix % 3 == 1 ? "done" : "OPEN");
            String region = mix % 7 == 0 ? "NULL" : String.valueOf(mix % 4);
            String amount = mix % 13 == 0 ? "NULL" : String.valueOf(mix % 200);
            // Watermarks tie in threes, so page boundaries fall between tied rows.
            execute("INSERT INTO txn VALUES (" + id + ", '" + status + "', " + region + ", " + amount + ", 'n" + id
                    + "', " + (clock + (id - from) / 3) + ")");
        }
        clock += (to - from) / 3 + 1;
    }

    /** An update the source sees again: the row's watermark moves past everything read so far. */
    void update(long id, long amount) throws SQLException {
        execute("UPDATE txn SET amount = " + amount + ", updated_at = " + clock++ + " WHERE id = " + id);
    }

    /** A delete, which a polled source cannot see -- neither path, so the answers still agree. */
    void delete(long id) throws SQLException {
        execute("DELETE FROM txn WHERE id = " + id);
    }

    @Override
    public void close() throws SQLException {
        plugin.close();
        admin.close();
    }
}

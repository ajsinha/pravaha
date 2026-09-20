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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.LookupJoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enrichment from a dimension table.
 *
 * <p>The join most streaming pipelines actually need, and the one that does not grow: a
 * stream-to-stream join holds both sides in state, this holds nothing but a cache it chooses to
 * keep. Every record asks the store for its key and uses the answer immediately.
 *
 * <p>Two behaviours carry the design. A {@code LEFT} lookup join is safe where the same syntax
 * against a stream is refused, because a lookup answers definitively -- the unmatched record goes
 * out with nulls and nothing later can turn that miss into a hit. And the cache's lifetime belongs
 * to the source: only the source knows how stale its rows may be.
 */
class LookupJoinTest {

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.int64())
                .field("ts", Types.timestamp())
                // Declared, not merely present: a windowed query over a stream with no declared
                // event time is refused (TIME-6), because no watermark advances over it and no
                // window it opens could ever close.
                .eventTime("ts")
                .build();
    }

    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("user_id", Types.int64())
                .field("segment", Types.string())
                .build();
    }

    /** A dimension table in a map, counting how often it was actually asked. */
    private static class MapLookup implements LookupSourcePlugin {
        final Map<Long, String> rows = new LinkedHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final Duration cacheFor;

        MapLookup(Duration cacheFor) {
            this.cacheFor = cacheFor;
        }

        @Override
        public StreamSchema schema() {
            return users();
        }

        @Override
        public List<String> keyColumns() {
            return List.of("user_id");
        }

        @Override
        public int lookup(Object[] key, PartitionReader.RecordSink sink) {
            calls.incrementAndGet();
            String segment = rows.get(((Number) key[0]).longValue());
            if (segment == null) {
                return 0;
            }
            RowWriter writer = sink.beginRow();
            writer.setLong(0, ((Number) key[0]).longValue())
                    .setString(1, segment)
                    .commit();
            return 1;
        }

        @Override
        public Duration cacheFor() {
            return cacheFor;
        }

        @Override
        public String name() {
            return "map-lookup";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}
    }

    @Test
    void aRegisteredQueryCanReachALookupJoin(@TempDir java.nio.file.Path dir) throws Exception {
        // The blocker: lookup joins were implemented, optimised, tested and documented, and
        // unreachable. SqlPlanner.withLookups had no caller in main, so every registration planned
        // every schema as a consumed stream -- a query joining a dimension planned as a
        // stream-to-stream join and waited for rows a dimension table never sends. A documented
        // tick against a feature no shipped surface could execute.
        //
        // This is the registry path, which is the only way anything in production registers.
        java.nio.file.Path data = dir.resolve("orders.csv");
        java.nio.file.Files.writeString(data, "1,7,0\n2,9,1000000\n3,7,2000000\n");

        MapLookup users = new MapLookup(Duration.ZERO);
        users.rows.put(7L, "gold");
        users.rows.put(9L, "silver");

        com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds feeds =
                new com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds()
                        .bind(new com.ash.messaging.pravaha.bindings.ingest.SourceBinding(
                                "orders",
                                "filesystem",
                                Map.of(
                                        "path",
                                        data.toString(),
                                        "schema",
                                        "order_id:INT64,user_id:INT64,ts:TIMESTAMP")));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, orders()).lookingUp(users).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register(
                    "enriched",
                    "SELECT o.order_id, u.segment FROM orders o "
                            + "JOIN users FOR SYSTEM_TIME AS OF o.ts AS u ON o.user_id = u.user_id",
                    List.of(0),
                    Principal.ANONYMOUS);
            query.awaitApplied(Duration.ofSeconds(20));

            ViewQuery reader = new ViewQuery(views);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline
                    && reader.execute("SELECT * FROM enriched").size() < 3) {
                Thread.sleep(20);
            }

            assertThat(reader.execute("SELECT order_id, segment FROM enriched").rows().stream()
                            .map(row -> row[0] + "=" + row[1])
                            .sorted()
                            .toList())
                    .as("every order is enriched from the dimension table, by the registry path")
                    .containsExactly("1=gold", "2=silver", "3=gold");
        }
    }

    private static final String INNER = "SELECT o.order_id, u.segment FROM orders o "
            + "JOIN users FOR SYSTEM_TIME AS OF o.ts AS u ON o.user_id = u.user_id";
    private static final String OUTER = "SELECT o.order_id, u.segment FROM orders o "
            + "LEFT JOIN users FOR SYSTEM_TIME AS OF o.ts AS u ON o.user_id = u.user_id";

    @Test
    void eachRecordIsEnrichedFromTheDimensionTable() {
        MapLookup users = new MapLookup(Duration.ZERO);
        users.rows.put(1L, "gold");
        users.rows.put(2L, "silver");

        assertThat(run(INNER, users, List.of(1L, 2L, 1L))).containsExactly("1:gold", "2:silver", "3:gold");
    }

    @Test
    void anInnerLookupJoinDropsARecordWithNoMatch() {
        MapLookup users = new MapLookup(Duration.ZERO);
        users.rows.put(1L, "gold");

        assertThat(run(INNER, users, List.of(1L, 99L, 1L))).containsExactly("1:gold", "3:gold");
    }

    @Test
    void aLeftLookupJoinEmitsTheRecordWithNullsAndNeverRetractsIt() {
        // Safe here and refused between two streams, for a reason rather than by inconsistency: the
        // lookup has already answered, so no later arrival can turn this miss into a hit.
        MapLookup users = new MapLookup(Duration.ZERO);
        users.rows.put(1L, "gold");

        assertThat(run(OUTER, users, List.of(1L, 99L))).containsExactly("1:gold", "2:null");
    }

    @Test
    void aFilterOnALookedUpColumnIsRefusedOverAnInnerJoinAndRunsOverALeftOne() throws Exception {
        // HLP-13, pinned with CONTINUOUS_QUERIES §14. The inner form is refused -- with a message
        // about correlated subqueries, which is what the planner sees and not what was written --
        // and the LEFT form the document recommends instead keeps exactly the rows the inner one
        // would have: the WHERE drops the null-padded row for the unknown key.
        MapLookup users = new MapLookup(Duration.ZERO);
        users.rows.put(1L, "gold");
        users.rows.put(2L, "silver");

        assertThatThrownBy(() -> plan(INNER + " WHERE u.segment = 'gold'"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020");
        assertThat(run(OUTER + " WHERE u.segment = 'gold'", users, List.of(1L, 2L, 99L, 1L)))
                .containsExactly("1:gold", "4:gold");

        String doc = java.nio.file.Files.readString(repoRoot().resolve("docs/CONTINUOUS_QUERIES.md"));
        assertThat(doc)
                .contains("A filter on a looked-up column, over an inner lookup join")
                .contains("Write the same join as `LEFT JOIN … WHERE u.tier = 'gold'`");
    }

    private static java.nio.file.Path repoRoot() {
        java.nio.file.Path path = java.nio.file.Path.of("").toAbsolutePath();
        while (path != null && !java.nio.file.Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException("could not find the repository root");
        }
        return path;
    }

    @Test
    void theStoreIsAskedOncePerRecordWhenTheSourceAllowsNoCaching() {
        // An account balance cannot be cached, and a source that says so must be asked every time --
        // including for a key it was just asked about.
        MapLookup users = new MapLookup(Duration.ZERO);
        users.rows.put(1L, "gold");

        run(INNER, users, List.of(1L, 1L, 1L));

        assertThat(users.calls).hasValue(3);
    }

    @Test
    void aCacheableSourceIsAskedOncePerKey() {
        MapLookup users = new MapLookup(Duration.ofMinutes(5));
        users.rows.put(1L, "gold");
        users.rows.put(2L, "silver");

        run(INNER, users, List.of(1L, 1L, 2L, 1L, 2L));

        assertThat(users.calls).as("five records over two keys").hasValue(2);
    }

    @Test
    void aMissIsCachedToo() {
        // Otherwise a stream of unknown keys -- a scan, a probe, a bad producer -- hammers the store
        // with the one query guaranteed to return nothing.
        MapLookup users = new MapLookup(Duration.ofMinutes(5));

        run(OUTER, users, List.of(99L, 99L, 99L));

        assertThat(users.calls).hasValue(1);
    }

    @Test
    void joiningAStreamThisWayIsRefusedWithWhatToDoInstead() {
        // 'users' registered as a stream rather than a lookup table: the difference is a
        // hundred-million-row table held in memory or not held at all.
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(orders(), users()).plan(INNER)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("registerLookup");
    }

    @Test
    void aPlanWhoseLookupTableWasNotSuppliedFailsAtStartUpRatherThanOnTheFirstRecord() {
        PhysicalOperator plan = plan(INNER);

        assertThatThrownBy(() -> InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {}), Map.of()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not among the dimension tables");
    }

    @Test
    void thePlanSaysWhatItLooksUpAndOnWhat() {
        PhysicalOperator plan = plan(INNER);
        String explained = PhysicalPlanBuilder.explain(plan);

        assertThat(explained).contains("LookupJoin[users on [user_id]]");
        assertThat(PhysicalPlanBuilder.explain(plan(OUTER))).contains("LookupLeftJoin[users");
    }

    @Test
    void aLookupJoinHoldsNoStateToCheckpoint() {
        // The property that distinguishes it from a stream-to-stream join. A cache lost on restart
        // costs latency, not correctness, so there is nothing to bound and nothing to restore.
        PhysicalOperator plan = plan(INNER);
        while (!(plan instanceof LookupJoinOperator) && !plan.inputs().isEmpty()) {
            plan = plan.inputs().get(0);
        }
        assertThat(plan).isInstanceOf(LookupJoinOperator.class);
        assertThat(plan.isStateful()).isFalse();
    }

    @Test
    void slowLookupsOverlapRatherThanQueueingBehindEachOther() {
        // The point of doing this on virtual threads. Ten records, each needing a lookup that takes
        // 50 ms: done one at a time that is half a second, and the whole operator caps a lane at the
        // inverse of the store's latency. Overlapped it is one round trip plus change.
        SlowLookup users = new SlowLookup(Duration.ofMillis(50));
        for (long id = 0; id < 10; id++) {
            users.rows.put(id, "seg-" + id);
        }

        List<String> out = run(INNER, users, List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));

        assertThat(out).hasSize(10);
        // Asserted on observed overlap rather than on elapsed time, and the reason is worth keeping.
        // The stopwatch version required ten 50 ms lookups to finish inside 400 ms, which measures
        // the machine as much as the operator: on a box running three builds it recorded 554 ms --
        // slower than fully sequential -- and failed a gate for scheduling starvation that had
        // nothing to do with lookups queueing.
        //
        // The property this case is actually about is that lookups are in flight together. Counting
        // them says so directly, and says it the same way on a busy machine as on an idle one.
        assertThat(users.peakInFlight())
                .as("lookups ran one at a time; the whole point of the virtual-thread fan-out is that "
                        + "an operator is not capped at the inverse of the store's latency")
                .isGreaterThan(1);
    }

    @Test
    void outputStaysInArrivalOrderHoweverTheLookupsFinish() {
        // Lookups complete out of order by design -- the first key here is slow and the rest are
        // instant -- and emitting as they finish would reorder the stream. Sequence numbers going
        // backwards break the deduplicating sink and every window downstream, and the reordering is
        // invisible in any test that only checks which rows came out.
        //
        // Thirty records rather than five, deliberately. With a handful, every lookup is still
        // outstanding when the last record arrives, the queue drains in order at the end whatever
        // the draining rule is, and a seeded out-of-order bug passes.
        SlowLookup users = new SlowLookup(Duration.ZERO);
        users.slowKey = 0L;
        users.slowBy = Duration.ofMillis(300);
        List<Long> keys = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (long id = 0; id < 30; id++) {
            users.rows.put(id, "seg-" + id);
            keys.add(id);
            expected.add((id + 1) + ":seg-" + id);
        }

        assertThat(run(INNER, users, keys)).containsExactlyElementsOf(expected);
    }

    @Test
    void aQuietStreamDoesNotLeaveItsLastRecordsWaiting() {
        // A parked record would otherwise wait for the next arrival to push it out, so a stream that
        // goes quiet leaves its last few records unanswered for as long as the quiet lasts. End of
        // input is the extreme case of that and is what this checks; the lane's idle path calls the
        // same drain.
        SlowLookup users = new SlowLookup(Duration.ZERO);
        users.slowKey = 7L;
        users.slowBy = Duration.ofMillis(50);
        users.rows.put(7L, "gold");

        assertThat(run(INNER, users, List.of(7L))).containsExactly("1:gold");
    }

    /** A dimension table that takes its time, so the overlap is observable. */
    private static final class SlowLookup extends MapLookup {
        private Long slowKey;
        private Duration slowBy = Duration.ZERO;
        private final Duration everyLookup;

        private final java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();

        SlowLookup(Duration everyLookup) {
            super(Duration.ZERO);
            this.everyLookup = everyLookup;
        }

        /** The most lookups this store ever had open at once. Load-independent, unlike a stopwatch. */
        int peakInFlight() {
            return peak.get();
        }

        @Override
        public int lookup(Object[] key, PartitionReader.RecordSink sink) {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                return lookupCounted(key, sink);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private int lookupCounted(Object[] key, PartitionReader.RecordSink sink) {
            long id = ((Number) key[0]).longValue();
            Duration delay = slowKey != null && slowKey == id ? slowBy : everyLookup;
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.lookup(key, sink);
        }
    }

    @Test
    void aWatermarkDoesNotOvertakeRecordsStillWaitingOnTheirLookup() {
        // The bug this test exists for, found by an end-to-end run against Aerospike where the query
        // produced nothing at all. A record parked on a network round trip has been consumed but not
        // yet placed in a window. If a watermark passes it, the window fires without it and the
        // record then arrives as late data for a window that has already closed -- and with the
        // default zero allowed lateness it is dropped outright. The query loses exactly the records
        // whose lookups were slowest, silently, and looks merely quiet.
        StreamSchema txn = StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        StreamSchema dim = StreamSchema.builder("users")
                .field("user_id", Types.int64())
                .field("segment", Types.string())
                .build();
        String sql = "SELECT window_end, t.user_id, SUM(t.amount) AS total "
                + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) AS t "
                + "LEFT JOIN users FOR SYSTEM_TIME AS OF t.event_time AS u ON t.user_id = u.user_id "
                + "GROUP BY window_start, window_end, t.user_id";

        SlowLookup users = new SlowLookup(Duration.ofMillis(40));
        users.rows.put(1L, "gold");

        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withLookups(txn, dim).plan(sql));
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        RowLayout layout = RowLayout.of(txn);

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan,
                        (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add),
                        Map.of("users", users))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (long i = 1; i <= 3; i++) {
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, 1L)
                        .setLong(1, 100 * i)
                        .setLong(2, i * 1_000_000_000L)
                        .weight(1L)
                        .eventTimestampNanos(i * 1_000_000_000L)
                        .sequence(i)
                        .commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }

            // Straight to the watermark, with every lookup still in flight.
            pipeline.advanceWatermark(20_000_000_000L);
        }

        assertThat(out)
                .as("the window fired without the records still waiting on their lookup")
                .hasSize(1);
        assertThat(out.get(0).asLong(2))
                .as("all three amounts, not the ones that happened to be quick")
                .isEqualTo(600L);
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withLookups(orders(), users()).plan(sql));
    }

    /** Feeds one order per user id, numbering the orders from one. */
    private static List<String> run(String sql, LookupSourcePlugin lookup, List<Long> userIds) {
        PhysicalOperator plan = plan(sql);
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        RowLayout layout = RowLayout.of(orders());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan,
                        (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add),
                        Map.of("users", lookup))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            long orderId = 0;
            for (long userId : userIds) {
                orderId++;
                long handle = feed.allocate(layout.rowSize(128));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, orderId)
                        .setLong(1, userId)
                        .setLong(2, orderId)
                        .weight(1L)
                        .eventTimestampNanos(orderId)
                        .sequence(orderId)
                        .commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return results.stream()
                .map(row -> row.asLong(0) + ":" + (row.isNull(1) ? "null" : row.values()[1]))
                .toList();
    }
}

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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * {@code deletes: detect} against a real Community Edition server: an insert, an update, a delete and
 * a re-insert each reach the emitted rows exactly; a restart between passes and one mid-pass resume
 * without a spurious retraction or a missed one; the ceiling refuses; and a registered view over the
 * source, checkpointed and restarted through the registry a node runs, equals the set after deletes.
 *
 * <p>What a mock cannot say and this does: that a record deleted on the server is really absent from
 * the next full scan (not a tombstone the client hands back), that a digest is stable across scans,
 * and that bins read back decode to the same recording pass after pass -- without which every
 * unchanged record would be retracted and re-inserted on every pass.
 */
@Timeout(300)
class AerospikeDeleteDetectionIT {

    private static final String SCHEMA = "id:INT64,status:STRING,amount:INT64";
    private static final AtomicInteger SETS = new AtomicInteger();

    private static GenericContainer<?> aerospike;
    private static String hosts;
    private static IAerospikeClient admin;

    private final String set = "dd" + SETS.incrementAndGet();
    private final List<QueryRegistry> registries = new ArrayList<>();

    @TempDir
    Path stateDir;

    @TempDir
    Path checkpoints;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startServer() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the Aerospike tests need a real server")
                .isTrue();
        aerospike = AerospikeContainer.create();
        aerospike.start();
        hosts = "127.0.0.1:" + AerospikeContainer.PORT;
        com.aerospike.client.policy.ClientPolicy policy = new com.aerospike.client.policy.ClientPolicy();
        policy.failIfNotConnected = true;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (admin == null) {
            try {
                admin = new AerospikeClient(
                        policy, new com.aerospike.client.Host("127.0.0.1", AerospikeContainer.PORT));
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                sleep(1000);
            }
        }
    }

    @AfterAll
    static void stopServer() {
        if (admin != null) {
            admin.close();
        }
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @AfterEach
    void closeRegistries() {
        registries.forEach(QueryRegistry::close);
    }

    private void put(long id, String status, long amount) {
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, set, id),
                new Bin("id", id),
                new Bin("status", status),
                new Bin("amount", amount));
    }

    private void delete(long id) {
        assertThat(admin.delete(new WritePolicy(), new Key(AerospikeContainer.NAMESPACE, set, id)))
                .isTrue();
    }

    private Map<String, String> options(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "hosts",
                hosts,
                "namespace",
                AerospikeContainer.NAMESPACE,
                "set",
                set,
                "schema",
                SCHEMA,
                "stream",
                "orders",
                "deletes",
                "detect",
                "deletes.state.dir",
                stateDir.toString(),
                "scan.interval.ms",
                "0"));
        config.putAll(extra);
        return config;
    }

    private AerospikeSourcePlugin source(Map<String, String> extra) {
        AerospikeSourcePlugin plugin = new AerospikeSourcePlugin();
        plugin.configure(new Ctx("orders", options(extra)));
        plugin.open();
        return plugin;
    }

    /** The set as the Z-set of rows a view over it must hold. */
    private Map<List<Object>, Long> setAsView() {
        Map<List<Object>, Long> view = new HashMap<>();
        admin.scanAll(null, AerospikeContainer.NAMESPACE, set, (key, record) -> {
            synchronized (view) {
                view.merge(
                        java.util.Arrays.asList(
                                record.getLong("id"), record.getString("status"), record.getLong("amount")),
                        1L,
                        Long::sum);
            }
        });
        return view;
    }

    private static List<Object> row(long id, String status, long amount) {
        return java.util.Arrays.asList(id, status, amount);
    }

    @Test
    void insertUpdateDeleteAndReinsertEachReachTheEmittedRowsExactly() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        put(3, "NEW", 30);
        AerospikeSourcePlugin plugin = source(Map.of());
        SourcePartition partition = plugin.partitions("orders").get(0);
        try (PartitionReader reader = plugin.createReader(partition, null)) {
            WeightedCollector out = new WeightedCollector(plugin.schema()).drain(reader);
            assertThat(out.rows).hasSize(3);
            assertThat(out.view).isEqualTo(setAsView());

            out.clearRows();
            out.drain(reader);
            assertThat(out.rows)
                    .as("an unchanged set emits nothing on the next full pass")
                    .isEmpty();

            put(2, "SHIPPED", 25);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(row(2, "NEW", 20), -1L),
                            org.assertj.core.groups.Tuple.tuple(row(2, "SHIPPED", 25), 1L));

            out.clearRows();
            delete(3);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(row(3, "NEW", 30), -1L));

            out.clearRows();
            put(3, "AGAIN", 33);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(row(3, "AGAIN", 33), 1L));
            assertThat(out.view).isEqualTo(setAsView());
        }
        plugin.close();
    }

    @Test
    void aRestartBetweenPassesAndOneMidPassNeitherInventNorMissARetraction() {
        for (long id = 1; id <= 6; id++) {
            put(id, "NEW", id * 10);
        }
        AerospikeSourcePlugin plugin = source(Map.of());
        SourcePartition partition = plugin.partitions("orders").get(0);

        // Mid-pass: two of six rows emitted when the checkpoint is taken.
        WeightedCollector out = new WeightedCollector(plugin.schema());
        SourceOffset midPass;
        try (PartitionReader reader = plugin.createReader(partition, null)) {
            assertThat(reader.poll(out, 2)).isEqualTo(2);
            midPass = reader.position();
        }
        delete(1);
        delete(6);
        put(4, "DONE", 40);

        WeightedCollector resumed = WeightedCollector.restoredFrom(plugin.schema(), out.view);
        SourceOffset betweenPasses;
        try (PartitionReader reader = plugin.createReader(partition, midPass)) {
            resumed.drain(reader);
            assertThat(resumed.view).isEqualTo(setAsView());
            betweenPasses = reader.position();
        }
        long retracted = resumed.rows.stream().filter(e -> e.weight() < 0).count();
        assertThat(retracted)
                .as("only rows the checkpoint had emitted can be retracted: of 1 and 6, only whichever of them "
                        + "was among the two emitted, plus 4's old value if it was")
                .isLessThanOrEqualTo(2);

        // Between passes: the process is down while the set changes.
        delete(2);
        put(7, "NEW", 70);
        WeightedCollector again = WeightedCollector.restoredFrom(plugin.schema(), resumed.view);
        try (PartitionReader reader = plugin.createReader(partition, betweenPasses)) {
            again.drain(reader);
        }
        assertThat(again.rows)
                .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(row(2, "NEW", 20), -1L),
                        org.assertj.core.groups.Tuple.tuple(row(7, "NEW", 70), 1L));
        assertThat(again.view).isEqualTo(setAsView());
        plugin.close();
    }

    @Test
    void aSetLargerThanTheCeilingIsRefusedByCode() {
        for (long id = 1; id <= 5; id++) {
            put(id, "NEW", id);
        }
        AerospikeSourcePlugin plugin = source(Map.of("deletes.max.keys", "4"));
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null)) {
            WeightedCollector out = new WeightedCollector(plugin.schema());
            assertThatThrownBy(() -> reader.poll(out, 100))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e -> assertThat(((PravahaException) e).errorCode())
                            .isEqualTo(AerospikeErrors.DELETE_STATE_FULL));
            assertThat(out.rows).isEmpty();
        }
        plugin.close();
    }

    private static final StreamSchema ORDERS = AerospikeSchemas.parse("orders", SCHEMA);

    private QueryRegistry registry() {
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("orders", "aerospike", options(Map.of("scan.interval.ms", "100"))));
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), ORDERS)
                .feedingFrom(feeds)
                .checkpointingTo(
                        checkpoints,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "30s")
                                .build());
        registries.add(registry);
        return registry;
    }

    /**
     * A checksum of the whole set that one row can hold: how many records, the sum of amounts, and
     * the sum of {@code id * id + amount} -- which moves if any one record is missing, doubled, or
     * holds another value. The row-for-row view is checked before the restart; across it, only this
     * aggregate is, because a restored projection view dies on its first commit (see the comment on
     * the restart below).
     */
    private static final String CHECKSUM =
            "SELECT COUNT(*) AS n, SUM(amount) AS total, SUM(id * id + amount) AS mix FROM orders";

    @Test
    void aRegisteredViewEqualsTheSetAfterDeletesAndAcrossARestart() throws Exception {
        for (long id = 1; id <= 20; id++) {
            put(id, id % 2 == 0 ? "EVEN" : "ODD", id);
        }
        QueryRegistry first = registry();
        RegisteredQuery rows =
                first.register("orders_now", "SELECT id, status, amount FROM orders", List.of(0), Principal.ANONYMOUS);
        RegisteredQuery totals = first.register("orders_total", CHECKSUM, List.of(0), Principal.ANONYMOUS);
        awaitRows(rows);
        awaitChecksum(totals);

        delete(3);
        delete(4);
        put(5, "CHANGED", 500);
        awaitRows(rows);
        awaitChecksum(totals);

        checkpointerOf(totals).checkpointNow();
        delete(6);
        awaitChecksum(totals); // delivered after the checkpoint, so the restore must redo it
        awaitRows(rows);
        first.close();
        registries.remove(first);

        // While nothing is reading.
        delete(7);
        put(3, "BACK", 3);
        put(21, "NEW", 21);

        // Only the aggregate is registered again. A projection view restored from a checkpoint is
        // killed by its feed's first commit when that commit runs before the lane has applied a row:
        // ViewSink's frontier starts at Long.MIN_VALUE rather than at the restored view's, and
        // ServedView.commit refuses "frontier went backwards" (PRV-5092, query still RUNNING). That
        // is the engine's, whatever the source, and is reported rather than worked round here.
        QueryRegistry second = registry();
        RegisteredQuery totalsAgain = second.register("orders_total", CHECKSUM, List.of(0), Principal.ANONYMOUS);
        awaitChecksum(totalsAgain);
        sleep(1_000);
        assertThat(checksum(totalsAgain))
                .as("restored from the checkpoint and brought to the set by one pass: 6 and 7 retracted once, "
                        + "3 back, 21 new, and nothing counted twice")
                .isEqualTo(setChecksum());
    }

    private Set<String> setRows() {
        return setAsView().keySet().stream().map(Object::toString).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> viewRows(RegisteredQuery query) {
        return query.view().scan().stream()
                .map(row -> java.util.Arrays.asList(row).toString())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private List<Long> setChecksum() {
        long n = 0;
        long total = 0;
        long mix = 0;
        for (List<Object> row : setAsView().keySet()) {
            long id = (Long) row.get(0);
            long amount = (Long) row.get(2);
            n++;
            total += amount;
            mix += id * id + amount;
        }
        return List.of(n, total, mix);
    }

    private static List<Long> checksum(RegisteredQuery query) {
        List<Object[]> scanned = query.view().scan();
        if (scanned.isEmpty()) {
            return List.of(0L, 0L, 0L);
        }
        Object[] row = scanned.get(0);
        return List.of(
                ((Number) row[0]).longValue(),
                row[1] == null ? 0L : ((Number) row[1]).longValue(),
                row[2] == null ? 0L : ((Number) row[2]).longValue());
    }

    private void awaitRows(RegisteredQuery rows) {
        await(
                () -> viewRows(rows).equals(setRows()),
                () -> "the view holds " + viewRows(rows) + ", the set holds " + setRows() + " (" + rows.state()
                        + ", feed " + rows.feed().describe() + ")");
    }

    private void awaitChecksum(RegisteredQuery totals) {
        await(
                () -> checksum(totals).equals(setChecksum()),
                () -> "the view holds " + checksum(totals)
                        + ", the set holds " + setChecksum() + " (" + totals.state() + ", feed "
                        + totals.feed().describe()
                        + ")");
    }

    private static void await(java.util.function.BooleanSupplier done, java.util.function.Supplier<String> why) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(why.get());
            }
            sleep(50);
        }
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) throws ReflectiveOperationException {
        Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
        field.setAccessible(true);
        return (PeriodicCheckpointer) field.get(query);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

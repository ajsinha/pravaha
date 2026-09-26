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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The Aerospike plugin against a real Aerospike server.
 *
 * <p>Community Edition in a container, which is the edition the design's gap G4 is about: no change
 * feed at all. Everything asserted here is what the {@code lut-scan} strategy can genuinely do, and
 * the things it cannot do are asserted too -- a test suite that only demonstrates the happy path
 * would leave the reader believing this source sees deletes.
 *
 * <p>Skipped rather than failed when docker is unavailable. A build that goes red on a machine
 * without docker teaches people to ignore red builds.
 */
@Timeout(300)
class AerospikePluginIT {

    private static GenericContainer<?> aerospike;
    private static String hosts;
    private static IAerospikeClient admin;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startServer() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the Aerospike tests need a real server")
                .isTrue();
        aerospike = AerospikeContainer.create();
        aerospike.start();
        hosts = "127.0.0.1:" + AerospikeContainer.PORT;
        // Single-node, because the container's node advertises its own internal address and the
        // client would try to connect to that from the host.
        com.aerospike.client.policy.ClientPolicy policy = new com.aerospike.client.policy.ClientPolicy();
        policy.failIfNotConnected = true;
        admin = connectWithRetries(policy);
    }

    /**
     * Connects, retrying while the node finishes coming up.
     *
     * <p>Even after the migration log line, a client can catch the node mid-initialisation. The
     * retry is a few seconds of patience rather than a sleep chosen by guesswork, and it fails with
     * the server's own message when the patience runs out.
     */
    private static IAerospikeClient connectWithRetries(com.aerospike.client.policy.ClientPolicy policy) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return new AerospikeClient(policy, new com.aerospike.client.Host("127.0.0.1", AerospikeContainer.PORT));
            } catch (RuntimeException e) {
                last = e;
                sleep(1000);
            }
        }
        throw last;
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

    @BeforeEach
    void clearSet() {
        admin.truncate(null, AerospikeContainer.NAMESPACE, "orders", null);
        // Truncate is asynchronous in the server; a short settle avoids a test reading the previous
        // test's records and blaming this one.
        sleep(200);
    }

    private static void put(long id, String status, long amount) {
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", id),
                new Bin("order_id", id),
                new Bin("status", status),
                new Bin("amount", amount));
    }

    private static final String SCHEMA = "order_id:INT64,status:STRING,amount:INT64";

    private static AerospikeSourcePlugin source(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "hosts",
                hosts,
                "namespace",
                AerospikeContainer.NAMESPACE,
                "set",
                "orders",
                "schema",
                SCHEMA,
                "stream",
                "orders"));
        config.putAll(extra);
        AerospikeSourcePlugin plugin = new AerospikeSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        return plugin;
    }

    @Test
    void aScanReadsWhatIsInTheSet() {
        put(1, "NEW", 100);
        put(2, "DONE", 250);
        AerospikeSourcePlugin plugin = source(Map.of());

        List<Object[]> rows = drain(plugin, null, ReadRequest.NOTHING);

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> (Long) row[0]).sorted().toList()).containsExactly(1L, 2L);
        plugin.close();
    }

    @Test
    void resumingReadsOnlyWhatChangedSince() {
        // The whole strategy. The filter runs on the server, so a set of a hundred million records
        // with a thousand recent changes transfers a thousand -- and a test that only checked the
        // count would pass just as well if the filtering happened here.
        put(1, "NEW", 100);
        AerospikeSourcePlugin plugin = source(Map.of());

        PartitionReader first = plugin.createReader(plugin.partitions("orders").get(0), null);
        Collector out = new Collector(plugin.schema());
        while (first.poll(out, 100) > 0) {
            // drain
        }
        var resumeFrom = first.position();
        first.close();
        assertThat(out.rows).hasSize(1);

        sleep(1100);
        put(2, "NEW", 200);

        List<Object[]> after = drain(plugin, resumeFrom, ReadRequest.NOTHING);

        // The resumed scan must not see the first record again -- that is what the watermark is for.
        // It may see the second one more than once, and the assertion says so rather than pretending
        // otherwise: the filter is greater-or-equal and record times have millisecond resolution, so
        // a record written in the same millisecond a scan started is re-read. Duplicated is the safe
        // direction and the declared guarantee is at-least-once. Asserting no duplicates here would
        // be asserting something this strategy does not offer, and it failed on a run where the
        // machine was fast enough to land both in one millisecond.
        assertThat(after).isNotEmpty();
        assertThat(after.stream().map(row -> (Long) row[0]).distinct().toList())
                .as("the resumed scan re-read a record it had already delivered")
                .containsExactly(2L);
        plugin.close();
    }

    @Test
    void aPushedFilterIsEvaluatedByTheServer() {
        for (long id = 1; id <= 20; id++) {
            put(id, id % 2 == 0 ? "DONE" : "NEW", id * 10);
        }
        AerospikeSourcePlugin plugin = source(Map.of());
        ReadRequest request = new ReadRequest(List.of(
                new ReadRequest.Filter("status", ReadRequest.Comparison.EQ, "DONE"),
                new ReadRequest.Filter("amount", ReadRequest.Comparison.GE, 100L)));

        List<Object[]> rows = drain(plugin, null, request);

        // Ten are DONE; of those, amount >= 100 keeps ids 10 to 20 even.
        assertThat(rows.stream().map(row -> (Long) row[0]).sorted().toList())
                .containsExactly(10L, 12L, 14L, 16L, 18L, 20L);
        plugin.close();
    }

    /**
     * ADR-039 item 6: a pushed projection names its bins on the scan, so the server sends only
     * those. A bin not asked for is written as the placeholder a column nothing reads gets -- here
     * the empty string, because {@code status} is declared NOT NULL.
     */
    @Test
    void aPushedProjectionScansOnlyTheNamedBins() {
        for (long id = 1; id <= 5; id++) {
            put(id, "DONE", id * 10);
        }
        AerospikeSourcePlugin plugin = source(Map.of());
        ReadRequest request = new ReadRequest(List.of(), List.of("order_id", "amount"), List.of());

        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            assertThat(((LutScanReader) reader).binNames()).containsExactly("order_id", "amount");
        }
        List<Object[]> rows = drain(plugin, null, request);
        assertThat(rows).hasSize(5);
        for (Object[] row : rows) {
            assertThat((Long) row[2]).isEqualTo((Long) row[0] * 10);
            assertThat(row[1])
                    .as("status was not asked for, so the server did not send it")
                    .isEqualTo("");
        }
        plugin.close();
    }

    /** ADR-039 item 6: the OR a shared reader pushes is one server-side expression. */
    @Test
    void anOrOfAlternativesIsEvaluatedByTheServer() {
        for (long id = 1; id <= 20; id++) {
            put(id, id % 2 == 0 ? "DONE" : "NEW", id * 10);
        }
        AerospikeSourcePlugin plugin = source(Map.of());
        ReadRequest request = new ReadRequest(
                List.of(new ReadRequest.Filter("status", ReadRequest.Comparison.EQ, "DONE")),
                List.of(),
                List.of(),
                List.of(
                        List.of(new ReadRequest.Filter("amount", ReadRequest.Comparison.LT, 50L)),
                        List.of(new ReadRequest.Filter("amount", ReadRequest.Comparison.GT, 170L))));

        List<Object[]> rows = drain(plugin, null, request);

        // DONE is the even ids; of those, amount < 50 keeps 2 and 4, amount > 170 keeps 18 and 20.
        assertThat(rows.stream().map(row -> (Long) row[0]).sorted().toList()).containsExactly(2L, 4L, 18L, 20L);
        plugin.close();
    }

    @Test
    void aLegacyBooleanStoredAsAnIntegerIsPushedTheWayItIsRead() {
        // Aerospike only grew a boolean particle in server 5.6, and copyInto still reads an integer
        // bin as a boolean because plenty of live data is still written that way. The pushdown did
        // not: Exp.boolBin excluded every legacy record, so the store sent one row where the engine
        // kept two -- silent loss in the mechanism whose contract is that it never loses anything.
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 1L),
                new Bin("order_id", 1L),
                new Bin("shipped", true));
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 2L),
                new Bin("order_id", 2L),
                new Bin("shipped", 1L));
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 3L),
                new Bin("order_id", 3L),
                new Bin("shipped", 0L));
        AerospikeSourcePlugin plugin = source(Map.of("schema", "order_id:INT64,shipped:BOOLEAN"));

        List<Object[]> shipped = drain(
                plugin,
                null,
                new ReadRequest(List.of(new ReadRequest.Filter("shipped", ReadRequest.Comparison.EQ, true))));
        assertThat(shipped.stream().map(row -> (Long) row[0]).sorted().toList())
                .as("the boolean particle and the legacy 1 are both true, because that is how the row is read")
                .containsExactly(1L, 2L);

        List<Object[]> notShipped = drain(
                plugin,
                null,
                new ReadRequest(List.of(new ReadRequest.Filter("shipped", ReadRequest.Comparison.EQ, false))));
        assertThat(notShipped.stream().map(row -> (Long) row[0]).toList()).containsExactly(3L);
        plugin.close();
    }

    @Test
    void aStringColumnOverANonStringBinIsPushedTheWayItIsRead() {
        // copyInto's default branch renders whatever the bin holds with String.valueOf, so a bin
        // holding the integer 42 declared STRING reads as "42". Exp.stringBin excluded it, and the
        // row the engine would have kept never arrived.
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 1L),
                new Bin("order_id", 1L),
                new Bin("ref", "42"));
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 2L),
                new Bin("order_id", 2L),
                new Bin("ref", 42L));
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 3L),
                new Bin("order_id", 3L),
                new Bin("ref", 7L));
        AerospikeSourcePlugin plugin = source(Map.of("schema", "order_id:INT64,ref:STRING"));

        List<Object[]> rows = drain(
                plugin, null, new ReadRequest(List.of(new ReadRequest.Filter("ref", ReadRequest.Comparison.EQ, "42"))));

        assertThat(rows.stream().map(row -> (Long) row[0]).sorted().toList())
                .as("both records read as \"42\"; neither may be filtered out by the store")
                .containsExactly(1L, 2L);
        plugin.close();
    }

    @Test
    void aLiteralThatDoesNotRoundTripMatchesOnlyTheStringBin() {
        // "007" parses as 7 and renders as "7", so a record holding the integer 7 does not read as
        // "007" -- and must not be matched by a filter for it. Parsing alone would have matched it.
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 1L),
                new Bin("order_id", 1L),
                new Bin("ref", "007"));
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 2L),
                new Bin("order_id", 2L),
                new Bin("ref", 7L));
        AerospikeSourcePlugin plugin = source(Map.of("schema", "order_id:INT64,ref:STRING"));

        List<Object[]> rows = drain(
                plugin,
                null,
                new ReadRequest(List.of(new ReadRequest.Filter("ref", ReadRequest.Comparison.EQ, "007"))));

        assertThat(rows.stream().map(row -> (Long) row[0]).toList()).containsExactly(1L);
        plugin.close();
    }

    @Test
    void anIntegerTooLargeForItsDeclaredColumnIsRefusedRatherThanTruncated() {
        // A bin holding 300 declared INT8 used to be cast to 44 and written into the row as though
        // that were the value. Aerospike stores every integer as 64 bits, so this is the same
        // disagreement between a record and its declaration that a string in an integer bin already
        // refused -- only the shape of the lie was different, and this one answered the query.
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 1L),
                new Bin("order_id", 1L),
                new Bin("small", 300L));
        AerospikeSourcePlugin plugin = source(Map.of("schema", "order_id:INT64,small:INT8"));

        assertThatThrownBy(() -> drain(plugin, null, ReadRequest.NOTHING))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("does not fit the INT8");
        plugin.close();
    }

    @Test
    void aFilterOnAnAbsentBinIsPushedAsBinExists() {
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 1L),
                new Bin("order_id", 1L),
                new Bin("amount", 5L));
        put(2, "DONE", 10);
        AerospikeSourcePlugin plugin = source(Map.of());

        List<Object[]> withStatus = drain(
                plugin,
                null,
                new ReadRequest(List.of(new ReadRequest.Filter("status", ReadRequest.Comparison.IS_NOT_NULL, null))));

        // Aerospike does not store absent bins, so "is not null" is "the bin exists" -- and record 1
        // simply has no status bin rather than a null one.
        assertThat(withStatus.stream().map(row -> (Long) row[0]).toList()).containsExactly(2L);
        plugin.close();
    }

    @Test
    void aPassIsReadAPageAtATimeAndBuffersNoMoreThanOnePollCanTake() {
        // SRC-7. The first pass of a fresh registration matches every record in the set, and it
        // used to be read whole into a list before the first row was handed on: the whole set on
        // the heap, whatever maxRecords said. Now each poll's maxRecords is the page the server is
        // asked for, and the client's partition filter resumes the pass where the page stopped.
        int records = 2_000;
        for (long id = 1; id <= records; id++) {
            put(id, "NEW", id);
        }
        AerospikeSourcePlugin plugin = source(Map.of());
        Collector out = new Collector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, ReadRequest.NOTHING)) {
            LutScanReader lut = (LutScanReader) reader;
            var before = reader.position();
            while (reader.poll(out, 100) > 0) {
                if (out.rows.size() < records) {
                    assertThat(reader.position())
                            .as("the offset stays put until the pass has been read to the end and drained")
                            .isEqualTo(before);
                }
            }

            assertThat(out.rows).hasSize(records);
            assertThat(out.rows.stream().map(row -> (Long) row[0]).distinct().count())
                    .as("a pass delivers each record once, across all its pages")
                    .isEqualTo(records);
            assertThat(lut.peakBuffered())
                    .as("the buffer never holds more than one poll can emit")
                    .isLessThanOrEqualTo(100);
            assertThat(lut.pageCount()).isGreaterThanOrEqualTo(records / 100);
            assertThat(lut.scanCount()).as("one pass, however many pages").isEqualTo(1);
            assertThat(reader.position()).as("a drained pass moves the offset").isNotEqualTo(before);
        }

        // Closed half way through a pass: there is no scanning thread to stop, so nothing waits.
        PartitionReader partial =
                plugin.createReader(plugin.partitions("orders").get(0), null, ReadRequest.NOTHING);
        assertThat(partial.poll(new Collector(plugin.schema()), 100)).isEqualTo(100);
        partial.close();
        plugin.close();
    }

    @Test
    void anAbsentBinArrivesAsNullRatherThanZero() {
        admin.put(
                new WritePolicy(),
                new Key(AerospikeContainer.NAMESPACE, "orders", 1L),
                new Bin("order_id", 1L),
                new Bin("amount", 5L));
        AerospikeSourcePlugin plugin = source(Map.of("schema", "order_id:INT64,status:STRING?,amount:INT64"));

        Collector out = new Collector(plugin.schema());
        PartitionReader reader = plugin.createReader(plugin.partitions("orders").get(0), null);
        while (reader.poll(out, 100) > 0) {
            // drain
        }

        assertThat(out.rows).hasSize(1);
        assertThat(out.rows.get(0)[1])
                .as("missing and empty are different facts about a record")
                .isNull();
        reader.close();
        plugin.close();
    }

    @Test
    void aDeletedRecordIsInvisibleAndTheCapabilitiesSaySo() {
        // The strategy's defining limitation, asserted rather than described. A view over this
        // source keeps serving deleted rows, and the only defence is that the engine is told.
        put(1, "NEW", 100);
        AerospikeSourcePlugin plugin = source(Map.of());
        assertThat(drain(plugin, null, ReadRequest.NOTHING)).hasSize(1);

        admin.delete(new WritePolicy(), new Key(AerospikeContainer.NAMESPACE, "orders", 1L));

        assertThat(drain(plugin, null, ReadRequest.NOTHING))
                .as("a delete is simply an absence, and absence is not an event")
                .isEmpty();
        assertThat(plugin.capabilities().emitsDeletes()).isFalse();
        assertThat(plugin.capabilities().guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        assertThat(plugin.capabilities().pushdown())
                .as("a server-side filter and named bins; never a partial aggregate (ADR-039 item 6)")
                .containsExactlyInAnyOrder(PushdownKind.FILTER, PushdownKind.PROJECT);
        plugin.close();
    }

    @Test
    void theSinkUpsertsByKeyAndDeletesOnRetraction() {
        StreamSchema schema = AerospikeSchemas.parse("results", SCHEMA);
        AerospikeSinkPlugin sink = new AerospikeSinkPlugin();
        sink.configure(new Ctx(
                "results",
                Map.of(
                        "hosts",
                        hosts,
                        "namespace",
                        AerospikeContainer.NAMESPACE,
                        "set",
                        "orders",
                        "schema",
                        SCHEMA,
                        "key.bin",
                        "order_id")));
        sink.open();

        RowFactory rows = new RowFactory(schema);
        sink.write(List.of(rows.row(1L, "NEW", 100L, 1), rows.row(2L, "DONE", 200L, 1)));
        assertThat(admin.get(null, new Key(AerospikeContainer.NAMESPACE, "orders", 1L)))
                .isNotNull();

        // Writing the same key again is the same end state, which is what makes a replay harmless.
        sink.write(List.of(rows.row(1L, "NEW", 100L, 1)));
        assertThat(admin.get(null, new Key(AerospikeContainer.NAMESPACE, "orders", 1L))
                        .getLong("amount"))
                .isEqualTo(100L);

        sink.write(List.of(rows.row(1L, "NEW", 100L, -1)));
        assertThat(admin.get(null, new Key(AerospikeContainer.NAMESPACE, "orders", 1L)))
                .as("a retraction is the query withdrawing the row; leaving it serves a wrong value")
                .isNull();

        assertThat(sink.capabilities().idempotentUpsert()).isTrue();
        assertThat(sink.capabilities().transactional())
                .as("Aerospike has no cross-record transaction this sink could use")
                .isFalse();
        rows.close();
        sink.close();
    }

    @Test
    void theLookupReadsByPrimaryKey() {
        put(7, "DONE", 700);
        AerospikeLookupPlugin lookup = new AerospikeLookupPlugin();
        lookup.configure(new Ctx(
                "orders",
                Map.of(
                        "hosts",
                        hosts,
                        "namespace",
                        AerospikeContainer.NAMESPACE,
                        "set",
                        "orders",
                        "schema",
                        SCHEMA,
                        "key.bin",
                        "order_id")));
        lookup.open();

        Collector out = new Collector(lookup.schema());
        assertThat(lookup.lookup(new Object[] {7L}, out)).isEqualTo(1);
        assertThat(out.rows.get(0)[2]).isEqualTo(700L);
        assertThat(out.rows.get(0)[0])
                .as("the key comes back as a column, not as a null")
                .isEqualTo(7L);

        assertThat(lookup.lookup(new Object[] {999L}, new Collector(lookup.schema())))
                .isZero();
        assertThat(lookup.lookup(new Object[] {null}, new Collector(lookup.schema())))
                .as("no record has a null primary key, so this is a round trip that cannot succeed")
                .isZero();
        lookup.close();
    }

    private static List<Object[]> drain(
            AerospikeSourcePlugin plugin, com.ash.messaging.pravaha.api.plugin.SourceOffset from, ReadRequest request) {
        Collector out = new Collector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), from, request)) {
            while (reader.poll(out, 100) > 0) {
                // drain
            }
        }
        return out.rows;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Collects rows as plain values. */
    private static final class Collector implements PartitionReader.RecordSink {
        private final List<Object[]> rows = new ArrayList<>();
        private final StreamSchema schema;

        Collector(StreamSchema schema) {
            this.schema = schema;
        }

        @Override
        public RowWriter beginRow() {
            return new ValueWriter(schema, rows::add);
        }
    }
}

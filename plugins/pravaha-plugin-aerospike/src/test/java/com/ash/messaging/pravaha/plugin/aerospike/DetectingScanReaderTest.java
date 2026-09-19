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

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.ScanCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code deletes: detect} against an in-memory store standing in for the client: what each pass
 * emits for an insert, an update, a delete and a re-insert, and that a reader resumed from any
 * position -- between passes, mid-pass, or behind rows emitted after it -- carries on so that the
 * emitted rows still sum to the store. The same scenarios run against a real server in {@code
 * AerospikeDeleteDetectionIT}.
 */
class DetectingScanReaderTest {

    private static final StreamSchema SCHEMA = AerospikeSchemas.parse("orders", "id:INT64,status:STRING,amount:INT64");

    @TempDir
    Path stateDir;

    /** Records by id, in insertion order; the fake client scans them in that order. */
    private final Map<Long, Map<String, Object>> store = new LinkedHashMap<>();

    private boolean failNextScan;

    private final IAerospikeClient client = (IAerospikeClient) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {IAerospikeClient.class}, (proxy, method, args) -> {
                if (!method.getName().equals("scanPartitions")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                ScanCallback callback = (ScanCallback) args[4];
                String[] bins = (String[]) args[5];
                List<Map.Entry<Long, Map<String, Object>>> records = new ArrayList<>(store.entrySet());
                for (int index = 0; index < records.size(); index++) {
                    if (failNextScan && index == records.size() / 2) {
                        failNextScan = false;
                        throw new AerospikeException(ResultCode.TIMEOUT, "scan timed out half way");
                    }
                    Map<String, Object> values =
                            new HashMap<>(records.get(index).getValue());
                    if (bins != null) {
                        values.keySet().retainAll(List.of(bins));
                    }
                    callback.scanCallback(
                            new Key("test", "orders", records.get(index).getKey()), new Record(values, 1, 0));
                }
                return null;
            });

    private void put(long id, String status, long amount) {
        store.put(id, Map.of("id", id, "status", status, "amount", amount));
    }

    private DetectingScanReader reader(SourceOffset from) {
        return reader(from, 1_000_000, ReadRequest.NOTHING);
    }

    private DetectingScanReader reader(SourceOffset from, long maxKeys, ReadRequest request) {
        return new DetectingScanReader(
                client, "test", "orders", SCHEMA, 0, 4096, 0, 0, 1000, 1000, from, request, stateDir, maxKeys);
    }

    /** The store as a Z-set of rows, which is what the emitted rows must sum to. */
    private Map<List<Object>, Long> storeAsView() {
        Map<List<Object>, Long> view = new HashMap<>();
        store.values()
                .forEach(bins -> view.merge(
                        java.util.Arrays.asList(bins.get("id"), bins.get("status"), bins.get("amount")),
                        1L,
                        Long::sum));
        return view;
    }

    private static List<Object> row(long id, String status, long amount) {
        return java.util.Arrays.asList(id, status, amount);
    }

    @Test
    void anInsertIsAddedOnceAndAPassThatFindsNothingNewEmitsNothing() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        try (DetectingScanReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            assertThat(out.rows).extracting(WeightedCollector.Emitted::weight).containsExactly(1L, 1L);

            out.clearRows();
            out.drain(reader);
            assertThat(out.rows)
                    .as("an unchanged store re-emits nothing: re-reading every row at +1 would count it twice")
                    .isEmpty();
            assertThat(out.view).isEqualTo(storeAsView());
        }
    }

    @Test
    void anUpdateRetractsTheWholeOldRowAndInsertsTheNewOne() {
        put(1, "NEW", 10);
        try (DetectingScanReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            long insertedAt = out.rows.get(0).eventTimeNanos();
            out.clearRows();

            put(1, "SHIPPED", 15);
            out.drain(reader);

            assertThat(out.rows).hasSize(2);
            assertThat(out.rows.get(0).values()).isEqualTo(row(1, "NEW", 10));
            assertThat(out.rows.get(0).weight()).isEqualTo(-1L);
            assertThat(out.rows.get(0).eventTimeNanos())
                    .as("a retraction carries the event time of the row it cancels, so it leaves the same window")
                    .isEqualTo(insertedAt);
            assertThat(out.rows.get(1).values()).isEqualTo(row(1, "SHIPPED", 15));
            assertThat(out.rows.get(1).weight()).isEqualTo(1L);
            assertThat(out.view).isEqualTo(storeAsView());
        }
    }

    @Test
    void aDeleteRetractsTheWholeRowAndAReinsertAddsItBack() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        try (DetectingScanReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            out.clearRows();

            store.remove(2L);
            out.drain(reader);
            assertThat(out.rows)
                    .containsExactly(new WeightedCollector.Emitted(
                            row(2, "NEW", 20), -1, out.rows.get(0).eventTimeNanos()));
            assertThat(out.view).isEqualTo(storeAsView());

            out.clearRows();
            put(2, "BACK", 25);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(row(2, "BACK", 25), 1L));
            assertThat(out.view).isEqualTo(storeAsView());
        }
    }

    @Test
    void aRestartBetweenPassesRetractsWhatWasDeletedWhileNothingWasReading() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        put(3, "NEW", 30);
        SourceOffset checkpoint;
        WeightedCollector out = new WeightedCollector(SCHEMA);
        try (DetectingScanReader reader = reader(null)) {
            out.drain(reader);
            checkpoint = reader.position();
        }
        store.remove(2L);
        put(3, "DONE", 30);

        WeightedCollector restored = WeightedCollector.restoredFrom(SCHEMA, out.view);
        try (DetectingScanReader reader = reader(checkpoint)) {
            restored.drain(reader);
        }
        assertThat(restored.rows)
                .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(row(2, "NEW", 20), -1L),
                        org.assertj.core.groups.Tuple.tuple(row(3, "NEW", 30), -1L),
                        org.assertj.core.groups.Tuple.tuple(row(3, "DONE", 30), 1L));
        assertThat(restored.view).isEqualTo(storeAsView());
    }

    @Test
    void aRestartMidPassEmitsExactlyTheRowsTheCheckpointHadNotSeen() {
        for (long id = 1; id <= 5; id++) {
            put(id, "NEW", id * 10);
        }
        SourceOffset checkpoint;
        WeightedCollector out = new WeightedCollector(SCHEMA);
        try (DetectingScanReader reader = reader(null)) {
            assertThat(reader.poll(out, 2)).isEqualTo(2);
            checkpoint = reader.position();
        }
        WeightedCollector restored = WeightedCollector.restoredFrom(SCHEMA, out.view);
        try (DetectingScanReader reader = reader(checkpoint)) {
            restored.drain(reader);
        }
        assertThat(restored.rows).hasSize(3).allMatch(emitted -> emitted.weight() == 1L);
        assertThat(restored.view).isEqualTo(storeAsView());
    }

    @Test
    void aRestartToACheckpointBehindLaterRowsReplaysOnlyUpToTheCheckpoint() {
        // The checkpoint is taken, then a pass retracts a row, then the process dies before the next
        // checkpoint. The restored view is the checkpoint's -- it still holds the row -- and so must
        // the restored reader be, or the retraction is lost for ever.
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        WeightedCollector out = new WeightedCollector(SCHEMA);
        SourceOffset checkpoint;
        Map<List<Object>, Long> viewAtCheckpoint;
        try (DetectingScanReader reader = reader(null)) {
            out.drain(reader);
            checkpoint = reader.position();
            viewAtCheckpoint = new HashMap<>(out.view);
            store.remove(1L);
            out.drain(reader);
            reader.position(); // a later position that never became a durable checkpoint
            assertThat(out.view).isEqualTo(storeAsView());
        }
        WeightedCollector restored = WeightedCollector.restoredFrom(SCHEMA, viewAtCheckpoint);
        try (DetectingScanReader reader = reader(checkpoint)) {
            restored.drain(reader);
        }
        assertThat(restored.rows)
                .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(row(1, "NEW", 10), -1L));
        assertThat(restored.view).isEqualTo(storeAsView());
    }

    @Test
    void aFailedScanEmitsNothingAndTheNextPassIsExact() {
        for (long id = 1; id <= 6; id++) {
            put(id, "NEW", id);
        }
        try (DetectingScanReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            out.clearRows();
            failNextScan = true;
            assertThatThrownBy(() -> reader.poll(out, 100))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("nothing from it was emitted");
            assertThat(out.rows).as("half a scan is not three deletions").isEmpty();
            store.remove(6L);
            out.drain(reader);
            assertThat(out.rows).extracting(WeightedCollector.Emitted::weight).containsExactly(-1L);
            assertThat(out.view).isEqualTo(storeAsView());
        }
    }

    @Test
    void aPassThatWouldHoldMoreThanTheCeilingIsRefusedBeforeAnyOfItIsEmitted() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        try (DetectingScanReader reader = reader(null, 2, ReadRequest.NOTHING)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            assertThat(out.rows).hasSize(2);
            out.clearRows();
            put(3, "NEW", 30);
            assertThatThrownBy(() -> reader.poll(out, 100))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e ->
                            assertThat(((PravahaException) e).errorCode()).isEqualTo(AerospikeErrors.DELETE_STATE_FULL))
                    .hasMessageContaining("deletes.max.keys (2)");
            assertThat(out.rows).isEmpty();
        }
    }

    @Test
    void aRestoreHoldingMoreThanTheCeilingIsRefusedWhenTheReaderIsCreated() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        SourceOffset checkpoint;
        try (DetectingScanReader reader = reader(null)) {
            new WeightedCollector(SCHEMA).drain(reader);
            checkpoint = reader.position();
        }
        assertThatThrownBy(() -> reader(checkpoint, 1, ReadRequest.NOTHING))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(AerospikeErrors.DELETE_STATE_FULL));
    }

    @Test
    void aCheckpointWhoseStateIsGoneIsRefusedRatherThanGuessedAt() throws Exception {
        put(1, "NEW", 10);
        SourceOffset checkpoint;
        try (DetectingScanReader reader = reader(null)) {
            new WeightedCollector(SCHEMA).drain(reader);
            checkpoint = reader.position();
            deleteTree(reader.emittedRows().directory());
        }
        assertThatThrownBy(() -> reader(checkpoint))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(AerospikeErrors.DELETE_STATE_FAILED))
                .hasMessageContaining("is gone");
    }

    @Test
    void anOffsetFromTheOtherModeIsRefusedEitherWay() {
        assertThatThrownBy(() -> reader(new SourceOffset("lut=123")))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(AerospikeErrors.BAD_CONFIGURATION));
        assertThatThrownBy(() -> reader(new SourceOffset("token=5")))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(AerospikeErrors.MALFORMED_OFFSET));
        assertThatThrownBy(() -> new LutScanReader(
                        client,
                        "test",
                        "orders",
                        SCHEMA,
                        0,
                        4096,
                        0,
                        0,
                        1000,
                        1000,
                        new SourceOffset(DetectingScanReader.PREFIX + "abc/1"),
                        ReadRequest.NOTHING))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(AerospikeErrors.BAD_CONFIGURATION));
    }

    @Test
    void aDurableCheckpointReleasesTheParentAndTheSnapshotsNothingNeeds() throws Exception {
        put(1, "NEW", 10);
        SourceOffset checkpoint;
        Path parentDir;
        try (DetectingScanReader reader = reader(null)) {
            new WeightedCollector(SCHEMA).drain(reader);
            checkpoint = reader.position();
            parentDir = reader.emittedRows().directory();
        }
        try (DetectingScanReader reader = reader(checkpoint)) {
            reader.emittedRows().snapshotAtLeastEvery(1);
            WeightedCollector out = new WeightedCollector(SCHEMA);
            for (long id = 2; id <= 6; id++) {
                put(id, "NEW", id);
                out.drain(reader);
            }
            SourceOffset latest = reader.position();
            assertThat(parentDir)
                    .as("the parent stays until a checkpoint naming this reader is durable")
                    .exists();
            reader.checkpointed(latest);
            assertThat(parentDir).doesNotExist();
            try (var files = Files.list(reader.emittedRows().directory())) {
                for (Path file : files.toList()) {
                    assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                                    Files.getPosixFilePermissions(file)))
                            .as("%s holds the source's rows, so only its owner may read it", file.getFileName())
                            .isEqualTo("rw-------");
                }
            }
            try (var files = Files.list(reader.emittedRows().directory())) {
                assertThat(files.map(file -> file.getFileName().toString())
                                .filter(name -> name.startsWith("snap-"))
                                .toList())
                        .as("only the snapshot under the durable checkpoint is kept")
                        .hasSize(1);
            }
        }
    }

    @Test
    void aProjectionRemembersOnlyTheProjectedColumnsAndStillRetractsExactly() {
        put(1, "NEW", 10);
        put(2, "NEW", 20);
        ReadRequest request = new ReadRequest(List.of(), List.of("id", "amount"), List.of());
        try (DetectingScanReader reader = reader(null, 100, request)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            out.clearRows();
            put(1, "SHIPPED", 10);
            out.drain(reader);
            assertThat(out.rows)
                    .as("a change to a column nothing reads is not a change to the rows this source emits")
                    .isEmpty();
            store.remove(2L);
            out.drain(reader);
            assertThat(out.rows).extracting(WeightedCollector.Emitted::weight).containsExactly(-1L);
            assertThat(out.view).hasSize(1);
        }
    }

    /**
     * The property all of the above are instances of, over random histories: after every pass the
     * rows emitted -- through any number of restarts, each from a checkpoint taken at a random point,
     * mid-pass included, and each restoring the view to what it was at that checkpoint -- sum to the
     * store exactly.
     */
    @Test
    void emittedRowsSumToTheStoreThroughRandomWritesAndRestarts() {
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            store.clear();
            WeightedCollector out = new WeightedCollector(SCHEMA);
            DetectingScanReader reader = reader(null);
            SourceOffset checkpoint = reader.position();
            Map<List<Object>, Long> viewAtCheckpoint = new HashMap<>();
            for (int round = 0; round < 40; round++) {
                for (int write = random.nextInt(6); write > 0; write--) {
                    long id = random.nextInt(12);
                    switch (random.nextInt(3)) {
                        case 0 -> store.remove(id);
                        default -> put(id, random.nextBoolean() ? "NEW" : "DONE", random.nextInt(3));
                    }
                }
                int action = random.nextInt(4);
                if (action == 0) {
                    reader.poll(out, 1 + random.nextInt(3)); // part of a pass, then a checkpoint
                    checkpoint = reader.position();
                    viewAtCheckpoint = new HashMap<>(out.view);
                    reader.checkpointed(checkpoint);
                } else if (action == 1) {
                    reader.close(); // died; restore from the last durable checkpoint
                    reader = reader(checkpoint);
                    out = WeightedCollector.restoredFrom(SCHEMA, viewAtCheckpoint);
                }
                out.drain(reader);
                assertThat(out.view).as("seed " + seed + " round " + round).isEqualTo(storeAsView());
            }
            reader.close();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}

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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A registered query's view, through a checkpoint and a restart, holds the values it held before
 * (VIEW-2).
 *
 * <p>The view snapshot a checkpoint carries wrote every integral number as a {@code long} and a
 * decimal through {@code longValue()}. A query keyed by an {@code INT32} column restarted with a
 * view of {@code Long} keys, and the engine went on writing {@code Integer} ones: the first row for
 * an existing key after the restart was a second row for it.
 */
class ViewCheckpointTypesTest {

    private static final StreamSchema ITEMS = StreamSchema.builder("items")
            .field("id", Types.int32())
            .field("price", Types.decimal(12, 2))
            .build();

    private static final StreamSchema TICKS = StreamSchema.builder("ticks")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @TempDir
    Path root;

    private RowArena arena;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    @Test
    void aViewKeyedByAnInt32ColumnHoldsOneRowPerKeyAcrossARestart() {
        QueryRegistry first = registry(ITEMS);
        RegisteredQuery query = first.register("prices", "SELECT id, price FROM items", List.of(0), DANA);
        item(query, 1, "10.25");
        item(query, 2, "20.50");
        query.commit();
        checkpointerOf(query).checkpointNow();
        first.close();
        registries.remove(first);

        QueryRegistry second = registry(ITEMS);
        RegisteredQuery restarted = second.register("prices", "SELECT id, price FROM items", List.of(0), DANA);
        assertThat(restarted.view().size())
                .as("the view came back from the checkpoint")
                .isEqualTo(2);
        assertThat(restarted.view().get(2).values())
                .as("a point lookup by the INT32 key finds the restored row, decimal whole: the restored key was "
                        + "a Long, which an Integer lookup never matched, and longValue() made 20.50 into 20")
                .hasValueSatisfying(values -> assertThat(values[1]).isEqualTo(new BigDecimal("20.50")));

        item(restarted, 1, "11.75");
        restarted.commit();

        assertThat(restarted.view().size())
                .as("one row per key: a restored key was a Long, and the Integer the engine wrote next was a "
                        + "second key beside it")
                .isEqualTo(2);
        Object[] row = restarted.view().get(1).values().orElseThrow();
        assertThat(row[0]).isInstanceOf(Integer.class);
        assertThat(row[1]).isEqualTo(new BigDecimal("11.75"));
    }

    @Test
    void aViewSnapshotInTheOldFormatIsRefusedBeforeAnyOperatorStateIsRestored() throws Exception {
        String sql = "SELECT user_id, SUM(amount) AS total FROM ticks "
                + "GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)";
        QueryRegistry first = registry(TICKS);
        RegisteredQuery query = first.register("spend", sql, List.of(0), DANA);
        tick(query, "u1", 100, 1_000_000_000L);
        query.commit();
        Checkpoint taken = checkpointerOf(query).checkpointNow();
        assertThat(taken.operatorState()).containsKeys("lane-0", QueryExecution.SERVED_VIEW_STATE);
        first.close();
        registries.remove(first);

        // The same checkpoint, with its view written as the unversioned format wrote it: an empty
        // view at frontier 0. The open window's 100 is in lane-0, untouched.
        java.io.ByteArrayOutputStream versionOne = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(versionOne)) {
            out.writeLong(0);
            out.writeInt(0);
        }
        Map<String, byte[]> state = new HashMap<>(taken.operatorState());
        state.put(QueryExecution.SERVED_VIEW_STATE, versionOne.toByteArray());
        Path directory;
        try (var dirs = Files.list(root)) {
            directory = dirs.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        new FileCheckpointStore(directory)
                .store(new Checkpoint(taken.id(), taken.timestampNanos(), taken.offsets(), state));

        // The checkpoint cannot be used, so the query starts from its sources -- which deliver the
        // 100 again. Had the lane's state been restored before the view was refused, this is 200.
        QueryRegistry second = registry(TICKS);
        RegisteredQuery restarted = second.register("spend", sql, List.of(0), DANA);
        tick(restarted, "u1", 100, 1_000_000_000L);
        restarted.advanceWatermark(11_000_000_000L);

        assertThat(restarted.view().get("u1").values().orElseThrow()[1])
                .as("the replay counted once: the refused checkpoint restored no operator state either")
                .isEqualTo(100L);
    }

    private QueryRegistry registry(StreamSchema stream) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), stream)
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

    private void item(RegisteredQuery query, int id, String price) {
        BigDecimal decimal = new BigDecimal(price);
        RowLayout layout = RowLayout.of(ITEMS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setInt(0, id);
        writer.setDecimal(1, Decimals.high(decimal, 2), Decimals.low(decimal, 2));
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private void tick(RegisteredQuery query, String user, long amount, long tsNanos) {
        RowLayout layout = RowLayout.of(TICKS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, tsNanos);
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}

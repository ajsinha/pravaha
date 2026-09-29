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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RESTOREPART-1 through the registry: a checkpoint whose view is refused after every lane was
 * restored starts the query from nothing, and says so.
 *
 * <p>The view's header is read before any lane (VIEW-2's check), so a view whose header is sound and
 * whose body is not got past it, the lane's windows came back, and the view refused -- after which the
 * registry started the query from its sources beside those windows, and the replay counted them twice.
 */
class PartialRestoreRegistryTest {

    private static final StreamSchema TICKS = StreamSchema.builder("ticks")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final String SQL =
            "SELECT user_id, SUM(amount) AS total FROM ticks GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)";

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
    void aViewRefusedAfterTheLanesStartsTheQueryFromNothingAndRecordsWhy() throws Exception {
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("spend", SQL, List.of(0), DANA);
        tick(query, "u1", 100, 1_000_000_000L);
        query.commit();
        Checkpoint taken = checkpointerOf(query).checkpointNow();
        byte[] view = taken.operatorState().get(QueryExecution.SERVED_VIEW_STATE);
        first.close();
        registries.remove(first);

        // A sound header, a body cut short: past the check that runs before the lanes, refused after.
        Map<String, byte[]> state = new HashMap<>(taken.operatorState());
        state.put(QueryExecution.SERVED_VIEW_STATE, Arrays.copyOf(view, 12));
        Path directory;
        try (var dirs = Files.list(root)) {
            directory = dirs.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        new FileCheckpointStore(directory)
                .store(new Checkpoint(taken.id(), taken.timestampNanos(), taken.offsets(), state));

        QueryRegistry second = registry();
        RegisteredQuery restarted = second.register("spend", SQL, List.of(0), DANA);
        tick(restarted, "u1", 100, 1_000_000_000L);
        restarted.advanceWatermark(11_000_000_000L);

        assertThat(restarted.view().get("u1").values().orElseThrow()[1])
                .as("the replay counted once: the lane's restored windows were put back to empty")
                .isEqualTo(100L);
        assertThat(restarted.checkpointFailures()).isEqualTo(1);
        assertThat(restarted.lastCheckpointFailure())
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("checkpoint " + taken.id())
                        .contains("could not be restored")
                        .contains("'spend'"));
    }

    private QueryRegistry registry() {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TICKS)
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

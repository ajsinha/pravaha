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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SHAREDLOSS-1: a shared computation's state outlives any one of its names. Two names asking the same
 * question share one computation, which checkpoints into the directory of the name that started it;
 * dropping that name used to leave the survivor pointing, after a restart, at its own empty directory.
 */
class SharedComputationRestartTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private static final String SQL = "SELECT user_id, amount FROM txn WHERE amount > 0";

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

    private QueryRegistry registry() {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .checkpointingTo(
                        root.resolve("checkpoints"),
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registry.journalTo(new RegistryJournal(root.resolve("registry.journal")));
        registries.add(registry);
        return registry;
    }

    private QueryRegistry recovered() {
        QueryRegistry registry = registry();
        QueryRegistry.Recovery recovery = registry.recover(owner -> Optional.of(DANA));
        assertThat(recovery.refused()).isEmpty();
        return registry;
    }

    private void restart(QueryRegistry registry) {
        registry.close();
        registries.remove(registry);
    }

    @Test
    void theSurvivingNameKeepsTheStateWhenTheStartingNameIsDropped() {
        QueryRegistry first = registry();
        RegisteredQuery shared = first.register("alias_a", SQL, List.of(0), DANA);
        assertThat(first.register("alias_b", SQL, List.of(0), DANA)).isSameAs(shared);
        txn(shared, "u1", 1, 1);
        txn(shared, "u2", 2, 2);
        shared.commit();
        first.drop("alias_a");
        checkpointerOf(shared).checkpointNow();
        restart(first);

        QueryRegistry second = recovered();
        assertThat(second.find("alias_a")).isEmpty();
        RegisteredQuery survivor = second.find("alias_b").orElseThrow();
        assertThat(rows(survivor))
                .as("it came back from the computation's own checkpoint, not its name's empty directory")
                .containsExactly(List.of("u1", 1L), List.of("u2", 2L));

        // And it keeps checkpointing there across a second restart, and is dropped cleanly.
        txn(survivor, "u3", 3, 3);
        survivor.commit();
        checkpointerOf(survivor).checkpointNow();
        restart(second);
        QueryRegistry third = recovered();
        assertThat(rows(third.find("alias_b").orElseThrow())).hasSize(3);
        third.drop("alias_b");
        assertThat(root.resolve("checkpoints").resolve("alias_a")).doesNotExist();
    }

    @Test
    void droppingTheLaterNameOrNoneStillRestores() {
        QueryRegistry first = registry();
        RegisteredQuery shared = first.register("alias_a", SQL, List.of(0), DANA);
        first.register("alias_b", SQL, List.of(0), DANA);
        first.register("alias_c", SQL, List.of(0), DANA);
        txn(shared, "u1", 1, 1);
        shared.commit();
        first.drop("alias_b");
        checkpointerOf(shared).checkpointNow();
        restart(first);

        QueryRegistry second = recovered();
        assertThat(rows(second.find("alias_a").orElseThrow())).containsExactly(List.of("u1", 1L));
        assertThat(second.find("alias_c").orElseThrow())
                .isSameAs(second.find("alias_a").orElseThrow());
        // Now the starting name: two survivors, both re-homed; whichever restarts first finds the state.
        second.drop("alias_a");
        restart(second);
        QueryRegistry third = recovered();
        assertThat(rows(third.find("alias_c").orElseThrow())).containsExactly(List.of("u1", 1L));
    }

    @Test
    void theRehomingRecordReplaysInPlaceAndRefusesNothing() throws Exception {
        RegistryJournal journal = new RegistryJournal(root.resolve("standalone.journal"));
        journal.recordRegistration(
                "one_view", SQL, List.of(0), "dana", com.ash.messaging.pravaha.serving.Retention.DEFAULT, List.of());
        journal.recordRegistration(
                "two_view", SQL, List.of(0), "dana", com.ash.messaging.pravaha.serving.Retention.DEFAULT, List.of());
        journal.recordIndexes("two_view", List.of(1));
        journal.recordDrop("one_view", List.of("two_view"), "one_view");
        List<RegistryJournal.Entry> live = journal.replay();
        assertThat(live).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("two_view");
            assertThat(entry.directory()).contains("one_view");
            assertThat(entry.indexed()).as("its indexes travel with it").containsExactly(1);
        });
        // A compaction keeps the directory: it is written back as the record that carries one.
        journal.compact(live);
        assertThat(journal.replay())
                .singleElement()
                .satisfies(entry -> assertThat(entry.directory()).contains("one_view"));
    }

    private static List<List<Object>> rows(RegisteredQuery query) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : query.view().scan()) {
            rows.add(List.of(row));
        }
        rows.sort(Comparator.comparing(Object::toString));
        return rows;
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

    private void txn(RegisteredQuery query, String user, long amount, long tsNanos) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, tsNanos);
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}

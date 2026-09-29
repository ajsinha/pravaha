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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DECKEYGROUP-1: a continuous unwindowed {@code GROUP BY} on a {@code DECIMAL} column -- a query over
 * a query's view (ADR-056) -- keeps every value apart by its whole unscaled value, through updates,
 * deletes, and a checkpoint and restart.
 *
 * <p>Before the fix the downstream failed with {@code PRV-3020} ("cannot group by a column of type
 * DECIMAL yet"), where the same key in a windowed aggregate had worked since WINDECKEY-1.
 */
@Timeout(120)
class DecimalGroupKeyChainTest {

    private static final StreamSchema FILLS = StreamSchema.builder("fills")
            .field("id", Types.string())
            .field("price", Types.decimal(30, 2))
            .field("qty", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String LATEST = "SELECT id, price, qty FROM fills";
    private static final String BY_PRICE = "SELECT price, SUM(qty) AS volume FROM latest GROUP BY price";

    @TempDir
    Path root;

    private RowArena arena;
    private final List<QueryRegistry> registries = new ArrayList<>();
    private long clock = 1_000_000_000L;

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
    void aGroupByOnADecimalColumnOverAViewKeepsEveryValueApart() {
        QueryRegistry registry = registry();
        RegisteredQuery latest = registry.register("latest", LATEST, List.of(0), DANA);
        RegisteredQuery byPrice = registry.register("by_price", BY_PRICE, List.of(0), DANA);

        fill(latest, "f1", "1.50", 10, 1);
        fill(latest, "f2", "2.75", 20, 1);
        fill(latest, "f3", "1.50", 5, 1);
        fill(latest, "f4", "10000000000000000000000000.00", 7, 1);
        latest.commit();
        awaitRows(byPrice, Map.of("1.50", 15L, "2.75", 20L, "10000000000000000000000000.00", 7L));

        // f2 moves price, f3 is deleted.
        fill(latest, "f2", "-2.75", 1, 1);
        fill(latest, "f3", "1.50", 5, -1);
        latest.commit();
        awaitRows(byPrice, Map.of("1.50", 10L, "-2.75", 1L, "10000000000000000000000000.00", 7L));
    }

    @Test
    void theDecimalKeysSurviveACheckpointAndRestart() {
        QueryRegistry first = registry();
        RegisteredQuery latest = first.register("latest", LATEST, List.of(0), DANA);
        RegisteredQuery byPrice = first.register("by_price", BY_PRICE, List.of(0), DANA);
        fill(latest, "f1", "1.50", 10, 1);
        fill(latest, "f2", "2.75", 20, 1);
        latest.commit();
        awaitRows(byPrice, Map.of("1.50", 10L, "2.75", 20L));
        checkpointerOf(latest).checkpointNow();
        checkpointerOf(byPrice).checkpointNow();
        first.close();
        registries.remove(first);

        QueryRegistry second = registry();
        RegisteredQuery latestAgain = second.register("latest", LATEST, List.of(0), DANA);
        RegisteredQuery byPriceAgain = second.register("by_price", BY_PRICE, List.of(0), DANA);
        awaitRows(byPriceAgain, Map.of("1.50", 10L, "2.75", 20L));

        fill(latestAgain, "f3", "2.75", 1, 1);
        latestAgain.commit();
        awaitRows(byPriceAgain, Map.of("1.50", 10L, "2.75", 21L));
    }

    private QueryRegistry registry() {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), FILLS)
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    /** Waits until the view holds exactly {@code expected}: price, as plain text, to volume. */
    private static void awaitRows(RegisteredQuery query, Map<String, Long> expected) {
        Supplier<Map<String, Long>> actual = () -> {
            Map<String, Long> rows = new TreeMap<>();
            for (Object[] row : query.view().scan()) {
                rows.put(((BigDecimal) row[0]).toPlainString(), ((Number) row[1]).longValue());
            }
            return rows;
        };
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!actual.get().equals(new TreeMap<>(expected)) && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        }
        assertThat(query.state()).isEqualTo(QueryState.RUNNING);
        assertThat(actual.get()).isEqualTo(new TreeMap<>(expected));
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

    private void fill(RegisteredQuery query, String id, String price, long qty, long weight) {
        long ts = clock += 1_000_000L;
        BigDecimal value = new BigDecimal(price);
        RowLayout layout = RowLayout.of(FILLS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id)
                .setDecimal(1, Decimals.high(value, 2), Decimals.low(value, 2))
                .setLong(2, qty)
                .setLong(3, ts);
        writer.weight(weight).eventTimestampNanos(ts).sequence(ts).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("fills", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}

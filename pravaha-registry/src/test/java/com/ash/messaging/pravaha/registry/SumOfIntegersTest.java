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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HLP-1: {@code SUM} over a narrower integer is a {@code BIGINT}, and runs.
 *
 * <p>The accumulators add in 64 bits and write the total with {@code setLong}. The planner took the
 * output column's type from Calcite, whose {@code SUM} keeps its argument's type -- so {@code
 * SUM(CASE WHEN tier = 'silver' THEN 1 ELSE 0 END)}, an {@code INTEGER}, planned an {@code INT32}
 * column, and the first row killed the lane with "field 0 ('silver') is INT32, not INT64".
 */
class SumOfIntegersTest {

    private static final StreamSchema S = StreamSchema.builder("s")
            .field("tier", Types.string())
            .field("n", Types.int32())
            .field("small", Types.int16())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), S);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    /**
     * Two rows, (silver, -2, -3) and (gold, 5, 7): negative on purpose, because reading a 32-bit
     * slot as 64 bits returns its bytes and the next field's, and a negative value is where the
     * missing sign extension shows.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "SELECT SUM(CASE WHEN tier = 'silver' THEN 1 ELSE 0 END) AS total FROM s | INT64 | 1",
                "SELECT SUM(n) AS total FROM s | INT64 | 3",
                "SELECT SUM(small) AS total FROM s | INT64 | 4",
                "SELECT SUM(n + 1) AS total FROM s | INT64 | 5",
                "SELECT MIN(n) AS total FROM s | INT32 | -2",
                "SELECT MAX(small) AS total FROM s | INT16 | 7",
                "SELECT AVG(n) AS total FROM s | INT32 | 1"
            })
    void anAggregateOfNarrowIntegersPlansAWidthItCanWriteAndAnswers(String sql, TypeName type, long expected) {
        RegisteredQuery query = registry.register("totals", sql, List.of(0), DANA);
        assertThat(query.outputSchema().field(0).type().typeName()).isEqualTo(type);

        feed(query, "silver", -2, (short) -3, 1L);
        feed(query, "gold", 5, (short) 7, 2L);
        commitUntilOneRow(query);

        Object total = query.view().scan().iterator().next()[0];
        assertThat(((Number) total).longValue()).isEqualTo(expected);
        assertThat(query.state()).isEqualTo(QueryState.RUNNING);
    }

    @Test
    void aWindowedSumOfAnIntIsABigintWithTheRightTotal() {
        RegisteredQuery query = registry.register(
                "windowed",
                "SELECT tier, SUM(n) AS total, MIN(n) AS lowest FROM s GROUP BY tier, TUMBLE(ts, INTERVAL '10' SECOND)",
                List.of(0),
                DANA);
        assertThat(query.outputSchema().field(1).type().typeName()).isEqualTo(TypeName.INT64);

        feed(query, "silver", -2, (short) 0, 1_000_000_000L);
        feed(query, "silver", 5, (short) 0, 2_000_000_000L);
        query.advanceWatermark(11_000_000_000L);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && query.view().size() == 0) {
            query.commit();
            sleep();
        }

        Object[] row = query.view().get("silver").values().orElseThrow();
        assertThat(row[1]).isEqualTo(3L);
        assertThat(((Number) row[2]).longValue()).isEqualTo(-2L);
        assertThat(query.state()).isEqualTo(QueryState.RUNNING);
    }

    @Test
    void theCaseCountsTheRowsItSelects() {
        RegisteredQuery query = registry.register(
                "silver", "SELECT SUM(CASE WHEN tier = 'silver' THEN 1 ELSE 0 END) AS silver FROM s", List.of(0), DANA);
        feed(query, "silver", 2, (short) 3, 1L);
        feed(query, "gold", 5, (short) 7, 2L);
        feed(query, "silver", 1, (short) 1, 3L);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && !hasRow(query, 2L)) {
            query.commit();
            sleep();
        }
        assertThat(hasRow(query, 2L)).as("two silver rows").isTrue();
    }

    private static boolean hasRow(RegisteredQuery query, long value) {
        for (Object[] row : query.view().scan()) {
            if (Long.valueOf(value).equals(row[0])) {
                return true;
            }
        }
        return false;
    }

    private static void commitUntilOneRow(RegisteredQuery query) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && query.view().size() == 0 && query.state() == QueryState.RUNNING) {
            query.commit();
            sleep();
        }
        assertThat(query.state()).as("the lane is still running").isEqualTo(QueryState.RUNNING);
        assertThat(query.view().size()).isEqualTo(1);
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void feed(RegisteredQuery query, String tier, int n, short small, long tsNanos) {
        RowLayout layout = RowLayout.of(S);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tier);
        writer.setInt(1, n);
        writer.setShort(2, small);
        writer.setLong(3, tsNanos);
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}

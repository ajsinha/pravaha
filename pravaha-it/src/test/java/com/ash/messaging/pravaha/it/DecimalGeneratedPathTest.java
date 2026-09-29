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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.codegen.FilterProjectStageGenerator;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.GeneratedChains;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CG-1: a filter comparing a DECIMAL column with a decimal literal runs on the generated path, and
 * answers exactly what the interpreter answers.
 *
 * <p>{@code ratio > 0.5} compiled to a comparison of two expressions, which the generator refuses,
 * so the whole filter-and-project chain ran interpreted -- correct, slower, and said so on the
 * query's {@code execution} line. A decimal column against a literal it can hold exactly is now a
 * typed comparison of 128-bit unscaled values, which the generator emits.
 */
class DecimalGeneratedPathTest {

    private static final StreamSchema TRADES = StreamSchema.builder("trades")
            .field("id", Types.int64())
            .field("ratio", Types.decimal(12, 2).withNullable(true))
            .field("big", Types.decimal(30, 2))
            .build();

    private static final int ROWS = 5_000;

    @AfterEach
    void uninstall() {
        GeneratedChains.install(null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT id, ratio FROM trades WHERE ratio > 0.5",
                "SELECT id FROM trades WHERE ratio <= -12.25 OR ratio = 3.10",
                "SELECT id FROM trades WHERE 0.5 < ratio AND ratio <> 1",
                "SELECT id, big FROM trades WHERE big >= 12345678901234567890.25",
                "SELECT id FROM trades WHERE big < -99999999999999999999.99 OR ratio IS NULL"
            })
    void aDecimalAgainstALiteralRunsGeneratedAndAnswersAsTheInterpreterDoes(String sql) throws Exception {
        Served interpreted = serve(sql, false);
        Served generated = serve(sql, true);

        assertThat(generated.paths())
                .as("the comparison must reach the generated path: %s", generated.paths())
                .anyMatch(path -> path.startsWith("generated:"));
        assertThat(interpreted.view()).as("the fixture must select some rows").isNotEmpty();
        assertThat(generated.view()).isEqualTo(interpreted.view());
    }

    @Test
    void aLiteralTheColumnCannotHoldExactlyIsComparedByTheGeneralPathAndStillRight() throws Exception {
        // 0.125 has three fractional digits and the column two: not rescaled, compared exactly.
        String sql = "SELECT id FROM trades WHERE ratio > 0.125";
        Served interpreted = serve(sql, false);
        Served generated = serve(sql, true);
        assertThat(generated.view()).isEqualTo(interpreted.view()).isNotEmpty();
    }

    record Served(List<String> view, List<String> paths) {}

    static Served serve(String sql, boolean generate) throws Exception {
        if (generate) {
            FilterProjectStageGenerator.install();
        } else {
            GeneratedChains.install(null);
        }
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TRADES);
                MemoryRegion region = MemoryAccess.best().allocate(1 << 16)) {
            RegisteredQuery query = registry.register("q", sql, List.of(0), Principal.ANONYMOUS);
            RowLayout layout = RowLayout.of(TRADES);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            Random random = new Random(7);
            for (int i = 0; i < ROWS; i++) {
                writer.begin(region, 0);
                writer.setLong(0, i);
                if (random.nextInt(9) == 0) {
                    writer.setNull(1);
                } else {
                    BigDecimal ratio = BigDecimal.valueOf(random.nextInt(4000) - 2000, 2);
                    writer.setDecimal(1, Decimals.high(ratio, 2), Decimals.low(ratio, 2));
                }
                BigDecimal big = new BigDecimal(new java.math.BigInteger(80, random))
                        .movePointLeft(2)
                        .multiply(BigDecimal.valueOf(random.nextBoolean() ? 1 : -1));
                writer.setDecimal(2, Decimals.high(big, 2), Decimals.low(big, 2));
                writer.weight(1).eventTimestampNanos(1_000L * i).sequence(i).commit();
                view.wrap(region, 0);
                while (!query.accept(view)) {
                    Thread.onSpinWait();
                }
            }
            assertThat(query.awaitApplied(Duration.ofMinutes(1))).isTrue();
            query.commit();
            List<String> rows = new ArrayList<>();
            for (Object[] row : query.view().scan()) {
                rows.add(Arrays.toString(row));
            }
            rows.sort(null);
            return new Served(rows, query.executionPaths());
        } finally {
            GeneratedChains.install(null);
        }
    }
}

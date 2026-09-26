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
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.codegen.FilterProjectStageGenerator;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.GeneratedChains;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A registered query serves the same view whether its filter and projection run generated or
 * interpreted (C-7).
 *
 * <p>The SQL is planned, registered and fed exactly as a node does it; the only difference between
 * the two runs is whether a generator is installed. The view is compared row for row, and the query's
 * own description is checked to say which path it took -- a comparison of two interpreted runs would
 * pass here too, so the generated run must be seen to be generated.
 */
class GeneratedQueryEquivalenceTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.int64())
            .field("user_id", Types.int64())
            .field("amount", Types.int64())
            .field("score", Types.int32())
            .field("ratio", Types.float64())
            .field("status", Types.string())
            .field("bonus", Types.int64().withNullable(true))
            .build();

    private static final String[] STATUSES = {"COMPLETED", "COMPLETE", "PENDING", "प्रवाह"};
    private static final int ROWS = 20_000;

    @AfterEach
    void uninstall() {
        GeneratedChains.install(null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT txn_id, user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 900",
                "SELECT txn_id, score, ratio FROM txn WHERE score < -3 OR score > 7",
                "SELECT txn_id, bonus FROM txn WHERE bonus IS NULL OR bonus >= 100",
                "SELECT txn_id, amount, bonus FROM txn WHERE status <> 'PENDING' AND score >= 0 AND amount < 500",
                "SELECT txn_id, status FROM txn WHERE amount > 10"
            })
    void aRegisteredQueryServesTheSameViewEitherWay(String sql) throws Exception {
        Served interpreted = serve(sql, false);
        Served generated = serve(sql, true);

        assertThat(interpreted.paths()).allMatch(path -> path.startsWith("interpreted:"));
        assertThat(generated.paths())
                .as("the generated run must actually have generated something, or this compares the "
                        + "interpreter with itself")
                .anyMatch(path -> path.startsWith("generated:"));
        assertThat(interpreted.view()).as("the fixture must select some rows").isNotEmpty();
        assertThat(generated.view()).isEqualTo(interpreted.view());
    }

    record Served(List<String> view, List<String> paths) {}

    static Served serve(String sql, boolean generate) throws Exception {
        if (generate) {
            FilterProjectStageGenerator.install();
        } else {
            GeneratedChains.install(null);
        }
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN);
                MemoryRegion region = MemoryAccess.best().allocate(1 << 16)) {
            RegisteredQuery query = registry.register("q", sql, List.of(0), Principal.ANONYMOUS);
            RowLayout layout = RowLayout.of(TXN);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            Random random = new Random(42);
            for (int i = 0; i < ROWS; i++) {
                writer.begin(region, 0);
                writer.setLong(0, i)
                        .setLong(1, random.nextInt(50))
                        .setLong(2, random.nextInt(1200))
                        .setInt(3, random.nextInt(21) - 10)
                        .setDouble(4, random.nextInt(10) == 0 ? Double.NaN : random.nextDouble())
                        .setString(5, STATUSES[random.nextInt(STATUSES.length)]);
                if (random.nextInt(3) == 0) {
                    writer.setNull(6);
                } else {
                    writer.setLong(6, random.nextInt(200));
                }
                writer.weight(1).eventTimestampNanos(1_000L * i).sequence(i).commit();
                view.wrap(region, 0);
                while (!query.accept(view)) {
                    Thread.onSpinWait();
                }
            }
            assertThat(query.awaitApplied(Duration.ofMinutes(1))).isTrue();
            query.advanceWatermark(Long.MAX_VALUE / 4);
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

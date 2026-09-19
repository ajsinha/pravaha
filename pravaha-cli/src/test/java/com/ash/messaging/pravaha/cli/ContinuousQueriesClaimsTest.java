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
package com.ash.messaging.pravaha.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HLP-13: claims in {@code docs/CONTINUOUS_QUERIES.md} that had drifted from the planner, each
 * measured here and then looked for in the document.
 *
 * <p>Every one of these was written down as working, or as refused for a different reason, or not
 * written down at all, and every one was found by somebody copying the document. Planning is cheap,
 * so the claim is checked against the planner rather than quoted from memory.
 */
class ContinuousQueriesClaimsTest {

    private static final String WINDOW = " FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))"
            + " GROUP BY user_id, window_start, window_end";

    private static StreamSchema txn() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("price", Types.float64())
                .field("ratio", Types.float32())
                .field("status", Types.string().withNullable(true))
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static void plan(String sql) {
        new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn()).plan(sql));
    }

    @Test
    void theRegistrationExampleInSectionThreePlans() {
        // It projected TUMBLE_END(...) AS hour: `hour` is reserved (PRV-2001), and TUMBLE_END needs
        // the grouped TUMBLE rather than the table function (PRV-2002). Neither could be registered.
        plan("SELECT user_id, window_end, SUM(amount) AS spend" + WINDOW);
        assertThat(continuousQueries())
                .contains("KEYED BY (user_id, window_end)")
                .doesNotContain("TUMBLE_END(event_time, INTERVAL '1' HOUR) AS hour");
    }

    @Test
    void aGuardedComparisonIsStillRefusedAsAProjectionAndIsTrueIsNot() {
        assertThatThrownBy(() -> plan("SELECT status IS NOT NULL AND status = 'ok' AS ok FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021");
        plan("SELECT (status = 'ok') IS TRUE AS ok FROM txn");
        plan("SELECT CASE WHEN status = 'ok' THEN TRUE ELSE FALSE END AS ok FROM txn");

        assertThat(continuousQueries())
                .contains("`(status = 'ok') IS TRUE`")
                .contains("**A guard does not make a comparison total, as far as the planner can tell.**");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUM", "AVG", "MIN", "MAX"})
    void everyValueReadingAggregateOverAFloatIsRefused(String aggregate) {
        for (String column : new String[] {"price", "ratio"}) {
            assertThatThrownBy(() -> plan("SELECT user_id, " + aggregate + "(" + column + ") AS v" + WINDOW))
                    .as("%s(%s)", aggregate, column)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020");
        }
        plan("SELECT user_id, " + aggregate + "(amount) AS v" + WINDOW);
        plan("SELECT user_id, COUNT(price) AS v" + WINDOW);

        assertThat(continuousQueries()).contains("`SUM`, `AVG`, `MIN` **and `MAX`** over a `FLOAT32`/`FLOAT64`");
    }

    @Test
    void aRenamedWindowColumnIsRefused() {
        assertThatThrownBy(() -> plan("SELECT user_id, window_end AS hour_end, COUNT(*) AS c" + WINDOW))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050");
        plan("SELECT user_id, window_end AS window_end, COUNT(*) AS c" + WINDOW);

        assertThat(continuousQueries())
                .contains("| Renaming a window column — `window_end AS hour_end` | ❌ | `PRV-2050`");
    }

    @Test
    void aRegistrationWithoutARetentionKeepsForever() {
        try (var registry = new com.ash.messaging.pravaha.registry.QueryRegistry(
                new com.ash.messaging.pravaha.serving.ViewCatalog(), txn())) {
            assertThat(registry.defaultRetention().isForever()).isTrue();
        }
        assertThat(continuousQueries()).contains("left out, the view keeps **forever**");
    }

    private static String continuousQueries() {
        try {
            return Files.readString(repoRoot().resolve("docs/CONTINUOUS_QUERIES.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read docs/CONTINUOUS_QUERIES.md", e);
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException(
                    "could not find the repository root from " + Path.of("").toAbsolutePath());
        }
        return path;
    }
}

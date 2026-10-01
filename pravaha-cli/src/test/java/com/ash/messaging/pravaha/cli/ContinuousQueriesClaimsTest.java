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
 * HLP-13: claims in {@code docs/guides/CONTINUOUS_QUERIES.md} that had drifted from the planner, each
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

    /**
     * The TY cluster's claims, each measured and then looked for in §11, §12, §13, §15 and §16.
     *
     * <p>Every one of these is a sentence the document did not have, or had wrong, while the
     * planner behaved a third way. They are grouped by the section they belong to rather than one
     * test per finding, because a reader checking the document reads a section at a time.
     */
    @Test
    void theProjectionSectionsClaimsAboutCastsCasesAndConcatenationHold() {
        // TY-23: a number cannot become text, whether it is written as a column or as a literal.
        for (String sql : new String[] {
            "SELECT user_id || amount AS c FROM txn",
            "SELECT user_id || 5 AS c FROM txn",
            "SELECT user_id || CAST(5 AS VARCHAR) AS c FROM txn",
            "SELECT CAST(5 AS VARCHAR) AS c FROM txn"
        }) {
            assertThatThrownBy(() -> plan(sql))
                    .as("%s", sql)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2021");
        }
        plan("SELECT CAST(NULL AS VARCHAR) AS c FROM txn");
        plan("SELECT user_id || '!' AS c FROM txn");

        // TY-4: a CASE whose branches disagree is refused with a code, as decimal arithmetic.
        assertThatThrownBy(() -> plan("SELECT CASE WHEN amount > 5 THEN 1 ELSE 1.5 END AS c FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021");
        // TY-4: and an approximate literal written with an exponent computes rather than throwing.
        plan("SELECT ratio / 3.0E0 AS c FROM txn");

        assertThat(continuousQueries())
                .contains("`CAST(NULL AS VARCHAR)` is accepted")
                .contains("**Every branch must produce the same type**")
                .contains("Both sides must be text");
    }

    @Test
    void theFilterSectionsClaimsAboutNullChecksAndIncomparableColumnsHold() {
        // TY-5: IS NULL over an expression, not only over a column.
        plan("SELECT txn_id FROM txn WHERE (CASE WHEN amount > 5 THEN status ELSE 'x' END) IS NULL");
        plan("SELECT txn_id FROM txn WHERE (amount * 2) IS NOT NULL");

        assertThat(continuousQueries())
                .contains("Over a column, and over any expression")
                .contains("Comparing a `BYTES`, `ARRAY`, `MAP` or `ROW` column");
    }

    @Test
    void theAggregationSectionsClaimAboutSummingTextHolds() {
        // TY-16: PRV-2020, the float-accumulator code, and not the decimal-arithmetic paragraph.
        for (String aggregate : new String[] {"SUM", "AVG"}) {
            assertThatThrownBy(() -> plan("SELECT " + aggregate + "(user_id) AS v" + WINDOW))
                    .as("%s over text", aggregate)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageNotContaining("DECIMAL arithmetic");
        }

        assertThat(continuousQueries()).contains("| `SUM` or `AVG` over a text column | ❌ | `PRV-2020`");
    }

    @Test
    void theSortingSectionsClaimThatEveryOrderByIsRefusedHolds() {
        // TY-20: the shape that planned and ran was the one the optimiser deleted first.
        for (String sql : new String[] {
            "SELECT txn_id FROM txn ORDER BY amount",
            "SELECT * FROM (SELECT txn_id FROM txn ORDER BY txn_id) x",
            "SELECT * FROM (SELECT txn_id FROM txn ORDER BY txn_id FETCH FIRST 2 ROWS ONLY) x"
        }) {
            assertThatThrownBy(() -> plan(sql))
                    .as("%s", sql)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020");
        }

        assertThat(continuousQueries()).contains("in every shape it can be written in");
    }

    @Test
    void theTypeSectionsClaimsAboutSchemaStringsAndNestedColumnsHold() {
        // TY-8 and TY-9: the code is a configuration one and the message names both places.
        assertThatThrownBy(() -> com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin.parseSchema(
                        "d", "id:INT64,amt:DECIMAL"))
                .hasMessageContaining("PRV-1028")
                .hasMessageContaining("stream 'd'")
                .hasMessageContaining("column 'amt'");
        // TY-7's fix, which §16 used to describe as still broken.
        assertThat(com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin.parseSchema(
                                "d", "id:INT64,amt:DECIMAL(10,2)")
                        .fieldCount())
                .isEqualTo(2);

        assertThat(continuousQueries())
                .contains("**`DECIMAL(p,s)` can be declared through the schema string**")
                .contains("**A schema string that will not parse is `PRV-1028`")
                .contains("| `PRV-1028` |");
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
            return Files.readString(repoRoot().resolve("docs/guides/CONTINUOUS_QUERIES.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read docs/guides/CONTINUOUS_QUERIES.md", e);
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/design/adr"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException(
                    "could not find the repository root from " + Path.of("").toAbsolutePath());
        }
        return path;
    }
}

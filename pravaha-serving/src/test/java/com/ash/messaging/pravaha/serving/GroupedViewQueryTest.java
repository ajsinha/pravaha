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
package com.ash.messaging.pravaha.serving;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code GROUP BY} over a maintained view.
 *
 * <p>The same SQL is refused in a continuous query and answered here, and the difference is the
 * input rather than the query. Over an endless stream a keyed aggregate holds one accumulator per
 * distinct key forever; a view read scans a finite set of rows and stops, so the state is bounded by
 * the scan and released with it.
 *
 * <p>This path was once opened without the operator behind it, and the result was
 * {@code SELECT DISTINCT tier} returning one row where there are two -- a wrong answer rather than a
 * failure, because the plan fell through to the unkeyed aggregate. Several of the assertions here
 * exist to make that specific mistake impossible to repeat.
 */
class GroupedViewQueryTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("region", Types.string())
            .field("total", Types.int64())
            .build();

    private ViewQuery queries;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", "emea", 300L}, 1, 100);
        view.applyValues(new Object[] {"u2", "silver", "emea", 50L}, 1, 100);
        view.applyValues(new Object[] {"u3", null, "apac", 7L}, 1, 100);
        view.applyValues(new Object[] {"u4", "gold", "apac", 1200L}, 1, 100);
        view.applyValues(new Object[] {"u5", "gold", "emea", 40L}, 1, 100);
        view.commit(100);
        queries = new ViewQuery(new ViewCatalog().register(view));
    }

    private Map<Object, Long> grouped(String sql) {
        ViewQuery.Result result = queries.execute(sql);
        return result.rows().stream().collect(java.util.stream.Collectors.toMap(row -> row[0], row -> (Long) row[1]));
    }

    @Test
    void aKeyedCountReturnsOneRowPerKey() {
        assertThat(grouped("SELECT tier, COUNT(*) FROM user_volume GROUP BY tier"))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("gold", 3L),
                        org.assertj.core.api.Assertions.entry("silver", 1L),
                        // NULL is a group of its own. SQL's GROUP BY differs from a comparison here:
                        // rows with no tier gather under one NULL rather than vanishing.
                        org.assertj.core.api.Assertions.entry(null, 1L));
    }

    @Test
    void aKeyedSumAddsWithinEachGroup() {
        assertThat(grouped("SELECT tier, SUM(total) FROM user_volume GROUP BY tier"))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("gold", 1540L),
                        org.assertj.core.api.Assertions.entry("silver", 50L),
                        org.assertj.core.api.Assertions.entry(null, 7L));
    }

    @Test
    void selectDistinctReturnsEveryDistinctValue() {
        // The regression. This returned a single row while the plan fell through to the unkeyed
        // aggregate, and it looked entirely plausible.
        ViewQuery.Result result = queries.execute("SELECT DISTINCT tier FROM user_volume");

        assertThat(result.rows().stream().map(row -> (String) row[0]).toList())
                .containsExactlyInAnyOrder("gold", "silver", null);
    }

    @Test
    void groupingByTwoColumnsKeysOnBoth() {
        ViewQuery.Result result =
                queries.execute("SELECT tier, region, COUNT(*) FROM user_volume GROUP BY tier, region");

        assertThat(result.rows().stream()
                        .map(row -> row[0] + "/" + row[1] + "=" + row[2])
                        .sorted()
                        .toList())
                .containsExactly("gold/apac=1", "gold/emea=2", "null/apac=1", "silver/emea=1");
    }

    @Test
    void minMaxAndAvgWorkPerGroup() {
        ViewQuery.Result result =
                queries.execute("SELECT tier, MIN(total), MAX(total), AVG(total) FROM user_volume GROUP BY tier");

        Object[] gold = result.rows().stream()
                .filter(row -> "gold".equals(row[0]))
                .findFirst()
                .orElseThrow();
        assertThat(gold[1]).isEqualTo(40L);
        assertThat(gold[2]).isEqualTo(1200L);
        // Integer division, matching SQL's AVG over an integer column: 1540 / 3.
        assertThat(gold[3]).isEqualTo(513L);
    }

    @Test
    void countDistinctWorksPerGroup() {
        ViewQuery.Result result =
                queries.execute("SELECT region, COUNT(DISTINCT tier) FROM user_volume GROUP BY region");

        assertThat(result.rows().stream()
                        .map(row -> row[0] + "=" + row[1])
                        .sorted()
                        .toList())
                // apac has gold and one NULL, and COUNT(DISTINCT) does not count NULL.
                .containsExactly("apac=1", "emea=2");
    }

    @Test
    void groupingByANumericColumnWorksToo() {
        ViewQuery.Result result = queries.execute("SELECT total, COUNT(*) FROM user_volume GROUP BY total");

        assertThat(result.size()).isEqualTo(5);
    }

    @Test
    void aFilterAppliesBeforeTheGrouping() {
        assertThat(grouped("SELECT tier, COUNT(*) FROM user_volume WHERE total > 45 GROUP BY tier"))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("gold", 2L),
                        org.assertj.core.api.Assertions.entry("silver", 1L));
    }

    @Test
    void havingFiltersTheGroups() {
        assertThat(grouped("SELECT tier, COUNT(*) FROM user_volume GROUP BY tier HAVING COUNT(*) > 1"))
                .containsOnly(org.assertj.core.api.Assertions.entry("gold", 3L));
    }

    @Test
    void aParameterBindsInAGroupedQuery() {
        ViewQuery.Prepared statement = queries.prepare(
                "SELECT tier, COUNT(*) FROM user_volume WHERE total > ? GROUP BY tier",
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS);
        ViewQuery.Result result = queries.execute(
                statement,
                com.ash.messaging.pravaha.sql.plan.BoundParameters.of(45L),
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS);

        assertThat(result.rows().stream()
                        .map(row -> row[0] + "=" + row[1])
                        .sorted()
                        .toList())
                .containsExactly("gold=2", "silver=1");
    }

    @Test
    void theSameQueryIsStillRefusedForAContinuousQuery() {
        // The distinction this whole operator rests on. Over a stream the key space never stops
        // growing, and no operator makes that acceptable -- so the refusal must survive.
        assertThatThrownBy(() -> new com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder()
                        .build(com.ash.messaging.pravaha.sql.SqlPlanner.withStreams(SCHEMA)
                                .plan("SELECT tier, COUNT(*) FROM user_volume GROUP BY tier")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050");
    }

    @Test
    void theRowOrderIsStableAcrossIdenticalReads() {
        // No ORDER BY, so the order is not meaningful -- but an answer that shuffles between two
        // identical calls is one somebody wastes an afternoon on.
        List<Object> first = queries.execute("SELECT tier, COUNT(*) FROM user_volume GROUP BY tier").rows().stream()
                .map(row -> row[0])
                .toList();
        List<Object> second = queries.execute("SELECT tier, COUNT(*) FROM user_volume GROUP BY tier").rows().stream()
                .map(row -> row[0])
                .toList();

        assertThat(first).isEqualTo(second);
    }
}

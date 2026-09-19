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
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code CREATE OR REPLACE CONTINUOUS QUERY}: the SQL spelling of a blue/green replacement.
 *
 * <p>It starts one and answers with the state it is in, which is {@code BACKFILLING} -- it does not
 * take the name from its readers and give it to something that has not caught up. That is the whole
 * reason the statement was refused by name until now, and the reason it can be accepted at last:
 * there is a mechanism behind it that does not lose anybody's answers.
 */
class ReplaceStatementTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    @Test
    void createOrReplaceStartsAReplacementAndSaysWhatStateItIsIn() {
        QueryRegistry registry = registry(40);
        run(
                registry,
                "CREATE CONTINUOUS QUERY orders KEYED BY (bucket) "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn");
        awaitRows(registry, "orders", 1);

        ViewQuery.Result result = run(
                registry,
                "CREATE OR REPLACE CONTINUOUS QUERY orders KEYED BY (bucket) "
                        + "WITH (backfill = 'history', cutover = 'manual') "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn");

        assertThat(result.schema()).isEqualTo(ContinuousQueryStatements.CREATED);
        Object[] row = result.rows().get(0);
        assertThat(row[0]).isEqualTo("orders");
        assertThat((String) row[1]).isIn("BACKFILLING", "CAUGHT_UP");
        assertThat(registry.replacements().of("orders")).isPresent();
        // The name still answers the version it answered before: a replacement is not a swap.
        assertThat(registry.find("orders").orElseThrow().outputSchema().fieldCount())
                .isEqualTo(2);

        await(() -> registry.replacements().of("orders").orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
        registry.replacements().cutOver("orders", DANA);
        assertThat(registry.find("orders").orElseThrow().outputSchema().fieldCount())
                .isEqualTo(3);
    }

    @Test
    void theOptionsAreReadFromTheStatementAndOneThatIsNotBuiltIsRefusedByName() {
        QueryRegistry registry = registry(10);
        run(
                registry,
                "CREATE CONTINUOUS QUERY orders KEYED BY (bucket) "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn");
        awaitRows(registry, "orders", 1);

        run(
                registry,
                "CREATE OR REPLACE CONTINUOUS QUERY orders KEYED BY (bucket) "
                        + "WITH (backfill = none, backfill.rate.limit = 250, cutover = auto, "
                        + "rollback.retention = 'PT5M') "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn");
        ReplacementOptions options =
                registry.replacements().of("orders").orElseThrow().options();
        assertThat(options.backfill()).isEqualTo(ReplacementOptions.Backfill.NONE);
        assertThat(options.rateLimit()).isEqualTo(250);
        assertThat(options.cutover()).isEqualTo(ReplacementOptions.Cutover.AUTO);
        assertThat(options.rollbackRetention()).isEqualTo(Duration.ofMinutes(5));

        // The design's other backfill options are named rather than ignored.
        QueryRegistry another = registry(10);
        run(
                another,
                "CREATE CONTINUOUS QUERY sales KEYED BY (bucket) "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn");
        awaitRows(another, "sales", 1);
        assertThatThrownBy(() -> run(
                        another,
                        "CREATE OR REPLACE CONTINUOUS QUERY sales KEYED BY (bucket) "
                                + "WITH (backfill.parallelism = 4) "
                                + "AS SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS n FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("backfill.parallelism");
    }

    @Test
    void createOrReplaceOfANameThatDoesNotExistIsAnOrdinaryCreate() {
        QueryRegistry registry = registry(5);
        ViewQuery.Result result = run(
                registry,
                "CREATE OR REPLACE CONTINUOUS QUERY orders KEYED BY (bucket) "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn");
        assertThat(result.rows().get(0)[1]).isEqualTo("RUNNING");
        assertThat(registry.replacements().of("orders")).isEmpty();
    }

    @Test
    void aReplacementMayNotMoveTheSinkOrTheRetentionWhileItIsAtIt() {
        QueryRegistry registry = registry(5);
        run(
                registry,
                "CREATE CONTINUOUS QUERY orders KEYED BY (bucket) "
                        + "AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn");
        awaitRows(registry, "orders", 1);

        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE OR REPLACE CONTINUOUS QUERY orders KEYED BY (bucket) WRITING TO warehouse "
                                + "AS SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS n FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("no sink");

        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE OR REPLACE CONTINUOUS QUERY orders KEYED BY (bucket) RETAIN FOR PT1H "
                                + "AS SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS n FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("keeps");
    }

    private ViewQuery.Result run(QueryRegistry registry, String sql) {
        return new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(ContinuousStatements.recognize(sql).orElseThrow(), DANA);
    }

    private QueryRegistry registry(int rows) {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < rows; i++) {
            log.append("u" + (i % 3), (long) (i + 1));
        }
        QueryRegistry registry = new QueryRegistry(
                        new com.ash.messaging.pravaha.serving.ViewCatalog(),
                        SecurityPolicy.PERMISSIVE,
                        AuditSink.NONE,
                        TXN)
                .feedingFrom(log);
        registries.add(registry);
        return registry;
    }

    private static void awaitRows(QueryRegistry registry, String name, int rows) {
        await(() -> registry.find(name).orElseThrow().view().size() == rows);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean())
                .as("the condition did not hold in 30 seconds")
                .isTrue();
    }
}

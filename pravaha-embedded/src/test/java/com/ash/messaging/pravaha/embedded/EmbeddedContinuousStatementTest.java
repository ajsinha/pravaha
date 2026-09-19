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
package com.ash.messaging.pravaha.embedded;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole loop in SQL, in process: {@code engine.query(sql)} registers, feeds a view that {@code
 * engine.query(sql)} then reads, lists, pauses, resumes and drops -- no Java registration call.
 */
class EmbeddedContinuousStatementTest {

    private static final String TXN = "user_id:STRING,amount:INT64";

    record Big(String userId, long amount) {}

    @Test
    void aQueryRegisteredInSqlIsReadInSqlAndManagedInSql() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();

            ViewQuery.Result created = engine.query("""
                    CREATE CONTINUOUS QUERY big_txn
                        KEYED BY (user_id)
                        RETAIN FOR INTERVAL '7' DAY
                    AS SELECT amount, user_id FROM txn WHERE amount > 100;
                    """);
            assertThat(created.rows().get(0)[0]).isEqualTo("big_txn");
            assertThat(created.rows().get(0)[1]).isEqualTo("RUNNING");
            assertThat(engine.find("big_txn").orElseThrow().view().keyOrdinals())
                    .containsExactly(1);
            assertThat(engine.find("big_txn").orElseThrow().view().retention())
                    .isEqualTo(Retention.ofAge(Duration.ofDays(7)));

            engine.push("txn", new Object[] {"u1", 300L}, new Object[] {"u2", 50L});
            assertThat(engine.query(Big.class, "SELECT user_id, amount FROM big_txn"))
                    .containsExactly(new Big("u1", 300L));

            assertThat(engine.query("SHOW CONTINUOUS QUERIES").rows())
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row[0]).isEqualTo("big_txn");
                        assertThat(row[5]).as("the key, by name").isEqualTo("user_id");
                        assertThat(row[7]).isEqualTo("PT168H");
                    });

            engine.query("PAUSE CONTINUOUS QUERY big_txn");
            assertThat(engine.find("big_txn").orElseThrow().state()).isEqualTo(QueryState.PAUSED);
            engine.query("resume continuous query big_txn");
            assertThat(engine.find("big_txn").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
            assertThat(engine.query("DROP CONTINUOUS QUERY big_txn").rows().get(0))
                    .containsExactly("big_txn", "DROPPED");
            assertThat(engine.queries()).isEmpty();
        }
    }

    @Test
    void aStatementTakesNoParametersAndAMalformedOneIsRefusedWithItsShape() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();

            assertThatThrownBy(() -> engine.query("DROP CONTINUOUS QUERY big_txn", "extra"))
                    .isInstanceOfSatisfying(
                            PravahaException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.STATEMENT_MALFORMED));
            assertThatThrownBy(() -> engine.query("CREATE CONTINUOUS QUERY big_txn AS SELECT user_id FROM txn"))
                    .isInstanceOfSatisfying(
                            PravahaException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.STATEMENT_MALFORMED))
                    .hasMessageContaining("KEYED BY");
            assertThat(engine.queries()).isEmpty();
        }
    }
}

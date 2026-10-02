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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two queries that differ in anything their answer depends on are two computations (FP-1).
 *
 * <p>Found writing the Aerospike tutorial, against a running node: the fingerprint was a hash of the
 * plan's <em>explain text</em>, and the explain text summarised. A join printed its keys and not its
 * time bound or whether it was LEFT; a projection printed its output names and not which columns
 * they came from; a windowed aggregate printed how many aggregates it had and not which. So
 * {@code SELECT txn_id AS v} shared with {@code SELECT amount AS v} and answered with amounts,
 * {@code MAX(amount)} shared with {@code SUM(amount)} and answered with sums, a 300-second join
 * shared with a 5-second one and held 29 pairs where 146 were true -- each time the second
 * registration read the first one's answer, and nothing said so.
 *
 * <p>Each case below is a pair of queries the fingerprint must tell apart, and each passed as
 * "shared" before the fix. The last two are pairs it must still join, because sharing is the feature.
 */
class PlanIdentityTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();
    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.int64())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();
    private static final StreamSchema PAYMENTS = StreamSchema.builder("payments")
            .field("payment_id", Types.int64())
            .field("order_id", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();
    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN, ORDERS, PAYMENTS);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private void distinct(String because, String a, String b, List<Integer> keys) {
        RegisteredQuery first = registry.register("q_one", a, keys, DANA);
        RegisteredQuery second = registry.register("q_two", b, keys, DANA);
        assertThat(second.fingerprint()).as(because).isNotEqualTo(first.fingerprint());
        assertThat(second).as(because).isNotSameAs(first);
    }

    private void shared(String because, String a, String b, List<Integer> keys) {
        RegisteredQuery first = registry.register("q_one", a, keys, DANA);
        RegisteredQuery second = registry.register("q_two", b, keys, DANA);
        assertThat(second.fingerprint()).as(because).isEqualTo(first.fingerprint());
    }

    private static String join(String kind, int seconds) {
        return "SELECT o.order_id, p.payment_id FROM orders o " + kind + " payments p ON p.order_id = o.order_id"
                + " AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '" + seconds + "' SECOND";
    }

    private static String windowed(String aggregate) {
        return "SELECT user_id, TUMBLE_START(event_time, INTERVAL '1' MINUTE) AS w, " + aggregate + " AS v FROM txn "
                + "GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id";
    }

    @Test
    void aJoinsTimeBoundIsPartOfItsIdentity() {
        distinct(
                "a 5-second and a 300-second join match different pairs",
                join("JOIN", 5),
                join("JOIN", 300),
                List.of(0));
    }

    @Test
    void anInnerAndALeftJoinAreDifferentQuestions() {
        distinct(
                "a LEFT JOIN also answers with the rows that never matched",
                join("JOIN", 5),
                join("LEFT JOIN", 5),
                List.of(0));
    }

    @Test
    void aProjectedNameIsNotItsSource() {
        distinct(
                "the same alias over different columns is a different answer",
                "SELECT txn_id AS k, amount AS v FROM txn",
                "SELECT txn_id AS k, txn_id AS v FROM txn",
                List.of(0));
    }

    @Test
    void aWindowedAggregatesFunctionIsPartOfItsIdentity() {
        distinct("MAX is not SUM", windowed("SUM(amount)"), windowed("MAX(amount)"), List.of(0, 1));
    }

    @Test
    void aWindowedAggregatesArgumentIsPartOfItsIdentity() {
        distinct("SUM(amount) is not SUM(txn_id)", windowed("SUM(amount)"), windowed("SUM(txn_id)"), List.of(0, 1));
    }

    @Test
    void theSameQuestionWrittenDifferentlyStillShares() {
        shared(
                "case and spacing are not the question",
                join("JOIN", 5),
                join("JOIN", 5)
                        .toLowerCase(Locale.ROOT)
                        .replace("interval", "INTERVAL")
                        .replace(" on ", "  ON  "),
                List.of(0));
    }

    @Test
    void aMirroredComparisonStillShares() {
        shared(
                "amount > 1000 and 1000 < amount ask the same thing",
                "SELECT txn_id, amount FROM txn WHERE amount > 1000",
                "SELECT txn_id, amount FROM txn WHERE 1000 < amount",
                List.of(0));
    }
}

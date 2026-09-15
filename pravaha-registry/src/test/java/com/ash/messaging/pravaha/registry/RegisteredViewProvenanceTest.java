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
 * Every view the registry builds knows what it reads.
 *
 * <p>SX-11's other half. {@code ViewQuery} refuses a read whose provenance the principal is not
 * entitled to — but a view whose provenance was never recorded has nothing behind it to check, and
 * would sail through exactly as it did before the fix. The check is only as good as the recording,
 * and the registry is the one place that must always do it, because a registered query is precisely
 * where the name and the data can be made to disagree.
 *
 * <p>This is the assertion {@code ServedView.derivedFrom}'s javadoc promises. It lives here rather
 * than beside the other provenance tests because {@code pravaha-serving} cannot see the registry.
 */
class RegisteredViewProvenanceTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema PAYROLL = StreamSchema.builder("payroll")
            .field("employee", Types.string())
            .field("salary", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN, PAYROLL);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void aRegisteredViewRecordsTheStreamItReads() {
        RegisteredQuery query = registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);

        assertThat(query.view().derivedFrom())
                .as("without this the read-path provenance check has nothing to check, and SX-11 is back")
                .containsExactly("txn");
    }

    @Test
    void aViewNamedNothingLikeItsStreamStillRecordsThatStream() {
        // The finding's own shape: the registered name shares no substring with the data it reads.
        RegisteredQuery query =
                registry.register("secret_pay", "SELECT employee, salary FROM payroll", List.of(0), DANA);

        assertThat(query.view().name()).isEqualTo("secret_pay");
        assertThat(query.view().derivedFrom())
                .as("the name is chosen by the registrant; this is the part that is not")
                .containsExactly("payroll");
    }
}

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
package com.ash.messaging.pravaha.sql.plan;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MINRETRACT-1: MIN and MAX over an input that retracts are refused at registration, by name. */
class RetractedExtremesTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.int64())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final String WINDOWED_MIN = "SELECT window_start, window_end, user_id, MIN(amount) AS m FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TXN).plan(sql));
    }

    @Test
    void aWindowedMinOverADeletingSourceIsRefusedNamingTheAggregateAndTheStream() {
        assertThatThrownBy(() -> RetractedExtremes.check(plan(WINDOWED_MIN), "txn"::equals))
                .isInstanceOf(PravahaException.class)
                .hasMessageStartingWith("PRV-2076")
                .hasMessageContaining("MIN(amount)")
                .hasMessageContaining("'txn'")
                .hasMessageContaining("COUNT, SUM or AVG");
    }

    @Test
    void anUnwindowedMaxOverADeletingSourceIsRefusedToo() {
        assertThatThrownBy(() -> RetractedExtremes.check(plan("SELECT MAX(amount) AS m FROM txn"), "txn"::equals))
                .hasMessageStartingWith("PRV-2076")
                .hasMessageContaining("MAX(amount)");
    }

    @Test
    void anAppendOnlySourceAndARetractingSumAreAccepted() {
        assertThatCode(() -> RetractedExtremes.check(plan(WINDOWED_MIN), stream -> false))
                .doesNotThrowAnyException();
        assertThatCode(() -> RetractedExtremes.check(plan("SELECT SUM(amount) AS s FROM txn"), "txn"::equals))
                .doesNotThrowAnyException();
    }

    @Test
    void aPushedRetractionIsCheckedAgainstTheStreamItReaches() {
        assertThat(RetractedExtremes.extremeOver(plan(WINDOWED_MIN), "txn")).contains("MIN(amount)");
        assertThat(RetractedExtremes.extremeOver(plan(WINDOWED_MIN), "other")).isEmpty();
    }
}

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
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NARROWCAST-1: an integer literal outside BIGINT is refused at registration, not compiled as its low
 * 64 bits ({@code CAST(9223372036854775808 AS BIGINT)} was {@code Long.MIN_VALUE}).
 */
class BigintLiteralRangeTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("num")
                .field("id", Types.int64())
                .field("a", Types.int64())
                .build();
    }

    private static void plan(String sql) {
        new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(sql));
    }

    @Test
    void aLiteralPastBigintIsRefusedWithACode() {
        assertThatThrownBy(() -> plan("SELECT id, CAST(9223372036854775808 AS BIGINT) AS x FROM num"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("9223372036854775808");
    }

    @Test
    void theEdgesOfBigintStillCompile() {
        assertThatCode(() -> plan("SELECT id, a + 9223372036854775807 AS x FROM num"))
                .doesNotThrowAnyException();
        assertThatCode(() -> plan("SELECT id FROM num WHERE a > -9223372036854775808"))
                .doesNotThrowAnyException();
    }
}

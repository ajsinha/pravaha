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
package com.ash.messaging.pravaha.pgwire;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.TypeName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three pieces of text and byte handling Power BI needed: {@link PgTrailingLimit}, {@link
 * PgPublicSchema} and {@link PgTypes#encodeBinary}. Each is small and each would be wrong in a way a
 * dashboard shows as a plausible number, so each edge is pinned here.
 */
class PgPowerBiTextTest {

    // ------------------------------------------------------------------ PgTrailingLimit

    @Test
    void aTrailingTopLevelLimitIsSplitOff() {
        assertThat(PgTrailingLimit.split("select a from v limit 1000001"))
                .contains(new PgTrailingLimit.Split("select a from v", 1_000_001));
        assertThat(PgTrailingLimit.split("select a from (select a from v) \"_\"\nLIMIT  4096 "))
                .contains(new PgTrailingLimit.Split("select a from (select a from v) \"_\"", 4096));
    }

    @Test
    void limitsThatAreNotTheLastTopLevelClauseAreLeftForThePlanner() {
        assertThat(PgTrailingLimit.split("select a from (select a from v limit 3) x"))
                .isEmpty();
        assertThat(PgTrailingLimit.split("select a from v limit 10 offset 5")).isEmpty();
        assertThat(PgTrailingLimit.split("select a from v limit all")).isEmpty();
        assertThat(PgTrailingLimit.split("select a from v limit $1")).isEmpty();
        assertThat(PgTrailingLimit.split("select a from v where note = 'limit 5'"))
                .isEmpty();
        assertThat(PgTrailingLimit.split("select \"limit\" from v")).isEmpty();
        assertThat(PgTrailingLimit.split("select speed_limit from v")).isEmpty();
    }

    @Test
    void theLimitCutsTheAnswerAndNeverPadsIt() {
        com.ash.messaging.pravaha.api.data.StreamSchema schema =
                com.ash.messaging.pravaha.api.data.StreamSchema.builder("t")
                        .field("a", com.ash.messaging.pravaha.api.data.Types.int64())
                        .build();
        List<Object[]> rows = new ArrayList<>();
        for (long i = 0; i < 5; i++) {
            rows.add(new Object[] {i});
        }
        com.ash.messaging.pravaha.serving.ViewQuery.Result all =
                new com.ash.messaging.pravaha.serving.ViewQuery.Result(schema, rows);
        assertThat(PgTrailingLimit.apply(all, 2).size()).isEqualTo(2);
        assertThat(PgTrailingLimit.apply(all, 0).size()).isZero();
        assertThat(PgTrailingLimit.apply(all, 1_000_001).size()).isEqualTo(5);
        assertThat(PgTrailingLimit.apply(all, PgTrailingLimit.NONE).size()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ PgPublicSchema

    @Test
    void thePublicQualifierIsDroppedAfterFromAndJoinOnly() {
        assertThat(PgPublicSchema.unqualify("select \"_\".\"a\" from \"public\".\"v\" \"_\""))
                .isEqualTo("select \"_\".\"a\" from \"v\" \"_\"");
        assertThat(PgPublicSchema.unqualify("SELECT * FROM public.v JOIN PUBLIC.w ON v.k = w.k"))
                .isEqualTo("SELECT * FROM v JOIN w ON v.k = w.k");
        assertThat(PgPublicSchema.unqualify("select a from (select a from \"public\".\"v\" \"_\") \"rows\""))
                .isEqualTo("select a from (select a from \"v\" \"_\") \"rows\"");
    }

    @Test
    void publicAnywhereElseIsLeftAlone() {
        String[] untouched = {
            "select public.a from v public",
            "select a from v where note = 'from public.x'",
            "select a from public_view",
            "select a from \"Public\".v",
            "select a from other.v",
        };
        for (String sql : untouched) {
            assertThat(PgPublicSchema.unqualify(sql)).isEqualTo(sql);
        }
    }

    // ------------------------------------------------------------------ binary numeric

    /** (ndigits, weight, sign, dscale, digits...) as PostgreSQL's numeric_send writes them. */
    private static List<Integer> numeric(String value) {
        ByteBuffer bytes = ByteBuffer.wrap(PgTypes.numericBinary(new BigDecimal(value)));
        List<Integer> shorts = new ArrayList<>();
        while (bytes.hasRemaining()) {
            shorts.add((int) bytes.getShort());
        }
        return shorts;
    }

    @Test
    void numericBinaryMatchesPostgresNumericSend() {
        assertThat(numeric("1200.00")).containsExactly(1, 0, 0, 2, 1200);
        assertThat(numeric("0.05")).containsExactly(1, -1, 0, 2, 500);
        assertThat(numeric("-205.18")).containsExactly(2, 0, 0x4000, 2, 205, 1800);
        assertThat(numeric("0")).containsExactly(0, 0, 0, 0);
        assertThat(numeric("0.00")).containsExactly(0, 0, 0, 2);
        assertThat(numeric("12345678.9")).containsExactly(3, 1, 0, 1, 1234, 5678, 9000);
        assertThat(numeric("1E+3")).containsExactly(1, 0, 0, 0, 1000);
        assertThat(numeric("10000")).containsExactly(1, 1, 0, 0, 1);
        assertThat(numeric("0.000000000000000000001")).containsExactly(1, -6, 0, 21, 1000);
    }

    @Test
    void binaryFixedWidthValuesAreBigEndian() {
        assertThat(PgTypes.encodeBinary(TypeName.INT16, (short) -2)).containsExactly(0xff, 0xfe);
        assertThat(PgTypes.encodeBinary(TypeName.INT32, 258)).containsExactly(0, 0, 1, 2);
        assertThat(PgTypes.encodeBinary(TypeName.BOOLEAN, true)).containsExactly(1);
        // 2000-01-01 is day zero of PostgreSQL's binary date.
        assertThat(PgTypes.encodeBinary(TypeName.DATE, 10_957)).containsExactly(0, 0, 0, 0);
        // One nanosecond before 2000-01-01 is the microsecond before it: truncated toward the past.
        assertThat(ByteBuffer.wrap(PgTypes.encodeBinary(TypeName.TIMESTAMP_LTZ, 946_684_799_999_999_999L))
                        .getLong())
                .isEqualTo(-1L);
        assertThat(PgTypes.encodeBinary(TypeName.STRING, null)).isNull();
    }
}

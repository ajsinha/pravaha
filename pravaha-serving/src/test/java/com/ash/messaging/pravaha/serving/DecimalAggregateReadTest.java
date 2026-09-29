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

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DECSUM-1: {@code SUM}, {@code MIN} and {@code MAX} over a {@code DECIMAL} column of a view answer
 * exactly, at the column's scale; {@code AVG} of one is refused by name, because a quotient of
 * decimals has no exact answer at a fixed scale (the rule {@code /} over decimals already follows).
 *
 * <p>Before the fix {@code SELECT SUM(avg_ticket) FROM rr} -- Power BI's DirectQuery shape -- failed
 * "field 0 ('a0') is DECIMAL, not INT64 in schema rr_projected_aggregated" with no code: the
 * accumulators read the 16-byte decimal slot with {@code getLong} and wrote the answer with {@code
 * setLong}.
 */
class DecimalAggregateReadTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("rr")
            .field("region", Types.string())
            .field("avg_ticket", Types.decimal(10, 2))
            .field("share", Types.float64())
            .field("big", Types.decimal(30, 0).withNullable(true))
            .build();

    private ViewQuery queries;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("rr", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"EMEA", new BigDecimal("222.74"), 0.25, BigDecimal.ONE}, 1, 100);
        view.applyValues(new Object[] {"APAC", new BigDecimal("-205.18"), 0.5, null}, 1, 100);
        view.applyValues(new Object[] {"AMER", new BigDecimal("0.05"), 1.5, new BigDecimal("1e25")}, 1, 100);
        view.commit(100);
        queries = new ViewQuery(new ViewCatalog().register(view));
    }

    @Test
    void theDirectQuerySumOfADecimalColumnIsExact() {
        ViewQuery.Result result = queries.execute("select sum(\"_\".\"avg_ticket\") from \"rr\" \"_\"");

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)[0]).isEqualTo(new BigDecimal("17.61"));
        // A sum keeps the column's scale and widens to the full 38 digits, as PostgreSQL's numeric
        // sum does not overflow the column it adds up.
        DecimalType type = (DecimalType) result.schema().field(0).type();
        assertThat(type.typeName()).isEqualTo(TypeName.DECIMAL);
        assertThat(type.scale()).isEqualTo(2);
        assertThat(type.precision()).isEqualTo(38);
    }

    @Test
    void minAndMaxOfADecimalColumnKeepItsType() {
        ViewQuery.Result result = queries.execute("SELECT MIN(avg_ticket), MAX(avg_ticket) FROM rr");

        assertThat(result.rows().get(0)).containsExactly(new BigDecimal("-205.18"), new BigDecimal("222.74"));
        assertThat(((DecimalType) result.schema().field(0).type()).scale()).isEqualTo(2);
    }

    @Test
    void aGroupedDecimalSumIsExactPerGroup() {
        ViewQuery.Result result =
                queries.execute("SELECT region, SUM(avg_ticket), MAX(avg_ticket) FROM rr GROUP BY region");

        assertThat(result.rows().stream()
                        .map(row -> row[0] + "=" + row[1] + "/" + row[2])
                        .sorted()
                        .toList())
                .containsExactly("AMER=0.05/0.05", "APAC=-205.18/-205.18", "EMEA=222.74/222.74");
    }

    @Test
    void aDecimalSumOverAnExpressionIsExact() {
        ViewQuery.Result result = queries.execute("SELECT SUM(avg_ticket * 3) FROM rr");

        assertThat(result.rows().get(0)[0]).isEqualTo(new BigDecimal("52.83"));
    }

    @Test
    void theAverageOfADecimalColumnIsRefusedByName() {
        assertThatThrownBy(() -> queries.execute("SELECT AVG(avg_ticket) FROM rr"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("avg_ticket");
    }

    @Test
    void aDecimalWiderThanSixtyFourBitsIsRefusedByNameNotWrapped() {
        // 10^25 has no 64-bit unscaled form; the accumulators are 64-bit, so the read is refused by
        // name rather than answered with the low half of the number.
        assertThatThrownBy(() -> queries.execute("SELECT SUM(big) FROM rr"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3020")
                .hasMessageContaining("big");
    }

    @Test
    void aFloatSumIsStillRefusedByNameNotFailed() {
        // The float refusal is a documented rule, not DECSUM-1's cause: every accumulator is an
        // integer. Named, coded and before any row is read.
        assertThatThrownBy(() -> queries.execute("SELECT SUM(share) FROM rr"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("share");
    }
}

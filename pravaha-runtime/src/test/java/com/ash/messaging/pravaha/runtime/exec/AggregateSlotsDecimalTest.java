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
package com.ash.messaging.pravaha.runtime.exec;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DECSUM-1: {@link AggregateSlots} reads a {@code DECIMAL} as its unscaled value, writes one back
 * sign-extended, refuses a value with no 64-bit unscaled form by name, and shows one at its scale in
 * an inspection.
 */
class AggregateSlotsDecimalTest {

    private static final StreamSchema SCHEMA =
            StreamSchema.builder("d").field("amount", Types.decimal(38, 2)).build();

    @Test
    void aDecimalRoundTripsThroughItsUnscaledValueWithItsSign() {
        for (String text :
                new String[] {"222.74", "-205.18", "0.00", "92233720368547758.07", "-92233720368547758.08"}) {
            BigDecimal value = new BigDecimal(text);
            try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 12, 2)) {
                BinaryRowView row = row(arena, value);
                long unscaled = AggregateSlots.read(row, 0, TypeName.DECIMAL, SCHEMA);
                assertThat(unscaled).isEqualTo(value.unscaledValue().longValueExact());

                BinaryRowView written = written(arena, unscaled);
                assertThat(Decimals.toBigDecimal(written.getDecimalHigh(0), written.getDecimalLow(0), 2))
                        .isEqualTo(value);
            }
        }
    }

    @Test
    void aDecimalWithNoSixtyFourBitUnscaledFormIsRefusedByName() {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 12, 2)) {
            BinaryRowView row = row(arena, new BigDecimal("92233720368547758.08"));
            assertThatThrownBy(() -> AggregateSlots.read(row, 0, TypeName.DECIMAL, SCHEMA))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3020")
                    .hasMessageContaining("'amount'");
        }
    }

    @Test
    void anInspectionShowsADecimalAtItsScale() {
        assertThat(AggregateSlots.text(22274L, SCHEMA, 0)).isEqualTo("222.74");
        assertThat(AggregateSlots.text(-5L, SCHEMA, 0)).isEqualTo("-0.05");
        StreamSchema integers =
                StreamSchema.builder("i").field("n", Types.int64()).build();
        assertThat(AggregateSlots.text(22274L, integers, 0)).isEqualTo("22274");
    }

    private static BinaryRowView row(RowArena arena, BigDecimal value) {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(0));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setDecimal(0, Decimals.high(value, 2), Decimals.low(value, 2));
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    private static BinaryRowView written(RowArena arena, long unscaled) {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(0));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        AggregateSlots.write(writer, 0, unscaled, TypeName.DECIMAL);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }
}

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Join key hashing and equality, tested here rather than through a join.
 *
 * <p>The equality check inside {@code JoinSide} confirms a candidate the hash produced, and with
 * 64-bit buckets there is no way to make a query produce a candidate that is not a match -- the
 * check is unreachable from above and becomes reachable when the index moves off-heap to a masked
 * table. So it is tested where it can be: directly.
 *
 * <p>The hash tests are the more important half. The two sides of a join carry the same key in
 * different columns, and a hash that picks up the ordinal rather than the value puts them in
 * different buckets -- a join that returns nothing at all, with no error and nothing to look at.
 */
class JoinKeysTest {

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4);

    private static final StreamSchema LEFT = StreamSchema.builder("l")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    /** Same key, different position: user_id is column 1 here and column 0 on the left. */
    private static final StreamSchema RIGHT = StreamSchema.builder("r")
            .field("country", Types.string())
            .field("user_id", Types.string())
            .build();

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    void theHashDependsOnTheValueRatherThanTheColumnItSitsIn() {
        RowView left = row(LEFT, "u1", 100L);
        RowView right = row(RIGHT, "IN", "u1");

        assertThat(JoinKeys.hash(left, new int[] {0}, LEFT))
                .as("the same key in different columns must land in the same bucket")
                .isEqualTo(JoinKeys.hash(right, new int[] {1}, RIGHT));
    }

    @Test
    void differentKeysHashDifferently() {
        // Not a guarantee -- collisions exist -- but a hash that maps everything to one bucket
        // turns every join into a linear scan and would pass every correctness test there is.
        assertThat(JoinKeys.hash(row(LEFT, "u1", 1L), new int[] {0}, LEFT))
                .isNotEqualTo(JoinKeys.hash(row(LEFT, "u2", 1L), new int[] {0}, LEFT));
    }

    @Test
    void aCompositeKeyDependsOnTheOrderOfItsColumns() {
        StreamSchema pair = StreamSchema.builder("p")
                .field("a", Types.string())
                .field("b", Types.string())
                .build();

        // ("x","y") and ("y","x") are different keys. A hash that adds its parts cannot tell them
        // apart, and the join then matches rows whose key columns are swapped.
        assertThat(JoinKeys.hash(row(pair, "x", "y"), new int[] {0, 1}, pair))
                .isNotEqualTo(JoinKeys.hash(row(pair, "y", "x"), new int[] {0, 1}, pair));
    }

    @Test
    void equalityComparesValuesAcrossDifferentOrdinals() {
        assertThat(JoinKeys.equal(row(LEFT, "u1", 1L), new int[] {0}, LEFT, row(RIGHT, "IN", "u1"), new int[] {1}))
                .isTrue();
        assertThat(JoinKeys.equal(row(LEFT, "u1", 1L), new int[] {0}, LEFT, row(RIGHT, "IN", "u2"), new int[] {1}))
                .isFalse();
    }

    @Test
    void nullIsNotEqualToNull() {
        StreamSchema nullable = StreamSchema.builder("n")
                .field("k", Types.string().withNullable(true))
                .build();
        RowView left = row(nullable, new Object[] {null});
        RowView right = row(nullable, new Object[] {null});

        assertThat(JoinKeys.equal(left, new int[] {0}, nullable, right, new int[] {0}))
                .as("SQL says NULL = NULL is unknown, so these rows do not join")
                .isFalse();
        assertThat(JoinKeys.isMatchable(left, new int[] {0})).isFalse();
    }

    @Test
    void aFloatingPointKeyIsRefusedWithTheReasonRatherThanQuietlyDroppingRows() {
        StreamSchema rates =
                StreamSchema.builder("rates").field("rate", Types.float64()).build();

        assertThatThrownBy(() -> JoinKeys.checkJoinable(rates, 0, "left"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3021")
                .hasMessageContaining("rounding");
    }

    private RowView row(StreamSchema schema, Object... values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                writer.setNull(i);
            } else if (values[i] instanceof Long longValue) {
                writer.setLong(i, longValue);
            } else {
                writer.setString(i, String.valueOf(values[i]));
            }
        }
        writer.weight(1).eventTimestampNanos(1).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }
}

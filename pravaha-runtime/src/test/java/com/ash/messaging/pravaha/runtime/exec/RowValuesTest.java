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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
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
 * Row identity, which decides whether a retraction cancels its insert.
 *
 * <p>It compares <strong>every</strong> column, not the key ones. So refusing a type here is not a
 * restriction on joining <em>by</em> that type -- it refuses every query that joins or retracts over
 * a row which merely carries one. A stream with a single binary column anywhere could not be joined
 * at all, and it failed with an uncoded {@code UnsupportedOperationException} the moment two rows on
 * one side shared a key, taking the query to FAILED mid-flight.
 */
class RowValuesTest {

    private static final StreamSchema WITH_BYTES = StreamSchema.builder("s")
            .field("id", Types.string())
            .field("payload", Types.bytes())
            .build();

    private record Rows(BinaryRowView left, BinaryRowView right) {}

    private static Rows twoRows(RowArena arena, StreamSchema schema, String id, byte[] a, byte[] b) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView[] views = new BinaryRowView[2];
        byte[][] payloads = {a, b};
        for (int i = 0; i < 2; i++) {
            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle), layout.rowSize(256));
            writer.setString(0, id)
                    .setBytes(1, payloads[i])
                    .weight(1L)
                    .eventTimestampNanos(0)
                    .sequence(i)
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            views[i] = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
        }
        return new Rows(views[0], views[1]);
    }

    @Test
    void twoRowsCarryingTheSameBytesAreTheSameRow() {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            // Deliberately not valid UTF-8: comparing binary by decoding it to text would call these
            // equal, because both decode to replacement characters.
            byte[] payload = {0x00, (byte) 0xFF, (byte) 0xC3, 0x28};
            Rows rows = twoRows(arena, WITH_BYTES, "r1", payload, payload.clone());

            assertThat(RowValues.sameFields(rows.left(), rows.right(), WITH_BYTES))
                    .isTrue();
        }
    }

    @Test
    void twoRowsCarryingDifferentBytesAreNotTheSameRow() {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            Rows rows = twoRows(arena, WITH_BYTES, "r1", new byte[] {0x00, (byte) 0xFF, (byte) 0xC3, 0x28}, new byte[] {
                0x00, (byte) 0xFF, (byte) 0xC3, 0x29
            });

            // If these compared equal, a retraction would cancel a row it does not match and the
            // view would lose one it should keep.
            assertThat(RowValues.sameFields(rows.left(), rows.right(), WITH_BYTES))
                    .isFalse();
        }
    }

    @Test
    void bytesOfDifferentLengthsAreNotTheSameRow() {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            Rows rows = twoRows(arena, WITH_BYTES, "r1", new byte[] {1, 2, 3}, new byte[] {1, 2});

            assertThat(RowValues.sameFields(rows.left(), rows.right(), WITH_BYTES))
                    .isFalse();
        }
    }

    @Test
    void aTypeWithNoDefensibleComparisonIsRefusedWithACodeAndAWayOut() {
        // ARRAY, MAP and ROW still have nested ordering and null questions this engine has not
        // answered. Refusing is right; refusing with a raw UnsupportedOperationException is not,
        // because this reaches a user as a failed query and has to say what to do about it.
        StreamSchema withArray = StreamSchema.builder("s")
                .field("id", Types.string())
                .field("tags", Types.array(Types.string()))
                .build();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RowLayout layout = RowLayout.of(withArray);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle), layout.rowSize(256));
            writer.setString(0, "r1")
                    .setBytes(1, new byte[] {1})
                    .weight(1L)
                    .eventTimestampNanos(0)
                    .sequence(0)
                    .commit();
            BinaryRowView row = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));

            assertThatThrownBy(() -> RowValues.sameFields(row, row, withArray))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("tags")
                    .hasMessageContaining("Project the column away");
        }
    }
}

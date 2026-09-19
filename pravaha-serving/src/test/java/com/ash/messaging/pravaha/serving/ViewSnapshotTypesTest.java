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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.row.Decimals;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view restored from a checkpoint holds exactly the values it held when the checkpoint was taken:
 * the same classes, the same numbers, the same decimal scale (VIEW-2).
 *
 * <p>The snapshot wrote every integral number as a {@code long}, every floating one as a {@code
 * double}, and a {@code BigDecimal} through {@code longValue()} -- so an {@code INT32} key came back
 * as a {@code Long}, which is not {@code equal} to the {@code Integer} the engine writes. After a
 * restore, the next update for that key was a second row beside the first, a retraction missed the
 * row it was retracting, and a decimal had lost its fraction.
 *
 * <p>Each test fills a view the way a lane does -- through a {@link ViewSink} writer -- takes a
 * snapshot, restores it into a fresh view, and then keeps writing to it the same way.
 */
class ViewSnapshotTypesTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("typed")
            .field("id", Types.int32())
            .field("price", Types.decimal(12, 3))
            .field("ratio", Types.float32())
            .field("day", Types.date())
            .field("raw", Types.bytes())
            .field("flag", Types.bool())
            .field("small", Types.int16())
            .field("tiny", Types.int8())
            .field("label", Types.string())
            .field("total", Types.int64())
            .field("share", Types.float64())
            .build();

    private static ServedView view(int... keys) {
        return new ServedView(
                "typed", SCHEMA, java.util.Arrays.stream(keys).boxed().toList(), 100);
    }

    /** One row, written as the pipeline's terminal stage writes one. */
    private static void write(ViewSink sink, int id, String price, float ratio, long weight, long sequence) {
        BigDecimal decimal = new BigDecimal(price).setScale(3);
        sink.begin()
                .setInt(0, id)
                .setDecimal(1, Decimals.high(decimal, 3), Decimals.low(decimal, 3))
                .setFloat(2, ratio)
                .setInt(3, 19_000 + id)
                .setBytes(4, new byte[] {(byte) id, (byte) 0xFF, 0})
                .setBoolean(5, id % 2 == 0)
                .setShort(6, (short) (id * 3))
                .setByte(7, (byte) id)
                .setString(8, "row-" + id)
                .setLong(9, 1_000_000_000_000L + id)
                .setDouble(10, id + 0.25)
                .weight(weight)
                .sequence(sequence)
                .commit();
    }

    private static ServedView restoredCopyOf(ServedView original, int... keys) {
        ServedView restored = view(keys);
        restored.restore(original.snapshot());
        return restored;
    }

    @Test
    void aRestoredRowHoldsTheClassesAndExactValuesTheLiveRowHeld() {
        ServedView live = view(0);
        ViewSink sink = new ViewSink(live, SCHEMA);
        write(sink, 7, "12.345", 1.1f, 1, 1);
        sink.commit(1);
        Object[] before = live.scan().get(0);

        Object[] after = restoredCopyOf(live, 0).scan().get(0);

        for (int column = 0; column < before.length; column++) {
            assertThat(after[column])
                    .as(
                            "column '%s' after a checkpoint restore",
                            SCHEMA.field(column).name())
                    .isInstanceOf(before[column].getClass());
        }
        assertThat(after[0]).isEqualTo(7);
        assertThat(after[1])
                .as("the decimal, fraction and scale both: longValue() made 12.345 into 12")
                .isEqualTo(new BigDecimal("12.345"));
        assertThat(((BigDecimal) after[1]).scale()).isEqualTo(3);
        assertThat(after[2]).isEqualTo(1.1f);
        assertThat(after[3]).isEqualTo(19_007);
        assertThat((byte[]) after[4]).containsExactly(7, 0xFF, 0);
        assertThat(after[5]).isEqualTo(false);
        assertThat(after[6]).isEqualTo((short) 21);
        assertThat(after[7]).isEqualTo((byte) 7);
        assertThat(after[8]).isEqualTo("row-7");
        assertThat(after[9]).isEqualTo(1_000_000_000_007L);
        assertThat(after[10]).isEqualTo(7.25);
    }

    @Test
    void anUpdateAfterARestoreReplacesTheRestoredRowRatherThanJoiningIt() {
        ServedView live = view(0);
        ViewSink sink = new ViewSink(live, SCHEMA);
        write(sink, 1, "1.500", 0.5f, 1, 1);
        write(sink, 2, "2.250", 0.25f, 1, 2);
        sink.commit(2);

        ServedView restored = restoredCopyOf(live, 0);
        ViewSink resumed = new ViewSink(restored, SCHEMA);
        // An update of key 1, as the engine sends one: the old row withdrawn, the new one inserted.
        write(resumed, 1, "1.500", 0.5f, -1, 3);
        write(resumed, 1, "9.875", 0.75f, 1, 4);
        // And a delete of key 2: a retraction that is the last word.
        write(resumed, 2, "2.250", 0.25f, -1, 5);
        resumed.commit(5);

        assertThat(restored.size())
                .as("one row per key: a restored INT32 key came back as a Long and the update beside it was "
                        + "a second key, while the delete of key 2 missed the row it was deleting")
                .isEqualTo(1);
        Object[] row = restored.get(1).values().orElseThrow();
        assertThat(row[1]).isEqualTo(new BigDecimal("9.875"));
        assertThat(row[2]).isEqualTo(0.75f);
        assertThat(restored.get(2).found())
                .as("key 2 was deleted after the restore")
                .isFalse();
    }

    @Test
    void keysOfEveryServedClassMatchTheirLiveSelvesAfterARestore() {
        // Keyed on the decimal, the float, the date, the bytes and the boolean: each must find its
        // restored row. A byte[] compared by identity never did, restored or not.
        int[] keys = {1, 2, 3, 4, 5};
        ServedView live = view(keys);
        ViewSink sink = new ViewSink(live, SCHEMA);
        write(sink, 4, "4.000", 4.5f, 1, 1);
        sink.commit(1);

        ServedView restored = restoredCopyOf(live, keys);
        ViewSink resumed = new ViewSink(restored, SCHEMA);
        write(resumed, 4, "4.000", 4.5f, -1, 2);
        resumed.commit(2);

        assertThat(restored.size())
                .as("the retraction found the restored row under a key made of every non-integral class")
                .isZero();
    }

    @Test
    void aViewComparedWithItsOwnSnapshotHasNothingToSend() {
        ServedView live = view(0);
        ViewSink sink = new ViewSink(live, SCHEMA);
        write(sink, 1, "1.500", 0.5f, 1, 1);
        write(sink, 2, "2.250", 0.25f, 1, 2);
        sink.commit(2);
        byte[] snapshot = live.snapshot();

        assertThat(live.changesSince(snapshot, true)).isEmpty();
        assertThat(restoredCopyOf(live, 0).changesSince(snapshot, true)).isEmpty();

        write(sink, 2, "2.250", 0.25f, -1, 3);
        write(sink, 2, "2.251", 0.25f, 1, 4);
        sink.commit(4);
        List<ViewChange> changes = live.changesSince(snapshot, true);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).weight()).isEqualTo(-1);
        assertThat(changes.get(0).values()[1]).isEqualTo(new BigDecimal("2.250"));
        assertThat(changes.get(0).values()[0]).isInstanceOf(Integer.class);
        assertThat(changes.get(1).values()[1]).isEqualTo(new BigDecimal("2.251"));
    }

    @Test
    void aSnapshotInTheUnversionedFormatIsRefusedAndLeavesTheViewAsItWas() throws Exception {
        // Version 1, byte for byte as it was written: the frontier first, no header, an INT32 key
        // stored as a long. Its decimals had already lost their fractions, so it is refused rather
        // than converted -- by the code a checkpoint refusal carries everywhere else (PRV-4002).
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeLong(5);
            out.writeInt(1);
            out.writeInt(1);
            out.writeByte(4);
            out.writeLong(7);
            out.writeLong(1);
            out.writeLong(5);
        }
        byte[] versionOne = bytes.toByteArray();

        ServedView live = view(0);
        ViewSink sink = new ViewSink(live, SCHEMA);
        write(sink, 3, "3.000", 3f, 1, 1);
        sink.commit(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.restore(versionOne))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-4002")
                .hasMessageContaining("version 1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ServedView.requireReadable(versionOne))
                .hasMessageContaining("PRV-4002");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.changesSince(versionOne, true))
                .hasMessageContaining("PRV-4002");
        assertThat(live.size()).as("a refused snapshot replaces nothing").isEqualTo(1);
        assertThat(live.get(3).found()).isTrue();

        ServedView.requireReadable(live.snapshot());
    }
}

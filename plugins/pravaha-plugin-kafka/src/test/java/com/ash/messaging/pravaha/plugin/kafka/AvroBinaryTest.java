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
package com.ash.messaging.pravaha.plugin.kafka;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The binary encoding itself: the varints, the little-endian floats, the blocks, and the refusals
 * where the bytes run out.
 *
 * <p>The vectors are from the Avro specification, written by hand, so this does not check {@link
 * AvroBinary} against itself: {@code -1} is {@code 0x01} and {@code 1} is {@code 0x02} because the
 * value is zig-zagged before it is written, and a reader that dropped the zig-zag would read that
 * pair <em>swapped</em> rather than fail -- which is what {@link
 * #zigzagIsNotAPlainVarintAndReadingItAsOneSwapsSigns()} pins.
 */
class AvroBinaryTest {

    @Test
    void theSpecificationsVarintVectorsReadBackExactly() throws Undecodable {
        assertThat(read(new byte[] {0x00})).isZero();
        assertThat(read(new byte[] {0x01})).isEqualTo(-1);
        assertThat(read(new byte[] {0x02})).isEqualTo(1);
        assertThat(read(new byte[] {0x03})).isEqualTo(-2);
        assertThat(read(new byte[] {0x04})).isEqualTo(2);
        assertThat(read(new byte[] {(byte) 0x7f})).isEqualTo(-64);
        assertThat(read(new byte[] {(byte) 0x80, 0x01})).isEqualTo(64);
        assertThat(read(new byte[] {(byte) 0x8e, 0x0f})).isEqualTo(967);
        assertThat(read(new byte[] {(byte) 0x8d, 0x0f})).isEqualTo(-967);
    }

    @Test
    void zigzagIsNotAPlainVarintAndReadingItAsOneSwapsSigns() throws Undecodable {
        // Seed proof. If AvroBinary.readLong() stopped zig-zagging -- returned `raw` -- these two
        // assertions would read 1 and -1 the other way round, and this test would fail on both.
        byte[] minusOne = new AvroWriter().number(-1).bytes();
        byte[] plusOne = new AvroWriter().number(1).bytes();
        assertThat(minusOne).containsExactly(0x01);
        assertThat(plusOne).containsExactly(0x02);
        assertThat(read(minusOne)).isEqualTo(-1);
        assertThat(read(plusOne)).isEqualTo(1);

        // And the same bytes written WITHOUT the zig-zag are a different pair of numbers, so the
        // two encodings cannot be confused for one another by accident.
        assertThat(read(new AvroWriter().plainVarint(1).bytes())).isEqualTo(-1);
        assertThat(read(new AvroWriter().plainVarint(2).bytes())).isEqualTo(1);
    }

    @Test
    void everyLongRoundTripsThroughTheWriterAndBackIncludingTheExtremes() throws Undecodable {
        Random random = new Random(20260919L);
        for (long value : List.of(
                Long.MIN_VALUE,
                Long.MIN_VALUE + 1,
                Integer.MIN_VALUE - 1L,
                -1L,
                0L,
                1L,
                Integer.MAX_VALUE + 1L,
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE)) {
            assertThat(read(new AvroWriter().number(value).bytes()))
                    .as("%d", value)
                    .isEqualTo(value);
        }
        for (int i = 0; i < 10_000; i++) {
            long value = random.nextLong();
            assertThat(read(new AvroWriter().number(value).bytes()))
                    .as("seed 20260919, value %d", value)
                    .isEqualTo(value);
        }
    }

    @Test
    void anIntOutOfRangeIsRefusedRatherThanTruncated() {
        byte[] tooBig = new AvroWriter().number(Integer.MAX_VALUE + 1L).bytes();
        assertThatThrownBy(() -> new AvroBinary(tooBig, 0).readInt())
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("out of an int's range");
    }

    @Test
    void aVarintThatNeverEndsIsRefused() {
        byte[] eleven = new byte[11];
        java.util.Arrays.fill(eleven, (byte) 0x80);
        assertThatThrownBy(() -> new AvroBinary(eleven, 0).readLong())
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("past ten bytes");
    }

    @Test
    void floatsAndDoublesAreLittleEndianIeee() throws Undecodable {
        byte[] one = new AvroWriter().float32(1.0f).bytes();
        assertThat(one).containsExactly(0x00, 0x00, (byte) 0x80, 0x3f);
        assertThat(new AvroBinary(one, 0).readFloat()).isEqualTo(1.0f);

        byte[] nan = new AvroWriter().float64(Double.NaN).bytes();
        assertThat(new AvroBinary(nan, 0).readDouble()).isNaN();
        byte[] negativeInfinity =
                new AvroWriter().float64(Double.NEGATIVE_INFINITY).bytes();
        assertThat(new AvroBinary(negativeInfinity, 0).readDouble())
                .isNegative()
                .isInfinite();
    }

    @Test
    void stringsAndBytesCarryTheirLengthAndUtf8() throws Undecodable {
        byte[] written =
                new AvroWriter().text("नमस्ते").binary(new byte[] {1, 2, 3}).bytes();
        AvroBinary in = new AvroBinary(written, 0);
        assertThat(in.readString()).isEqualTo("नमस्ते");
        assertThat(in.readBytes()).containsExactly(1, 2, 3);
        assertThat(in.atEnd()).isTrue();
    }

    @Test
    void aBlockMayBeWrittenWithACountOrWithANegativeCountAndASize() throws Undecodable {
        byte[] plain = new AvroWriter().block(3).bytes();
        assertThat(new AvroBinary(plain, 0).blockCount()).isEqualTo(3);
        byte[] sized = new AvroWriter().sizedBlock(3, 17).bytes();
        AvroBinary in = new AvroBinary(sized, 0);
        assertThat(in.blockCount())
                .as("the count, with the byte size read and dropped")
                .isEqualTo(3);
        assertThat(in.atEnd()).isTrue();
    }

    @Test
    void aValueThatEndsInTheMiddleIsUndecodableAndNeverAnIndexOutOfBounds() {
        byte[] truncatedString =
                new AvroWriter().number(10).fixed(new byte[] {1, 2}).bytes();
        assertThatThrownBy(() -> new AvroBinary(truncatedString, 0).readString())
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("ends in the middle");
        assertThatThrownBy(() -> new AvroBinary(new byte[] {0x01, 0x02}, 0).readDouble())
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("ends in the middle of a double");
        assertThatThrownBy(() -> new AvroBinary(new byte[0], 0).readBoolean())
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("ends in the middle of a boolean");
    }

    @Test
    void aBooleanThatIsNeitherZeroNorOneIsRefused() {
        assertThatThrownBy(() -> new AvroBinary(new byte[] {7}, 0).readBoolean())
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("neither 0 nor 1");
    }

    @Test
    void skippingReadsPastAValueOfAnyShapeAndLeavesTheNextOneReadable() throws Undecodable {
        AvroSchema.Node schema = AvroSchema.parse("""
                {"type":"record","name":"Skippable","fields":[
                  {"name":"tags","type":{"type":"array","items":"string"}},
                  {"name":"props","type":{"type":"map","values":"long"}},
                  {"name":"nested","type":{"type":"record","name":"Inner","fields":[
                     {"name":"a","type":["null","bytes"]},{"name":"b","type":{"type":"fixed","name":"F","size":3}}]}},
                  {"name":"colour","type":{"type":"enum","name":"Colour","symbols":["RED","GREEN"]}}]}""");
        AvroWriter writer = new AvroWriter();
        writer.block(2).text("a").text("b").block(0);
        writer.block(1).text("k").number(7).block(0);
        writer.union(1).binary(new byte[] {9}).fixed(new byte[] {1, 2, 3});
        writer.integer(1);
        writer.text("after");
        byte[] bytes = writer.bytes();

        AvroBinary in = new AvroBinary(bytes, 0);
        for (AvroSchema.Field field : schema.fields()) {
            in.skip(field.type());
        }
        assertThat(in.readString()).isEqualTo("after");
        assertThat(in.atEnd()).isTrue();
    }

    @Test
    void aUnionIndexNoBranchHasIsRefused() {
        AvroSchema.Node union = AvroSchema.parse("[\"null\",\"string\"]");
        byte[] third = new AvroWriter().union(2).bytes();
        assertThatThrownBy(() -> new AvroBinary(third, 0).branch(union))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("branch 2 of 2");
    }

    private static long read(byte[] bytes) throws Undecodable {
        return new AvroBinary(bytes, 0).readLong();
    }
}

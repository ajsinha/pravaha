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
package com.ash.messaging.pravaha.runtime.plan;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CompareString} compares an ASCII literal in place and gives the answer decoding gave.
 *
 * <p>The in-place comparison exists for speed (gate P2, 2026-09-26: the decoded String was half of a
 * lane's time), and speed is no excuse for a different answer. So the reference here is the old
 * code, written out: decode the stored bytes as UTF-8 and compare Strings. The stored bytes are
 * chosen where a byte comparison and a decode could part company -- overlong encodings of ASCII
 * letters, stray continuation bytes, truncated sequences, encoded surrogates, multi-byte text and
 * prefixes of the literal -- and the literal is ASCII, non-ASCII or empty.
 */
class CompareStringInPlaceTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("s")
            .field("id", Types.int64())
            .field("text", Types.string().withNullable(true))
            .build();

    private static final String[] LITERALS = {"", "A", "AB", "COMPLETED", "COMPLETE", "prav", "प्रवाह", "�"};

    private static final byte[][] FRAGMENTS = {
        {'A'},
        {'B'},
        {'C'},
        {'O'},
        {'M'},
        {'P'},
        {'L'},
        {'E'},
        {'T'},
        {'D'},
        {(byte) 0xC1, (byte) 0x81}, // overlong 'A'
        {(byte) 0xC0, (byte) 0x80}, // overlong NUL
        {(byte) 0x80}, // stray continuation
        {(byte) 0xE0, (byte) 0xA4}, // truncated three-byte sequence
        {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, // an encoded surrogate
        {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD}, // U+FFFD itself
        "प्र".getBytes(StandardCharsets.UTF_8),
    };

    @Test
    void theInPlaceComparisonGivesTheDecodedAnswer() {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        Random random = new Random(11);
        int matched = 0;
        try (MemoryRegion region = MemoryAccess.best().allocate(1 << 12)) {
            for (int trial = 0; trial < 20_000; trial++) {
                String literal = LITERALS[random.nextInt(LITERALS.length)];
                byte[] stored = random.nextInt(4) == 0 ? literal.getBytes(StandardCharsets.UTF_8) : randomBytes(random);
                write(writer, region, layout, stored);
                view.wrap(region, 0);
                boolean decoded = new String(stored, StandardCharsets.UTF_8).equals(literal);
                matched += decoded ? 1 : 0;
                for (Predicate.Op op : new Predicate.Op[] {Predicate.Op.EQ, Predicate.Op.NE}) {
                    boolean expected = op == Predicate.Op.EQ ? decoded : !decoded;
                    assertThat(new Predicate.CompareString(1, "text", op, literal).test(view))
                            .as("%s %s over bytes %s", op, literal, java.util.Arrays.toString(stored))
                            .isEqualTo(expected);
                }
            }
        }
        assertThat(matched)
                .as("the fixture must produce matches as well as mismatches")
                .isGreaterThan(1000);
    }

    private static byte[] randomBytes(Random random) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pieces = random.nextInt(10);
        for (int i = 0; i < pieces; i++) {
            out.writeBytes(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
        }
        return out.toByteArray();
    }

    /** A row whose text column holds exactly {@code stored}, valid UTF-8 or not. */
    private static void write(BinaryRowWriter writer, MemoryRegion region, RowLayout layout, byte[] stored) {
        writer.begin(region, 0);
        writer.setLong(0, 1L).setString(1, "x".repeat(stored.length));
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        int payload = region.getInt(layout.offsetOf(1));
        region.putBytes(payload, stored, 0, stored.length);
    }
}

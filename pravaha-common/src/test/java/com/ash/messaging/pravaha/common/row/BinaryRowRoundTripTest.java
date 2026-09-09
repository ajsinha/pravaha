/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.row;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-06's acceptance criterion: whatever is written comes back, for generated schemas covering every
 * type.
 *
 * <p>Property-based rather than example-based on purpose. The layout has several interacting rules
 * -- alignment, the null bitmap, payload ordering, offsets relative to the row -- and the bugs they
 * produce appear only for particular *combinations* of field types and widths. Hand-picked examples
 * reliably miss those; generated ones find them.
 */
class BinaryRowRoundTripTest {

    private static final int REGION_BYTES = 1 << 16;

    @Property(tries = 400)
    void whateverIsWrittenIsWhatIsRead(@ForAll("samples") RowSample sample, @ForAll("rowOffsets") int rowOffset) {
        // The offset is generated rather than fixed at zero. Writing every row at zero makes an
        // absolute payload offset indistinguishable from a relative one, so the invariant the next
        // property depends on would never actually be exercised.
        RowLayout layout = RowLayout.of(sample.schema());
        try (MemoryRegion region = MemoryAccess.best().allocate(REGION_BYTES)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            sample.write(region, rowOffset, layout, writer);
            BinaryRowView view = new BinaryRowView(layout).wrap(region, rowOffset);
            sample.assertMatches(view, new MutableSlice());
        }
    }

    @Property(tries = 400)
    void aRowStaysValidWhereverItIsCopied(
            @ForAll("samples") RowSample sample,
            @ForAll("rowOffsets") int source,
            @ForAll("rowOffsets") int destination) {
        // Offsets are stored relative to the row precisely so that checkpointing and shipping a row
        // between nodes is a memcpy rather than a re-encode. Both ends are generated: a fixed
        // source of zero would let an absolute offset pass, which is exactly the bug this guards.
        RowLayout layout = RowLayout.of(sample.schema());
        try (MemoryRegion region = MemoryAccess.best().allocate(REGION_BYTES)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            int size = sample.write(region, source, layout, writer);

            int target = destination + REGION_BYTES / 2;
            region.copyFrom(target, region, source, size);

            BinaryRowView moved = new BinaryRowView(layout).wrap(region, target);
            sample.assertMatches(moved, new MutableSlice());
        }
    }

    @Property(tries = 200)
    void manyRowsPackedBackToBackDoNotDisturbEachOther(
            @ForAll("samples") RowSample sample, @ForAll("rowOffsets") int start) {
        RowLayout layout = RowLayout.of(sample.schema());
        try (MemoryRegion region = MemoryAccess.best().allocate(REGION_BYTES)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            List<Integer> offsets = new ArrayList<>();

            int cursor = start;
            for (int i = 0; i < 8; i++) {
                offsets.add(cursor);
                cursor += align8(sample.write(region, cursor, layout, writer));
            }

            BinaryRowView view = new BinaryRowView(layout);
            for (int offset : offsets) {
                sample.assertMatches(view.wrap(region, offset), new MutableSlice());
            }
        }
    }

    @Test
    void everyTypeIsExercisedByAtLeastOneField() {
        // The generator covers every TypeName; this asserts that stays true as types are added,
        // rather than leaving a new type silently untested.
        StreamSchema schema = schemaWithEveryType();
        EnumSet<TypeName> covered = EnumSet.noneOf(TypeName.class);
        for (int i = 0; i < schema.fieldCount(); i++) {
            covered.add(schema.field(i).type().typeName());
        }
        assertThat(covered).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(TypeName.class));

        RowSample sample = new RowSample(schema, valuesFor(schema, new Random(20260909L), false));
        RowLayout layout = RowLayout.of(schema);
        try (MemoryRegion region = MemoryAccess.best().allocate(REGION_BYTES)) {
            sample.write(region, 512, layout, new BinaryRowWriter(layout));
            sample.assertMatches(new BinaryRowView(layout).wrap(region, 512), new MutableSlice());
        }
    }

    @Test
    void everyTypeRoundTripsAsNullWhenNullable() {
        StreamSchema schema = nullableSchemaWithEveryType();
        List<Object> allNull = new ArrayList<>();
        for (int i = 0; i < schema.fieldCount(); i++) {
            allNull.add(null);
        }
        RowSample sample = new RowSample(schema, allNull);
        RowLayout layout = RowLayout.of(schema);
        try (MemoryRegion region = MemoryAccess.best().allocate(REGION_BYTES)) {
            sample.write(region, 512, layout, new BinaryRowWriter(layout));
            BinaryRowView view = new BinaryRowView(layout).wrap(region, 512);
            for (int i = 0; i < schema.fieldCount(); i++) {
                assertThat(view.isNull(i)).as("field %d should be null", i).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- generators

    /** Eight-byte aligned, always non-zero: zero would hide an absolute-versus-relative bug. */
    @Provide
    Arbitrary<Integer> rowOffsets() {
        return Arbitraries.integers().between(1, 256).map(i -> i * 8);
    }

    @Provide
    Arbitrary<RowSample> samples() {
        return Arbitraries.integers()
                .between(1, 24)
                .flatMap(fieldCount -> Arbitraries.longs()
                        .flatMap(seed -> Arbitraries.of(true, false)
                                .map(allowNulls -> buildSample(fieldCount, seed, allowNulls))));
    }

    private static RowSample buildSample(int fieldCount, long seed, boolean allowNulls) {
        Random random = new Random(seed);
        TypeName[] all = TypeName.values();
        StreamSchema.Builder b = StreamSchema.builder("generated");
        for (int i = 0; i < fieldCount; i++) {
            PravahaType type = typeFor(all[random.nextInt(all.length)], random);
            b.field("f" + i, allowNulls && random.nextBoolean() ? type.withNullable(true) : type);
        }
        StreamSchema schema = b.build();
        return new RowSample(schema, valuesFor(schema, random, allowNulls));
    }

    private static PravahaType typeFor(TypeName name, Random random) {
        return switch (name) {
            case BOOLEAN -> Types.bool();
            case INT8 -> Types.int8();
            case INT16 -> Types.int16();
            case INT32 -> Types.int32();
            case INT64 -> Types.int64();
            case FLOAT32 -> Types.float32();
            case FLOAT64 -> Types.float64();
            case DECIMAL -> Types.decimal(18, 4);
            case DATE -> Types.date();
            case TIME -> Types.time();
            case TIMESTAMP_LTZ -> Types.timestamp();
            case STRING -> random.nextBoolean() ? Types.string() : Types.string(64);
            case BYTES -> Types.bytes();
            case ARRAY -> Types.array(Types.int64());
            case MAP -> Types.map(Types.string(), Types.int64());
            case ROW -> Types.row(List.of(new com.ash.messaging.pravaha.api.data.Field("a", Types.int32(), 0)));
        };
    }

    private static List<Object> valuesFor(StreamSchema schema, Random random, boolean allowNulls) {
        List<Object> values = new ArrayList<>(schema.fieldCount());
        for (int i = 0; i < schema.fieldCount(); i++) {
            PravahaType type = schema.field(i).type();
            if (allowNulls && type.nullable() && random.nextInt(4) == 0) {
                values.add(null);
                continue;
            }
            values.add(valueFor(type.typeName(), random));
        }
        return values;
    }

    private static Object valueFor(TypeName name, Random random) {
        return switch (name) {
            case BOOLEAN -> random.nextBoolean();
            case INT8 -> (byte) random.nextInt();
            case INT16 -> (short) random.nextInt();
            case INT32, DATE -> random.nextInt();
            case INT64, TIME, TIMESTAMP_LTZ -> random.nextLong();
            case FLOAT32 -> random.nextFloat();
            case FLOAT64 -> random.nextDouble();
            case DECIMAL -> new long[] {random.nextLong(), random.nextLong()};
            // Deliberately includes multi-byte UTF-8: a length computed in characters rather than
            // bytes is a classic encoding bug, and it only shows up with non-ASCII input.
            case STRING -> randomString(random);
            case BYTES, ARRAY, MAP, ROW -> randomBytes(random);
        };
    }

    private static String randomString(Random random) {
        String[] alphabet = {"a", "Z", "9", "_", "प", "र", "वा", "ह", "é", "🌊"};
        StringBuilder sb = new StringBuilder();
        int length = random.nextInt(12);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    private static byte[] randomBytes(Random random) {
        byte[] out = new byte[random.nextInt(24)];
        random.nextBytes(out);
        return out;
    }

    private static StreamSchema schemaWithEveryType() {
        StreamSchema.Builder b = StreamSchema.builder("every_type");
        Random r = new Random(1L);
        int i = 0;
        for (TypeName name : TypeName.values()) {
            b.field("f" + i++, typeFor(name, r));
        }
        return b.build();
    }

    private static StreamSchema nullableSchemaWithEveryType() {
        StreamSchema.Builder b = StreamSchema.builder("every_type_nullable");
        Random r = new Random(2L);
        int i = 0;
        for (TypeName name : TypeName.values()) {
            b.field("f" + i++, typeFor(name, r).withNullable(true));
        }
        return b.build();
    }

    private static int align8(int value) {
        return (value + 7) & ~7;
    }

    /** Kept so an unused-import check does not flag the tuple type jqwik needs on the classpath. */
    @SuppressWarnings("unused")
    private static Tuple.Tuple2<Integer, Long> unusedTupleReference() {
        return Tuple.of(0, 0L);
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowLayoutTest {

    private static StreamSchema mixedSchema() {
        return StreamSchema.builder("mixed")
                .field("flag", Types.bool()) // 1 byte
                .field("small", Types.int16()) // 2 bytes
                .field("count", Types.int32()) // 4 bytes
                .field("total", Types.int64()) // 8 bytes
                .field("amount", Types.decimal(18, 4)) // 16 bytes
                .field("name", Types.string()) // variable
                .field("payload", Types.bytes()) // variable
                .build();
    }

    @Test
    void headerComesFirstAndIsEightByteAligned() {
        RowLayout l = RowLayout.of(mixedSchema());
        assertThat(RowLayout.HEADER_BYTES % 8).isZero();
        assertThat(l.nullBitmapOffset()).isEqualTo(RowLayout.HEADER_BYTES);
    }

    @Test
    void nullBitmapIsOneBitPerFieldPaddedToEightBytes() {
        assertThat(RowLayout.of(mixedSchema()).nullBitmapBytes()).isEqualTo(8); // 7 fields -> 1 byte -> pad 8
        StreamSchema wide = wideSchema(65);
        assertThat(RowLayout.of(wide).nullBitmapBytes()).isEqualTo(16); // 65 fields -> 9 bytes -> pad 16
    }

    @Test
    void everyFieldIsAlignedToItsOwnWidth() {
        // Misaligned access is legal on x86 but costs a penalty on a split cache line, and is
        // unsupported outright on some architectures we would like to keep the door open to.
        RowLayout l = RowLayout.of(mixedSchema());
        StreamSchema s = l.schema();
        for (int i = 0; i < l.fieldCount(); i++) {
            int width = l.isVariableWidth(i)
                    ? RowLayout.VAR_SLOT_BYTES
                    : s.field(i).type().fixedWidth();
            int alignment = Math.min(width, 8);
            assertThat(l.offsetOf(i) % alignment)
                    .as(
                            "field %d ('%s') offset %d is not aligned to %d",
                            i, s.field(i).name(), l.offsetOf(i), alignment)
                    .isZero();
        }
    }

    @Test
    void fieldSlotsDoNotOverlap() {
        RowLayout l = RowLayout.of(mixedSchema());
        StreamSchema s = l.schema();
        for (int i = 0; i < l.fieldCount(); i++) {
            int iWidth = l.isVariableWidth(i)
                    ? RowLayout.VAR_SLOT_BYTES
                    : s.field(i).type().fixedWidth();
            for (int j = i + 1; j < l.fieldCount(); j++) {
                int jWidth = l.isVariableWidth(j)
                        ? RowLayout.VAR_SLOT_BYTES
                        : s.field(j).type().fixedWidth();
                boolean disjoint = l.offsetOf(i) + iWidth <= l.offsetOf(j) || l.offsetOf(j) + jWidth <= l.offsetOf(i);
                assertThat(disjoint).as("fields %d and %d overlap", i, j).isTrue();
            }
        }
    }

    @Test
    void variableWidthFieldsGetAPointerSlot() {
        RowLayout l = RowLayout.of(mixedSchema());
        assertThat(l.variableFieldCount()).isEqualTo(2);
        assertThat(l.isVariableWidth(l.schema().indexOf("name"))).isTrue();
        assertThat(l.isVariableWidth(l.schema().indexOf("payload"))).isTrue();
        assertThat(l.isVariableWidth(l.schema().indexOf("count"))).isFalse();
    }

    @Test
    void fixedEndIsWhereThePayloadBeginsAndIsEightByteAligned() {
        RowLayout l = RowLayout.of(mixedSchema());
        assertThat(l.fixedEnd() % 8).isZero();
        assertThat(l.fixedEnd()).isGreaterThan(l.fixedRegionOffset());
        assertThat(l.rowSize(0)).isEqualTo(l.fixedEnd());
        assertThat(l.rowSize(24)).isEqualTo(l.fixedEnd() + 24);
    }

    @Test
    void nullBitAddressingCoversEveryField() {
        RowLayout l = RowLayout.of(wideSchema(20));
        for (int i = 0; i < 20; i++) {
            int byteOffset = l.nullByteOffset(i);
            assertThat(byteOffset).isBetween(l.nullBitmapOffset(), l.nullBitmapOffset() + l.nullBitmapBytes() - 1);
            assertThat(Integer.bitCount(l.nullBitMask(i) & 0xFF)).isOne();
        }
        // Distinct fields must never share a (byte, bit) pair, or one null would set another.
        for (int i = 0; i < 20; i++) {
            for (int j = i + 1; j < 20; j++) {
                boolean sameBit = l.nullByteOffset(i) == l.nullByteOffset(j) && l.nullBitMask(i) == l.nullBitMask(j);
                assertThat(sameBit)
                        .as("fields %d and %d share a null bit", i, j)
                        .isFalse();
            }
        }
    }

    @Test
    void rejectsASchemaWithNoFields() {
        assertThatThrownBy(() -> RowLayout.of(StreamSchema.builder("empty").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no fields");
    }

    @Test
    void checkTypeNamesTheFieldAndBothTypes() {
        RowLayout l = RowLayout.of(mixedSchema());
        assertThatThrownBy(() ->
                        l.checkType(l.schema().indexOf("count"), com.ash.messaging.pravaha.api.data.TypeName.INT64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count")
                .hasMessageContaining("INT32")
                .hasMessageContaining("INT64");
    }

    @Test
    void toStringDescribesTheLayout() {
        assertThat(RowLayout.of(mixedSchema()).toString())
                .contains("mixed")
                .contains("header=32")
                .contains("varFields=2");
    }

    private static StreamSchema wideSchema(int fields) {
        StreamSchema.Builder b = StreamSchema.builder("wide");
        for (int i = 0; i < fields; i++) {
            b.field("f" + i, Types.int32());
        }
        return b.build();
    }
}

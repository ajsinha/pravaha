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
package com.ash.messaging.pravaha.api.data;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PravahaTypeTest {

    static Stream<PravahaType> allTypes() {
        return Stream.of(
                Types.bool(),
                Types.int8(),
                Types.int16(),
                Types.int32(),
                Types.int64(),
                Types.float32(),
                Types.float64(),
                Types.date(),
                Types.time(),
                Types.decimal(18, 4),
                Types.timestamp(),
                Types.string(),
                Types.string(64),
                Types.bytes(),
                Types.array(Types.int64()),
                Types.map(Types.string(), Types.int64()),
                Types.row(List.of(new Field("a", Types.int32(), 0))));
    }

    @ParameterizedTest
    @MethodSource("allTypes")
    void factoriesProduceNotNullTypes(PravahaType t) {
        // Nullability should be a decision someone made, not one they inherited by omission.
        assertThat(t.nullable()).isFalse();
        assertThat(t.sqlName()).endsWith("NOT NULL");
    }

    @ParameterizedTest
    @MethodSource("allTypes")
    void withNullableRoundTrips(PravahaType t) {
        PravahaType nullable = t.withNullable(true);
        assertThat(nullable.nullable()).isTrue();
        assertThat(nullable.typeName()).isEqualTo(t.typeName());
        assertThat(nullable.withNullable(false)).isEqualTo(t);
    }

    @ParameterizedTest
    @MethodSource("allTypes")
    void withNullableIsIdentityWhenUnchanged(PravahaType t) {
        assertThat(t.withNullable(false)).isSameAs(t);
    }

    @ParameterizedTest
    @MethodSource("allTypes")
    void widthDelegatesToTheTypeName(PravahaType t) {
        assertThat(t.fixedWidth()).isEqualTo(t.typeName().fixedWidth());
        assertThat(t.isFixedWidth()).isEqualTo(t.typeName().isFixedWidth());
    }

    @Test
    void decimalRejectsOutOfRangePrecisionAndScale() {
        assertThatThrownBy(() -> Types.decimal(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Types.decimal(39, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Types.decimal(10, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Types.decimal(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timestampRejectsPrecisionBeyondNanoseconds() {
        assertThatThrownBy(() -> Types.timestamp(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Types.timestamp(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void primitiveTypeRejectsParameterisedTypeNames() {
        assertThatThrownBy(() -> new PrimitiveType(TypeName.DECIMAL, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a primitive");
        assertThatThrownBy(() -> new PrimitiveType(TypeName.STRING, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapKeysAreForcedNotNullable() {
        // A null map key has no meaning; silently accepting one would surface much later.
        MapType m = (MapType) Types.map(Types.string().withNullable(true), Types.int64());
        assertThat(m.keyType().nullable()).isFalse();
    }

    @Test
    void rowRejectsDuplicateFieldNames() {
        assertThatThrownBy(() -> Types.row(List.of(new Field("a", Types.int32(), 0), new Field("a", Types.int64(), 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void variableWidthTypesRejectNonPositiveExplicitLengths() {
        assertThatThrownBy(() -> Types.string(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Types.bytes(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sqlNamesRenderParameters() {
        assertThat(Types.decimal(18, 4).sqlName()).isEqualTo("DECIMAL(18, 4) NOT NULL");
        assertThat(Types.string(64).sqlName()).isEqualTo("VARCHAR(64) NOT NULL");
        assertThat(Types.string().sqlName()).isEqualTo("VARCHAR NOT NULL");
        assertThat(Types.bytes().sqlName()).isEqualTo("VARBINARY NOT NULL");
        assertThat(Types.timestamp().sqlName()).isEqualTo("TIMESTAMP(9) WITH LOCAL TIME ZONE NOT NULL");
        assertThat(Types.array(Types.int64()).sqlName()).isEqualTo("ARRAY<INT64 NOT NULL> NOT NULL");
        assertThat(Types.map(Types.string(), Types.int64()).sqlName())
                .isEqualTo("MAP<VARCHAR NOT NULL, INT64 NOT NULL> NOT NULL");
        assertThat(Types.row(List.of(new Field("a", Types.int32(), 0))).sqlName())
                .isEqualTo("ROW<a INT32 NOT NULL> NOT NULL");
    }
}

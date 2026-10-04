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
package com.ash.messaging.pravaha.sql;

import java.util.List;
import java.util.stream.Stream;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeMappingTest {

    // The widened type system, not Calcite's default: the round-trip property below is what
    // found that the default caps DECIMAL at 19 digits and TIMESTAMP at milliseconds.
    private static final RelDataTypeFactory FACTORY = new JavaTypeFactoryImpl(PravahaTypeSystem.INSTANCE);

    /** Every type that maps to a distinct SQL type. ARRAY/MAP/ROW map to ANY and cannot round-trip. */
    static Stream<PravahaType> roundTrippable() {
        return Stream.of(
                Types.bool(),
                Types.int8(),
                Types.int16(),
                Types.int32(),
                Types.int64(),
                Types.float32(),
                Types.float64(),
                Types.decimal(18, 4),
                Types.decimal(38, 0),
                Types.date(),
                Types.time(),
                Types.timestamp(),
                Types.string(),
                Types.string(64),
                Types.bytes());
    }

    @ParameterizedTest
    @MethodSource("roundTrippable")
    void everyTypeSurvivesTheRoundTrip(PravahaType original) {
        // A mapping that disagrees with itself in the two directions produces the worst kind of
        // bug: a query validates, plans, and then reads a column as the wrong width.
        RelDataType calcite = TypeMapping.toCalcite(FACTORY, original);
        PravahaType back = TypeMapping.fromCalcite(calcite);
        assertThat(back.typeName()).isEqualTo(original.typeName());
        assertThat(back).isEqualTo(original);
    }

    @ParameterizedTest
    @MethodSource("roundTrippable")
    void nullabilityAlsoSurvives(PravahaType original) {
        PravahaType nullable = original.withNullable(true);
        assertThat(TypeMapping.fromCalcite(TypeMapping.toCalcite(FACTORY, nullable))
                        .nullable())
                .isTrue();
        assertThat(TypeMapping.fromCalcite(TypeMapping.toCalcite(FACTORY, original))
                        .nullable())
                .isFalse();
    }

    @Test
    void everyTypeNameIsHandledSoANewOneCannotBeSilentlyMissed() {
        // Without this, adding a TypeName and forgetting the mapping would surface as a runtime
        // failure in a customer's query rather than a red build here.
        for (TypeName name : TypeName.values()) {
            PravahaType type =
                    switch (name) {
                        case BOOLEAN -> Types.bool();
                        case INT8 -> Types.int8();
                        case INT16 -> Types.int16();
                        case INT32 -> Types.int32();
                        case INT64 -> Types.int64();
                        case FLOAT32 -> Types.float32();
                        case FLOAT64 -> Types.float64();
                        case DECIMAL -> Types.decimal(10, 2);
                        case DATE -> Types.date();
                        case TIME -> Types.time();
                        case TIMESTAMP_LTZ -> Types.timestamp();
                        case STRING -> Types.string();
                        case BYTES -> Types.bytes();
                        case ARRAY -> Types.array(Types.int64());
                        case MAP -> Types.map(Types.string(), Types.int64());
                        case ROW ->
                            Types.row(List.of(new com.ash.messaging.pravaha.api.data.Field("a", Types.int32(), 0)));
                    };
            assertThat(TypeMapping.toCalcite(FACTORY, type))
                    .as("no Calcite mapping for %s", name)
                    .isNotNull();
        }
    }

    @Test
    void theWidenedTypeSystemAllowsWhatPravahaTypesActuallySupport() {
        // Both of these were found by the round-trip property, not by reading documentation.
        assertThat(PravahaTypeSystem.INSTANCE.getMaxPrecision(org.apache.calcite.sql.type.SqlTypeName.DECIMAL))
                .as("128-bit unscaled decimals are 38 digits; Calcite defaults to 19 and truncates")
                .isEqualTo(38);
        assertThat(PravahaTypeSystem.INSTANCE.getMaxPrecision(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE))
                .as("timestamps are nanoseconds; Calcite defaults to milliseconds and rounds window edges")
                .isEqualTo(9);
        assertThat(PravahaTypeSystem.INSTANCE.getDefaultPrecision(SqlTypeName.TIMESTAMP))
                .as("an unqualified TIMESTAMP must mean the engine's native precision")
                .isEqualTo(9);
    }

    @Test
    void decimalKeepsItsPrecisionAndScale() {
        RelDataType calcite = TypeMapping.toCalcite(FACTORY, Types.decimal(18, 4));
        assertThat(calcite.getPrecision()).isEqualTo(18);
        assertThat(calcite.getScale()).isEqualTo(4);
    }

    @Test
    void timestampIsAnInstantNotAWallClockReading() {
        // Design 15.1: Pravaha timestamps are points on the UTC timeline. Calcite's plain TIMESTAMP
        // is a wall-clock type whose comparison semantics differ in the cases windowing relies on.
        assertThat(TypeMapping.toCalcite(FACTORY, Types.timestamp()).getSqlTypeName())
                .isEqualTo(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE);
    }

    @Test
    void aWholeSchemaBecomesARowTypeInOrder() {
        StreamSchema schema = StreamSchema.builder("s")
                .field("a", Types.int64())
                .field("b", Types.string())
                .field("c", Types.bool())
                .build();
        RelDataType rowType = TypeMapping.toRowType(FACTORY, schema);
        assertThat(rowType.getFieldNames()).containsExactly("a", "b", "c");
        assertThat(rowType.getFieldList().get(0).getType().getSqlTypeName()).isEqualTo(SqlTypeName.BIGINT);
    }

    @Test
    void aBareNullIsRefusedWithTheCastThatFixesIt() {
        assertThatThrownBy(() ->
                        TypeMapping.fromCalcite(FACTORY.createSqlType(org.apache.calcite.sql.type.SqlTypeName.NULL)))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("a bare NULL has no type")
                .hasMessageContaining("CAST(NULL AS BIGINT)");
    }

    @Test
    void anUnmappableSqlTypeSaysSoRatherThanGuessing() {
        RelDataType interval = FACTORY.createSqlIntervalType(new org.apache.calcite.sql.SqlIntervalQualifier(
                org.apache.calcite.avatica.util.TimeUnit.DAY,
                org.apache.calcite.avatica.util.TimeUnit.SECOND,
                org.apache.calcite.sql.parser.SqlParserPos.ZERO));
        // Coded, not an IllegalArgumentException. CONTINUOUS_QUERIES.md promises every refusal carries a
        // PRV code, and this one reached a client through Flight carrying none -- which is how
        // SELECT NULL surfaced as an untyped stack trace.
        assertThatThrownBy(() -> TypeMapping.fromCalcite(interval))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("no Pravaha type");
    }
}

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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.plugin.mysqlcdc.MySqlSchema.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Column types to stream types, and binlog values to row values. */
class MySqlSchemaTest {

    private static final MySqlCdcOptions OPTIONS = TransactionAssemblerTest.options(Map.of());

    private static MySqlSchema.Column column(String dataType, String columnType) {
        return new MySqlSchema.Column("c", dataType, columnType, true, "utf8mb4");
    }

    @Test
    void unsignedColumnsAreReinterpretedAndWidened() {
        assertThat(MySqlSchema.convert(Kind.TINY_UNSIGNED, null, Types.int16(), -1))
                .isEqualTo((short) 255);
        assertThat(MySqlSchema.convert(Kind.SHORT_UNSIGNED, null, Types.int32(), -1))
                .isEqualTo(65535);
        assertThat(MySqlSchema.convert(Kind.MEDIUM_UNSIGNED, null, Types.int32(), -1))
                .isEqualTo(16_777_215);
        assertThat(MySqlSchema.convert(Kind.INT_UNSIGNED, null, Types.int64(), -1))
                .isEqualTo(4_294_967_295L);
        assertThat(MySqlSchema.convert(Kind.BIG_UNSIGNED, null, Types.decimal(38, 0), -1L))
                .isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(MySqlSchema.convert(Kind.MEDIUM, null, Types.int32(), -5)).isEqualTo(-5);
    }

    @Test
    void temporalValuesAreMicrosecondsAndTextIsDecodedInTheColumnsCharacterSet() {
        long micros = 1_700_000_000_123_456L;
        assertThat(MySqlSchema.convert(Kind.TIMESTAMP, null, Types.timestamp(), micros))
                .isEqualTo(micros * 1000);
        assertThat(MySqlSchema.convert(Kind.DATE, null, Types.date(), 86_400_000_000L * 20_000))
                .isEqualTo(20_000);
        assertThat(MySqlSchema.convert(
                        Kind.STRING, StandardCharsets.UTF_8, Types.string(), "grün".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("grün");
        assertThat(MySqlSchema.convert(Kind.DOUBLE, null, Types.float64(), 2.5d))
                .isEqualTo(2.5d);
        assertThat(MySqlSchema.convert(Kind.BIG, null, Types.int64(), null)).isNull();
        assertThatThrownBy(() -> MySqlSchema.convert(Kind.INT, null, Types.int32(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typesMapExactlyAndAnUnmappedOneIsRefusedByName() {
        MySqlSchema.Mapping mapping = MySqlSchema.resolve(
                OPTIONS,
                List.of(
                        column("int", "int unsigned"),
                        column("decimal", "decimal(12,3)"),
                        column("datetime", "datetime(6)"),
                        column("blob", "blob")));
        assertThat(mapping.schema().fields())
                .extracting(f -> f.type().typeName())
                .containsExactly(TypeName.INT64, TypeName.DECIMAL, TypeName.TIMESTAMP_LTZ, TypeName.BYTES);
        assertThatThrownBy(() -> MySqlSchema.resolve(OPTIONS, List.of(column("json", "json"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("column 'c' of shop.customers is json");
        assertThatThrownBy(() -> MySqlSchema.resolve(OPTIONS, List.of())).hasMessageContaining("does not exist");
    }
}

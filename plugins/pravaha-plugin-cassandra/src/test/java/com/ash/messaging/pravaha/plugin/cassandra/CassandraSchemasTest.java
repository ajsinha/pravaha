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
package com.ash.messaging.pravaha.plugin.cassandra;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link CassandraSchemas}'s type mapping and refusals, exercised without a server. */
class CassandraSchemasTest {

    @Test
    void mapsEveryDeclaredType() {
        StreamSchema schema = CassandraSchemas.parse(
                "t",
                "a:BOOLEAN,b:TINYINT,c:SMALLINT,d:INT,e:BIGINT,f:FLOAT,g:DOUBLE,h:TEXT,i:BLOB,"
                        + "j:TIMESTAMP,k:UUID,l:COUNTER");
        assertThat(schema.field(0).type().typeName()).isEqualTo(TypeName.BOOLEAN);
        assertThat(schema.field(1).type().typeName()).isEqualTo(TypeName.INT8);
        assertThat(schema.field(2).type().typeName()).isEqualTo(TypeName.INT16);
        assertThat(schema.field(3).type().typeName()).isEqualTo(TypeName.INT32);
        assertThat(schema.field(4).type().typeName()).isEqualTo(TypeName.INT64);
        assertThat(schema.field(5).type().typeName()).isEqualTo(TypeName.FLOAT32);
        assertThat(schema.field(6).type().typeName()).isEqualTo(TypeName.FLOAT64);
        assertThat(schema.field(7).type().typeName()).isEqualTo(TypeName.STRING);
        assertThat(schema.field(8).type().typeName()).isEqualTo(TypeName.BYTES);
        assertThat(schema.field(9).type().typeName()).isEqualTo(TypeName.TIMESTAMP_LTZ);
        assertThat(schema.field(10).type().typeName())
                .as("UUID has no equivalent physical type here and reads as STRING")
                .isEqualTo(TypeName.STRING);
        assertThat(schema.field(11).type().typeName())
                .as("COUNTER is Cassandra's 64-bit counter column and reads as INT64")
                .isEqualTo(TypeName.INT64);
    }

    @Test
    void aTrailingQuestionMarkIsNullable() {
        StreamSchema schema = CassandraSchemas.parse("t", "a:INT64?,b:INT64");
        assertThat(schema.field(0).type().nullable()).isTrue();
        assertThat(schema.field(1).type().nullable()).isFalse();
    }

    @Test
    void decimalAndVarintAreRefusedRatherThanApproximated() {
        assertThatThrownBy(() -> CassandraSchemas.parse("t", "a:DECIMAL"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("lossless")
                .hasMessageContaining("TEXT");
        assertThatThrownBy(() -> CassandraSchemas.parse("t", "a:VARINT"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("lossless");
    }

    @Test
    void anUnknownTypeNamesWhatIsSupported() {
        assertThatThrownBy(() -> CassandraSchemas.parse("t", "a:MAP"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("unknown type")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void aMalformedEntryNamesTheExpectedShape() {
        assertThatThrownBy(() -> CassandraSchemas.parse("t", "not-a-pair"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name:TYPE");
    }
}

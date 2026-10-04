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
package com.ash.messaging.pravaha.plugin.aerospike;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AerospikeSchemas}'s type mapping and refusals, exercised without a server -- reachable only
 * through {@code AerospikePluginIT} until now, the same gap {@code CassandraSchemasTest} closes for
 * the Cassandra plugin.
 */
class AerospikeSchemasTest {

    @Test
    void mapsEveryDeclaredType() {
        StreamSchema schema = AerospikeSchemas.parse(
                "t", "a:BOOLEAN,b:INT8,c:INT16,d:INT32,e:INT64,f:FLOAT32,g:FLOAT64,h:STRING,i:BYTES,j:TIMESTAMP");
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
    }

    @Test
    void aliasSpellingsMapToTheSamePhysicalType() {
        StreamSchema schema =
                AerospikeSchemas.parse("t", "a:BOOL,b:BYTE,c:SHORT,d:INT,e:LONG,f:FLOAT,g:DOUBLE,h:TEXT,i:BLOB");
        assertThat(schema.field(0).type().typeName()).isEqualTo(TypeName.BOOLEAN);
        assertThat(schema.field(1).type().typeName()).isEqualTo(TypeName.INT8);
        assertThat(schema.field(2).type().typeName()).isEqualTo(TypeName.INT16);
        assertThat(schema.field(3).type().typeName()).isEqualTo(TypeName.INT32);
        assertThat(schema.field(4).type().typeName()).isEqualTo(TypeName.INT64);
        assertThat(schema.field(5).type().typeName()).isEqualTo(TypeName.FLOAT32);
        assertThat(schema.field(6).type().typeName()).isEqualTo(TypeName.FLOAT64);
        assertThat(schema.field(7).type().typeName()).isEqualTo(TypeName.STRING);
        assertThat(schema.field(8).type().typeName()).isEqualTo(TypeName.BYTES);
    }

    @Test
    void aTrailingQuestionMarkIsNullable() {
        StreamSchema schema = AerospikeSchemas.parse("t", "a:INT64?,b:INT64");
        assertThat(schema.field(0).type().nullable()).isTrue();
        assertThat(schema.field(1).type().nullable()).isFalse();
    }

    @Test
    void decimalIsRefusedRatherThanStoredAsALossyDouble() {
        assertThatThrownBy(() -> AerospikeSchemas.parse("t", "a:DECIMAL"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Aerospike has no type for")
                .hasMessageContaining("INT64");
    }

    @Test
    void anUnknownTypeNamesWhatIsSupported() {
        assertThatThrownBy(() -> AerospikeSchemas.parse("t", "a:MAP"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("unknown type")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void aMalformedEntryNamesTheExpectedShape() {
        assertThatThrownBy(() -> AerospikeSchemas.parse("t", "not-a-pair"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name:TYPE");
    }

    @Test
    void aBinNameOver15BytesIsRefusedAtRegistrationRatherThanOnTheFirstWrite() {
        String tooLong = "a".repeat(AerospikeSchemas.MAX_BIN_NAME_BYTES + 1);
        assertThatThrownBy(() -> AerospikeSchemas.parse("t", tooLong + ":INT64"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("15-byte limit");
    }

    @Test
    void aBinNameAtExactlyTheLimitIsAccepted() {
        String exact = "a".repeat(AerospikeSchemas.MAX_BIN_NAME_BYTES);
        StreamSchema schema = AerospikeSchemas.parse("t", exact + ":INT64");
        assertThat(schema.field(0).name()).isEqualTo(exact);
    }

    @Test
    void aTrailingSeparatorIsRefusedLikeAnyOtherMalformedEntry() {
        // SPLITTRAIL-1: "id:INT64," and "id:INT64:" used to parse as "id:INT64".
        assertThatThrownBy(() -> AerospikeSchemas.parse("t", "id:INT64,"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name:TYPE");
        assertThatThrownBy(() -> AerospikeSchemas.parse("t", "id:INT64:"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name:TYPE");
    }
}

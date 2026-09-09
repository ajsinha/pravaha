/*
 * Copyright the Pravaha authors.
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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamSchemaTest {

    private static StreamSchema txnSchema() {
        return StreamSchema.builder("txn_stream")
                .field("txn_id", Types.string())
                .field("user_id", Types.string())
                .field("amount", Types.decimal(18, 4))
                .field("status", Types.string(16))
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .primaryKeyField("txn_id")
                .build();
    }

    @Test
    void ordinalsAreAssignedByInsertionOrder() {
        StreamSchema s = txnSchema();
        assertThat(s.fieldCount()).isEqualTo(5);
        for (int i = 0; i < s.fieldCount(); i++) {
            assertThat(s.field(i).ordinal()).isEqualTo(i);
        }
        assertThat(s.indexOf("txn_id")).isZero();
        assertThat(s.indexOf("event_time")).isEqualTo(4);
    }

    @Test
    void unknownFieldFailsLoudlyWithTheAvailableNames() {
        // Ordinals are resolved once at registration, so a miss here is a bug worth shouting about.
        assertThatThrownBy(() -> txnSchema().indexOf("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("txn_id");
    }

    @Test
    void exposesEventTimeAndPrimaryKey() {
        StreamSchema s = txnSchema();
        assertThat(s.eventTimeOrdinal()).hasValue(4);
        assertThat(s.primaryKey()).containsExactly("txn_id");
        assertThat(s.hasField("amount")).isTrue();
        assertThat(s.hasField("absent")).isFalse();
    }

    @Test
    void eventTimeIsOptional() {
        StreamSchema s = StreamSchema.builder("s").field("a", Types.int32()).build();
        assertThat(s.eventTimeOrdinal()).isEmpty();
        assertThat(s.primaryKey()).isEmpty();
    }

    @Test
    void eventTimeMustBeATimestamp() {
        assertThatThrownBy(() -> StreamSchema.builder("s")
                        .field("a", Types.int64())
                        .eventTime("a")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be TIMESTAMP");
    }

    @Test
    void unknownEventTimeOrPrimaryKeyFieldIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> StreamSchema.builder("s")
                        .field("a", Types.int32())
                        .eventTime("b")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event-time field");
        assertThatThrownBy(() -> StreamSchema.builder("s")
                        .field("a", Types.int32())
                        .primaryKeyField("b")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primary-key field");
    }

    @Test
    void rejectsBlankSchemaName() {
        assertThatThrownBy(() -> StreamSchema.builder(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsVersionBelowOne() {
        assertThatThrownBy(() -> StreamSchema.builder("s").version(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldsAreImmutable() {
        List<Field> fields = txnSchema().fields();
        assertThatThrownBy(() -> fields.add(Field.of("x", Types.int32())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evolveBumpsTheVersionAndPreservesMetadata() {
        // A running query keeps the version it was planned against (design section 11.4).
        StreamSchema v1 = txnSchema();
        List<Field> plusOne = new java.util.ArrayList<>(v1.fields());
        plusOne.add(new Field("channel", Types.string().withNullable(true), 5));
        StreamSchema v2 = v1.evolve(plusOne);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.fieldCount()).isEqualTo(6);
        assertThat(v2.eventTimeOrdinal()).hasValue(4);
        assertThat(v2.primaryKey()).containsExactly("txn_id");
        assertThat(v1.fieldCount()).isEqualTo(5);
    }

    @Test
    void equalityIsByValueIncludingVersion() {
        assertThat(txnSchema()).isEqualTo(txnSchema()).hasSameHashCodeAs(txnSchema());
        assertThat(txnSchema()).isNotEqualTo(txnSchema().evolve(txnSchema().fields()));
        assertThat(txnSchema()).isNotEqualTo("not a schema");
        assertThat(txnSchema()).isEqualTo(txnSchema());
    }

    @Test
    void toStringNamesTheSchemaVersionAndColumns() {
        assertThat(txnSchema().toString())
                .startsWith("txn_stream v1 (")
                .contains("amount DECIMAL(18, 4) NOT NULL")
                .contains("status VARCHAR(16) NOT NULL");
    }
}

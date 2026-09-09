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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldTest {

    @Test
    void rejectsBlankNames() {
        assertThatThrownBy(() -> new Field("  ", Types.int32(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsNegativeOrdinals() {
        assertThatThrownBy(() -> new Field("a", Types.int32(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new Field(null, Types.int32(), 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Field("a", null, 0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofDefaultsTheOrdinalToZero() {
        assertThat(Field.of("a", Types.int32()).ordinal()).isZero();
    }

    @Test
    void withOrdinalIsIdentityWhenUnchanged() {
        Field f = new Field("a", Types.int32(), 3);
        assertThat(f.withOrdinal(3)).isSameAs(f);
        assertThat(f.withOrdinal(4).ordinal()).isEqualTo(4);
    }
}

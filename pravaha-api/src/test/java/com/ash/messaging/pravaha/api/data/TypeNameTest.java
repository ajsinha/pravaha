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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TypeNameTest {

    @ParameterizedTest
    @EnumSource(TypeName.class)
    void widthIsEitherPositiveOrTheVariableSentinel(TypeName t) {
        assertThat(t.fixedWidth())
                .satisfiesAnyOf(
                        w -> assertThat(w).isEqualTo(TypeName.VARIABLE),
                        w -> assertThat(w).isPositive());
    }

    @ParameterizedTest
    @EnumSource(TypeName.class)
    void isFixedWidthAgreesWithTheWidth(TypeName t) {
        assertThat(t.isFixedWidth()).isEqualTo(t.fixedWidth() != TypeName.VARIABLE);
    }

    @Test
    void variableWidthTypesUseTheSentinelSpelledLiterallyInTheConstantList() {
        // TypeName cannot reference VARIABLE from its constant list (illegal forward reference),
        // so the literal and the constant are kept in step by hand. This asserts they still agree.
        assertThat(TypeName.STRING.fixedWidth()).isEqualTo(TypeName.VARIABLE);
        assertThat(TypeName.BYTES.fixedWidth()).isEqualTo(TypeName.VARIABLE);
        assertThat(TypeName.ARRAY.fixedWidth()).isEqualTo(TypeName.VARIABLE);
        assertThat(TypeName.MAP.fixedWidth()).isEqualTo(TypeName.VARIABLE);
        assertThat(TypeName.ROW.fixedWidth()).isEqualTo(TypeName.VARIABLE);
    }

    @Test
    void scalarWidthsMatchTheDesignTypeTable() {
        assertThat(TypeName.BOOLEAN.fixedWidth()).isEqualTo(1);
        assertThat(TypeName.INT32.fixedWidth()).isEqualTo(4);
        assertThat(TypeName.INT64.fixedWidth()).isEqualTo(8);
        assertThat(TypeName.FLOAT64.fixedWidth()).isEqualTo(8);
        assertThat(TypeName.DECIMAL.fixedWidth()).isEqualTo(16);
        assertThat(TypeName.DATE.fixedWidth()).isEqualTo(4);
        assertThat(TypeName.TIMESTAMP_LTZ.fixedWidth()).isEqualTo(8);
    }
}

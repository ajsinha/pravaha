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

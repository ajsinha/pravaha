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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowKindTest {

    @Test
    void additionsCarryPositiveWeightAndRemovalsNegative() {
        assertThat(RowKind.INSERT.weight()).isEqualTo(1L);
        assertThat(RowKind.UPDATE_AFTER.weight()).isEqualTo(1L);
        assertThat(RowKind.UPDATE_BEFORE.weight()).isEqualTo(-1L);
        assertThat(RowKind.DELETE.weight()).isEqualTo(-1L);
    }

    @ParameterizedTest
    @EnumSource(RowKind.class)
    void everyKindHasATwoCharacterShorthand(RowKind kind) {
        assertThat(kind.shorthand()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(RowKind.class)
    void isAdditionAgreesWithTheSignOfTheWeight(RowKind kind) {
        assertThat(kind.isAddition()).isEqualTo(kind.weight() > 0);
    }

    @Test
    void ofWeightMapsSignToKind() {
        assertThat(RowKind.ofWeight(1)).isEqualTo(RowKind.INSERT);
        assertThat(RowKind.ofWeight(42)).isEqualTo(RowKind.INSERT);
        assertThat(RowKind.ofWeight(-1)).isEqualTo(RowKind.DELETE);
        assertThat(RowKind.ofWeight(-7)).isEqualTo(RowKind.DELETE);
    }

    @Test
    void zeroWeightHasNoRepresentation() {
        // A consolidated row must never reach a sink; failing loudly here is the point.
        assertThatThrownBy(() -> RowKind.ofWeight(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consolidated");
    }
}

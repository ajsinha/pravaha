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

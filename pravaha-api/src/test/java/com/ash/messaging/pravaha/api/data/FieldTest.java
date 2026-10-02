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
    // Nulls on purpose: what is tested is the refusal a caller outside NullAway meets.
    @SuppressWarnings("NullAway")
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

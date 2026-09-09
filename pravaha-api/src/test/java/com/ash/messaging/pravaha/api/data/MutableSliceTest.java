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

class MutableSliceTest {

    @Test
    void startsEmpty() {
        MutableSlice s = new MutableSlice();
        assertThat(s.isEmpty()).isTrue();
        assertThat(s.offset()).isZero();
        assertThat(s.length()).isZero();
    }

    @Test
    void wrapRepointsAndReturnsItself() {
        MutableSlice s = new MutableSlice();
        assertThat(s.wrap(4096, 12)).isSameAs(s);
        assertThat(s.offset()).isEqualTo(4096);
        assertThat(s.length()).isEqualTo(12);
        assertThat(s.isEmpty()).isFalse();
    }

    @Test
    void wrapIsReusableSoReadingCostsNoAllocation() {
        MutableSlice s = new MutableSlice();
        s.wrap(1, 1).wrap(2, 2).wrap(3, 3);
        assertThat(s.offset()).isEqualTo(3);
        assertThat(s.length()).isEqualTo(3);
    }

    @Test
    void clearResetsToEmpty() {
        MutableSlice s = new MutableSlice().wrap(32, 8);
        assertThat(s.clear().isEmpty()).isTrue();
        assertThat(s.offset()).isZero();
    }

    @Test
    void rejectsNegativeOffsetOrLength() {
        assertThatThrownBy(() -> new MutableSlice().wrap(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MutableSlice().wrap(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringIsDiagnosable() {
        assertThat(new MutableSlice().wrap(171, 4).toString()).contains("171").contains("4B");
    }
}

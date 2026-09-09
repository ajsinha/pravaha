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
package com.ash.messaging.pravaha.algebra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontierTest {

    @Test
    void startsUnboundedAndCanReachComplete() {
        assertThat(Frontier.INITIAL.isInitial()).isTrue();
        assertThat(Frontier.COMPLETE.isComplete()).isTrue();
        assertThat(Frontier.INITIAL.isAtOrBefore(Frontier.COMPLETE)).isTrue();
    }

    @Test
    void refusesToRegress() {
        // A frontier that could move backwards would let an operator un-finalise a result it has
        // already emitted.
        Frontier f = Frontier.at(1_000);
        assertThatThrownBy(() -> f.advanceTo(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot regress");
        assertThat(f.advanceTo(1_000)).isSameAs(f);
        assertThat(f.advanceTo(2_000).nanos()).isEqualTo(2_000);
    }

    @Test
    void meetTakesTheLeastAdvancedInput() {
        // An operator is only as advanced as its slowest input; it cannot finalise anything a slow
        // input might still contradict.
        Frontier slow = Frontier.at(100);
        Frontier fast = Frontier.at(900);
        assertThat(slow.meet(fast)).isEqualTo(slow);
        assertThat(fast.meet(slow)).isEqualTo(slow);
        assertThat(slow.join(fast)).isEqualTo(fast);
    }

    @Test
    void closureIsStrictlyBeforeTheFrontier() {
        Frontier f = Frontier.at(1_000);
        assertThat(f.isClosed(999)).isTrue();
        assertThat(f.isClosed(1_000))
                .as("a timestamp at the frontier may still receive input")
                .isFalse();
        assertThat(f.isClosed(1_001)).isFalse();
        assertThat(Frontier.COMPLETE.isClosed(Long.MAX_VALUE - 1)).isTrue();
    }

    @Test
    void ordersAndComparesByValue() {
        assertThat(Frontier.at(5)).isEqualTo(Frontier.at(5)).hasSameHashCodeAs(Frontier.at(5));
        assertThat(Frontier.at(5)).isLessThan(Frontier.at(6));
        assertThat(Frontier.at(5)).isNotEqualTo("not a frontier");
        assertThat(Frontier.INITIAL).hasToString("Frontier[initial]");
        assertThat(Frontier.COMPLETE).hasToString("Frontier[complete]");
        assertThat(Frontier.at(42)).hasToString("Frontier[42]");
    }
}

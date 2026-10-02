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
package com.ash.messaging.pravaha.identity;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** LOCKENUM-1: failures bar the (account, source) they came from, for a bounded time, in bounded memory. */
class SignInThrottleTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final SignInThrottle throttle = new SignInThrottle(5, Duration.ofMinutes(15), Duration.ofMinutes(30));

    @Test
    void theFifthFailureBarsThatSourceForThatAccountOnly() {
        for (int i = 0; i < 4; i++) {
            assertThat(throttle.failed("ana", "a", T0)).isFalse();
        }
        assertThat(throttle.failed("ana", "a", T0)).isTrue();
        assertThat(throttle.barred("ana", "a", T0.plusSeconds(60))).isTrue();
        assertThat(throttle.barred("ana", "b", T0.plusSeconds(60))).isFalse();
        assertThat(throttle.barred("bob", "a", T0.plusSeconds(60))).isFalse();
        assertThat(throttle.barred("ana", "a", T0.plus(Duration.ofMinutes(31)))).isFalse();
    }

    @Test
    void failuresOutsideTheWindowStartTheCountAgainAndASuccessClearsIt() {
        for (int i = 0; i < 4; i++) {
            throttle.failed("ana", "a", T0);
        }
        assertThat(throttle.failed("ana", "a", T0.plus(Duration.ofMinutes(16)))).isFalse();
        for (int i = 0; i < 3; i++) {
            throttle.failed("ana", "a", T0.plus(Duration.ofMinutes(16)));
        }
        throttle.succeeded("ana", "a");
        assertThat(throttle.failed("ana", "a", T0.plus(Duration.ofMinutes(17)))).isFalse();
    }

    @Test
    void theTableIsBoundedAndForgetsWhatHasExpiredFirst() {
        for (int i = 0; i < SignInThrottle.MAX_TRACKED; i++) {
            throttle.failed("ana", "s" + i, T0);
        }
        assertThat(throttle.tracked()).isEqualTo(SignInThrottle.MAX_TRACKED);
        assertThat(throttle.failed("ana", "late", T0.plusSeconds(1))).isFalse();
        assertThat(throttle.tracked()).isEqualTo(SignInThrottle.MAX_TRACKED);
        throttle.failed("ana", "later", T0.plus(Duration.ofMinutes(16)));
        assertThat(throttle.tracked()).isEqualTo(1);
    }
}

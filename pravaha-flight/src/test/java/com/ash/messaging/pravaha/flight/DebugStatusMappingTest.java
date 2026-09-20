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
package com.ash.messaging.pravaha.flight;

import org.apache.arrow.flight.CallStatus;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a debug session's refusals look like to a gRPC client (FLIGHT-1).
 *
 * <p>The debugger's six codes reached {@code statusFor} through its default arm, so every one of
 * them arrived as {@code INVALID_ARGUMENT} -- including a session that had expired and a node
 * already holding its ceiling of sessions. That is the shape {@code statusFor}'s own javadoc says
 * must not happen: a saturated node looking like a malformed query. A client retries a saturated
 * node and does not retry a bad request, so the two cannot share a status.
 *
 * <p>The other four stay {@code INVALID_ARGUMENT} deliberately: no checkpoint to fork from, a
 * source that cannot replay, an unreadable step, are all things the caller can correct.
 */
class DebugStatusMappingTest {

    private static CallStatus statusOf(int code, String name) {
        return FlightErrors.statusFor(new PravahaException(new ErrorCode(code, name), "for the test"));
    }

    @Test
    void aSessionThatHasEndedIsNotFoundRatherThanMalformed() {
        assertThat(statusOf(8013, "DEBUG_NO_SUCH_SESSION").code()).isEqualTo(CallStatus.NOT_FOUND.code());
        assertThat(statusOf(8016, "DEBUG_QUERY_GONE").code()).isEqualTo(CallStatus.NOT_FOUND.code());
    }

    @Test
    void aNodeAtItsSessionCeilingIsSaturatedRatherThanMalformed() {
        assertThat(statusOf(8014, "DEBUG_TOO_MANY_SESSIONS").code())
                .as("a client retries a saturated node; it does not retry a bad request")
                .isEqualTo(CallStatus.RESOURCE_EXHAUSTED.code());
    }

    @Test
    void whatTheCallerCanCorrectStaysAnInvalidArgument() {
        assertThat(statusOf(8011, "DEBUG_NO_CHECKPOINT").code()).isEqualTo(CallStatus.INVALID_ARGUMENT.code());
        assertThat(statusOf(8012, "DEBUG_SOURCE_NOT_REPLAYABLE").code()).isEqualTo(CallStatus.INVALID_ARGUMENT.code());
        assertThat(statusOf(8015, "DEBUG_BAD_STEP").code()).isEqualTo(CallStatus.INVALID_ARGUMENT.code());
    }
}

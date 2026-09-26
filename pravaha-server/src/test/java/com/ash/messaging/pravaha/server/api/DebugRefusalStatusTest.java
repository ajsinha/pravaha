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
package com.ash.messaging.pravaha.server.api;

import java.util.List;
import java.util.Map;

import org.apache.arrow.flight.FlightStatusCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.flight.FlightErrors;
import com.ash.messaging.pravaha.registry.DebugErrors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DBG-2: every debugger refusal answers the same status over HTTP as over Flight, read through the
 * usual gRPC-to-HTTP correspondence. Derived from {@link FlightErrors#statusFor} rather than
 * restated, so the two cannot drift apart again without this failing.
 */
class DebugRefusalStatusTest {

    private static final Map<FlightStatusCode, HttpStatus> HTTP_FOR_FLIGHT = Map.of(
            FlightStatusCode.NOT_FOUND, HttpStatus.NOT_FOUND,
            FlightStatusCode.RESOURCE_EXHAUSTED, HttpStatus.TOO_MANY_REQUESTS,
            FlightStatusCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST);

    @Test
    void everyDebuggerRefusalAnswersOverHttpWhatItAnswersOverFlight() {
        List<ErrorCode> codes = List.of(
                DebugErrors.NO_CHECKPOINT,
                DebugErrors.SOURCE_NOT_REPLAYABLE,
                DebugErrors.NO_SUCH_SESSION,
                DebugErrors.TOO_MANY_SESSIONS,
                DebugErrors.BAD_STEP,
                DebugErrors.QUERY_GONE);
        for (ErrorCode code : codes) {
            FlightStatusCode flight = FlightErrors.statusFor(new PravahaException(code, "a refusal"))
                    .code();
            assertThat(HTTP_FOR_FLIGHT)
                    .as("%s answers Flight %s, which this test has no HTTP status for", code, flight)
                    .containsKey(flight);
            assertThat(ApiExceptionHandler.statusFor(code))
                    .as("%s: Flight answers %s", code, flight)
                    .isEqualTo(HTTP_FOR_FLIGHT.get(flight));
        }
    }
}

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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightMethod;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.RequestContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * J21-1: a custom token verifier that fails other than by refusing is an unauthenticated call
 * carrying {@code PRV-7001}, not an exception gRPC turns into {@code UNKNOWN} with no code.
 *
 * <p>Arrow's interceptor catches only {@link FlightRuntimeException} from a middleware factory;
 * anything else escaped to gRPC, which closed the call {@code UNKNOWN} -- the one status a client
 * cannot act on -- while the HTTP filter and the PostgreSQL gateway's per-statement check already
 * treated a failing verifier as a refusal (closed, not open).
 */
class FlightVerifierFailureTest {

    @Test
    void aVerifierThatThrowsIsAnUnauthenticatedCallWithTheEnginesCode() {
        PrincipalMiddleware.Factory factory = new PrincipalMiddleware.Factory(token -> {
            throw new IllegalStateException("idp.internal:8443 refused the connection");
        });
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer some-token");

        FlightRuntimeException refused = catchThrowableOfType(
                FlightRuntimeException.class,
                () -> factory.onCallStarted(new CallInfo(FlightMethod.DO_GET), headers, new Context()));

        assertThat(refused)
                .as("a FlightRuntimeException, which Arrow turns into a status")
                .isNotNull();
        assertThat(refused.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED);
        assertThat(refused.getMessage()).contains("PRV-7001").doesNotContain("idp.internal");
    }

    private static final class Context implements RequestContext {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public @Nullable String get(String key) {
            return values.get(key);
        }

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }

        @Override
        public @Nullable String remove(String key) {
            return values.remove(key);
        }
    }
}

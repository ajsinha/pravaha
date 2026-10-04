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
package com.ash.messaging.pravaha.sdk.flight;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CONNECTTIMEOUT-1: {@code connectTimeout} is deprecated because nothing can read it, and it is
 * {@code requestTimeout} that bounds connecting.
 *
 * <p>The "server" is a listening socket that is never accepted from: the kernel completes the TCP
 * handshake, and then nothing ever speaks HTTP/2 -- a node that is reachable but wedged, the case a
 * connect timeout exists for. The first call, which is where the connection is made, gives up at the
 * request deadline even though the deprecated setting asks for five minutes.
 */
@Timeout(60)
class JavaSdkConnectTimeoutTest {

    @Test
    @SuppressWarnings("deprecation") // the deprecated setting is the subject of the test
    void requestTimeoutBoundsConnectingAndTheDeprecatedSettingDoesNot() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            ClientOptions options = ClientOptions.builder("grpc://localhost:" + silent.getLocalPort())
                    .connectTimeout(Duration.ofMinutes(5))
                    .requestTimeout(Duration.ofMillis(500))
                    .build();
            // Still accepted and still reported, so 2.x code that sets it compiles and runs.
            assertThat(options.connectTimeout()).isEqualTo(Duration.ofMinutes(5));

            long started = System.nanoTime();
            try (PravahaFlightClient client = PravahaFlightClient.connect(options)) {
                assertThatThrownBy(client::queries).isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ClientErrors.DEADLINE_EXCEEDED);
                    assertThat(e.getMessage()).contains("0.5 s");
                });
            }
            // Far short of the five-minute connectTimeout; loose enough for a loaded build machine,
            // where connecting and closing alone took over ten seconds.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(45));
        }
    }
}

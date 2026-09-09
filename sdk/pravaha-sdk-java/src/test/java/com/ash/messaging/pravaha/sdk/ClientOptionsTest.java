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
package com.ash.messaging.pravaha.sdk;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientOptionsTest {

    @Test
    void defaultsAreTheSafeChoicesNotTheFastestOnes() {
        // A client that quietly defaults to the loosest behaviour is how an application ends up
        // reporting numbers that do not reconcile, months before anyone notices.
        ClientOptions o = ClientOptions.builder("host:9090").build();
        assertThat(o.endpoint().tls()).isTrue();
        assertThat(o.defaultConsistency()).isEqualTo(Consistency.CONSISTENT);
        assertThat(o.subscriberBufferRows()).isPositive();
        assertThat(o.conflateOnOverflow()).isTrue();
        assertThat(o.token()).isEmpty();
    }

    @Test
    void overridesApply() {
        ClientOptions o = ClientOptions.builder("grpc+tls://host:9090")
                .token("secret-token")
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(5))
                .defaultConsistency(Consistency.LATEST)
                .subscriberBufferRows(50)
                .conflateOnOverflow(false)
                .applicationName("fraud-service")
                .build();

        assertThat(o.token()).hasValue("secret-token");
        assertThat(o.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(o.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(o.defaultConsistency()).isEqualTo(Consistency.LATEST);
        assertThat(o.subscriberBufferRows()).isEqualTo(50);
        assertThat(o.conflateOnOverflow()).isFalse();
        assertThat(o.applicationName()).isEqualTo("fraud-service");
    }

    @Test
    void refusesToSendATokenOverPlaintext() {
        // Sending a bearer token over plaintext hands it to anyone on the path. Refusing is less
        // convenient than warning, and considerably safer.
        assertThatThrownBy(() ->
                        ClientOptions.builder("grpc://host:9090").token("t").build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("plaintext")
                .hasMessageContaining("grpc+tls://");
    }

    @Test
    void plaintextWithoutATokenIsAllowedForLocalDevelopment() {
        assertThat(ClientOptions.builder("grpc://localhost:9090")
                        .build()
                        .endpoint()
                        .tls())
                .isFalse();
    }

    @Test
    void toStringNeverRendersTheToken() {
        // A ClientOptions reaching a log line must not leak the credential it carries.
        ClientOptions o =
                ClientOptions.builder("grpc+tls://h:9090").token("super-secret").build();
        assertThat(o.toString()).doesNotContain("super-secret").contains("authenticated");
        assertThat(ClientOptions.builder("grpc+tls://h:9090").build().toString())
                .doesNotContain("authenticated");
    }

    @Test
    void rejectsNonsensicalSettings() {
        var b = ClientOptions.builder("grpc+tls://h:9090");
        assertThatThrownBy(() -> b.subscriberBufferRows(0)).isInstanceOf(PravahaClientException.class);
        assertThatThrownBy(() -> b.connectTimeout(Duration.ZERO)).isInstanceOf(PravahaClientException.class);
        assertThatThrownBy(() -> b.requestTimeout(Duration.ofSeconds(-1))).isInstanceOf(PravahaClientException.class);
        assertThatThrownBy(() -> b.applicationName(" ")).isInstanceOf(PravahaClientException.class);
    }

    @Test
    void everyConsistencyModeIsRepresented() {
        // Mirrors design section 17.3; a mode added there without a client constant would be unreachable.
        assertThat(Consistency.values())
                .containsExactly(Consistency.LATEST, Consistency.CONSISTENT, Consistency.AS_OF, Consistency.AT_LEAST);
    }

    @Test
    void clientExceptionsCarryTheEngineErrorCodesAndARetryHint() {
        PravahaClientException e = new PravahaClientException(
                new com.ash.messaging.pravaha.api.ErrorCode(3001, "UNAVAILABLE"), "node is draining", true);
        assertThat(e.retryable()).isTrue();
        assertThat(e.getMessage()).startsWith("PRV-3001");
        assertThat(e.helpUrl()).endsWith("PRV-3001");
    }
}

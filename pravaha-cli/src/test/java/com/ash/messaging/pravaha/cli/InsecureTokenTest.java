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
package com.ash.messaging.pravaha.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CLI must not answer the plaintext-token question on the operator's behalf.
 *
 * <p>P-3. {@code ServerCommand.connect} called {@code .allowInsecureToken(true)} unconditionally,
 * on every server-talking command, on every invocation carrying {@code --token}, with no flag to
 * opt out. The SDK's own refusal — which {@code ClientOptions.Builder.build()} does enforce, and
 * has all along — was therefore switched off for every CLI user, and nothing printed a word about
 * it. A bearer token went to {@code grpc://} in clear text and the tool that sent it had decided
 * that was acceptable without asking.
 *
 * <p>These tests drive {@code ClientOptions} the way {@code connect} now does, rather than opening a
 * socket: what is under test is which flag the CLI passes, and that is decided before any
 * connection is attempted.
 */
class InsecureTokenTest {

    private static final String PLAINTEXT = "grpc://localhost:9090";
    private static final String ENCRYPTED = "grpc+tls://localhost:9090";

    /** Exactly what {@code ServerCommand.connect} does with the parsed arguments. */
    private static ClientOptions optionsFor(String... arguments) {
        Args args = Args.parse(List.of(arguments));
        String url = args.get("url", "grpc://localhost:9090");
        ClientOptions.Builder options = ClientOptions.builder(url);
        args.get("token").ifPresent(token -> options.token(token).allowInsecureToken(args.has("insecure-token")));
        return options.build();
    }

    @Test
    void aTokenOverPlaintextIsRefusedUnlessTheOperatorSaysOtherwise() {
        assertThatThrownBy(() -> optionsFor("--url", PLAINTEXT, "--token", "secret"))
                .as("the SDK has always refused this; the CLI was switching the refusal off for "
                        + "every one of its users, silently")
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void theOperatorCanStillSaySoDeliberately() {
        // Refusing outright would break loopback and sidecar-TLS deployments, which are the cases
        // the escape hatch exists for. The point is that it has to be typed.
        assertThatCode(() -> optionsFor("--url", PLAINTEXT, "--token", "secret", "--insecure-token"))
                .doesNotThrowAnyException();

        assertThat(optionsFor("--url", PLAINTEXT, "--token", "secret", "--insecure-token")
                        .allowInsecureToken())
                .isTrue();
    }

    @Test
    void aTokenOverTlsNeedsNoFlagAtAll() {
        assertThatCode(() -> optionsFor("--url", ENCRYPTED, "--token", "secret"))
                .as("the ordinary secure case must stay ordinary, or operators learn to pass the "
                        + "escape hatch everywhere and it stops meaning anything")
                .doesNotThrowAnyException();
    }

    @Test
    void noTokenOverPlaintextIsStillFine() {
        // An unauthenticated node on a loopback socket is a normal development setup, and this
        // check is about the token, not about the transport.
        assertThatCode(() -> optionsFor("--url", PLAINTEXT)).doesNotThrowAnyException();
    }
}

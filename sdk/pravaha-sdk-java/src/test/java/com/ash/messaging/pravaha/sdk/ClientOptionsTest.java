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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
                // CONSISTENT, because it is the only mode the server implements: I-6 made the
                // other three refuse rather than be silently downgraded. This case is about the
                // builder carrying overrides through, not about which modes exist.
                .defaultConsistency(Consistency.CONSISTENT)
                .subscriberBufferRows(50)
                .conflateOnOverflow(false)
                .applicationName("fraud-service")
                .build();

        assertThat(o.token()).hasValue("secret-token");
        assertThat(o.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(o.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(o.defaultConsistency()).isEqualTo(Consistency.CONSISTENT);
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

    @Test
    void anUnimplementedConsistencyModeIsRefusedRatherThanSilentlyDowngraded() {
        // I-6. LATEST, AT_LEAST and AS_OF are declared by this SDK and implemented by nothing:
        // ViewQuery reads committed state only, and ServedView.get(Consistency, ...) has no
        // transport caller. A client asking for LATEST was served CONSISTENT -- a different answer
        // than the one requested, with nothing anywhere to say so.
        //
        // Refused rather than implemented, because wiring the modes through the Flight surface is
        // real work and an answer nobody can tell is wrong is the worst kind this system produces.
        for (Consistency unimplemented :
                new Consistency[] {Consistency.LATEST, Consistency.AT_LEAST, Consistency.AS_OF}) {
            assertThatThrownBy(() -> ClientOptions.builder("grpc+tls://host:9090")
                            .defaultConsistency(unimplemented)
                            .build())
                    .as("%s must refuse rather than quietly become CONSISTENT", unimplemented)
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("not implemented")
                    .hasMessageContaining("CONSISTENT");
        }
    }

    @Test
    void theImplementedModeIsAccepted() {
        assertThat(ClientOptions.builder("grpc+tls://host:9090")
                        .defaultConsistency(Consistency.CONSISTENT)
                        .build()
                        .defaultConsistency())
                .isEqualTo(Consistency.CONSISTENT);
    }

    @Test
    void defaultTlsOptionsAreCarriedThrough() {
        ClientOptions o = ClientOptions.builder("grpc+tls://host:9090").build();
        assertThat(o.tls().isDefault()).isTrue();
    }

    @Test
    void tlsOptionsOnAPlaintextEndpointAreRefused() {
        // Certificate material configured for a connection that will not use TLS at all is exactly
        // the kind of setting that looks like it did something and did not.
        TlsOptions tls =
                TlsOptions.builder().caCertificate(Path.of("/tmp/ca.pem")).build();
        assertThatThrownBy(
                        () -> ClientOptions.builder("grpc://host:9090").tls(tls).build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void defaultTlsOptionsOnAPlaintextEndpointAreFine() {
        // The refusal above is about material that would silently do nothing, not about the mere
        // presence of a tls(...) call -- passing TlsOptions.defaults() explicitly must not be
        // punished the way configuring real material is.
        assertThat(ClientOptions.builder("grpc://host:9090")
                        .tls(TlsOptions.defaults())
                        .build()
                        .tls()
                        .isDefault())
                .isTrue();
    }

    @Test
    void disablingHostnameVerificationOnATlsConnectionDoesNotAlsoWaiveTheTokenRefusal() {
        // The two checks are independent -- Endpoint.tls() decides the token refusal, and hostname
        // verification is a property of the TLS handshake itself -- so disabling one must not read as
        // having disabled the other. On a real TLS endpoint, a token is fine either way.
        TlsOptions insecureHostnameCheck =
                TlsOptions.builder().disableHostnameVerificationInsecure(true).build();
        assertThat(ClientOptions.builder("grpc+tls://host:9090")
                        .tls(insecureHostnameCheck)
                        .token("t")
                        .build()
                        .token())
                .hasValue("t");
    }

    @Test
    void disablingHostnameVerificationOnAPlaintextEndpointIsStillRefusedForBeingPlaintext() {
        // Hostname verification only means anything once TLS is in use at all, so configuring it on
        // a grpc:// endpoint is refused by the same "TLS options on a plaintext endpoint" check as
        // any other TLS setting -- it does not open some separate path around the token refusal.
        TlsOptions insecureHostnameCheck =
                TlsOptions.builder().disableHostnameVerificationInsecure(true).build();
        assertThatThrownBy(() -> ClientOptions.builder("grpc://host:9090")
                        .tls(insecureHostnameCheck)
                        .token("t")
                        .build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void fromConfigBuildsAFullyTlsConfiguredClientFromAPlainMapAloneNoCodeEdit() {
        ClientOptions opts = ClientOptions.fromConfig(Map.of(
                "hosts", "db01:9090",
                "tls.enabled", "true",
                "tls.trust-store", "/tmp/truststore.p12",
                "tls.trust-store-password", "secret",
                "tls.trust-store-type", "PKCS12",
                "token", "t",
                "application-name", "config-driven-app"));
        assertThat(opts.endpoint().tls()).isTrue();
        assertThat(opts.tls().trustStore()).contains(Path.of("/tmp/truststore.p12"));
        assertThat(opts.token()).hasValue("t");
        assertThat(opts.applicationName()).isEqualTo("config-driven-app");
    }

    @Test
    void fromConfigWithTlsEnabledFalseTurnsTlsOffEvenWithLeftoverCertMaterial() {
        assertThatThrownBy(() -> ClientOptions.fromConfig(
                        Map.of("hosts", "db01:9090", "tls.enabled", "false", "tls.ca-certificate", "/tmp/ca.pem")))
                // Endpoint stays plaintext (tls.enabled won), so the leftover cert material is then
                // caught -- loudly, not silently dropped and not silently switching TLS back on -- by
                // the ordinary "TLS options on a plaintext endpoint" refusal.
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void applyConfigComposesWithDirectBuilderCallsLastCallWins() {
        ClientOptions opts = ClientOptions.builder(Endpoint.parse("grpc://host:9090"))
                .applyConfig(Map.of("application-name", "from-config"))
                .applicationName("from-code")
                .build();
        assertThat(opts.applicationName()).isEqualTo("from-code");
    }
}

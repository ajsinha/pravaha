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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointTest {

    @Test
    void parsesSchemeHostAndPort() {
        Endpoint e = Endpoint.parse("grpc+tls://db01.example.com:9090");
        assertThat(e.tls()).isTrue();
        assertThat(e.nodes()).singleElement().hasToString("db01.example.com:9090");
    }

    @Test
    void plaintextSchemeDisablesTls() {
        assertThat(Endpoint.parse("grpc://localhost:9090").tls()).isFalse();
        assertThat(Endpoint.parse("http://localhost:9090").tls()).isFalse();
    }

    @Test
    void tlsIsAssumedWhenTheSchemeIsOmitted() {
        // The safe reading of an ambiguous input. Defaulting to plaintext would mean a typo
        // silently downgrades the connection.
        Endpoint e = Endpoint.parse("host:9090");
        assertThat(e.tls()).isTrue();
        assertThat(e.nodes()).singleElement().hasToString("host:9090");
    }

    @Test
    void portDefaultsWhenOmitted() {
        assertThat(Endpoint.parse("grpc://host").nodes())
                .singleElement()
                .extracting(Endpoint.HostPort::port)
                .isEqualTo(Endpoint.DEFAULT_PORT);
    }

    @Test
    void parsesAListOfNodesForFailover() {
        Endpoint e = Endpoint.parse("grpc+tls://a:9090, b:9091 ,c:9092");
        assertThat(e.nodes()).hasSize(3);
        assertThat(e.nodes().get(1).host()).isEqualTo("b");
        assertThat(e.nodes().get(2).port()).isEqualTo(9092);
    }

    @Test
    void roundTripsThroughToString() {
        String text = "grpc+tls://a:9090,b:9091";
        assertThat(Endpoint.parse(Endpoint.parse(text).toString())).isEqualTo(Endpoint.parse(text));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "   ", "grpc://", "ftp://host:1", "host:notaport", "host:0", "host:70000", "a:9090,,b:9090"})
    void malformedInputFailsAtConstructionWithTheAcceptedForms(String text) {
        // A typo in a connection string should fail next to the code that supplied it, not fifteen
        // minutes later inside a request where the stack trace points somewhere unhelpful.
        assertThatThrownBy(() -> Endpoint.parse(text))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("grpc+tls://host:port");
    }

    @Test
    void malformedEndpointsAreNotRetryable() {
        assertThatThrownBy(() -> Endpoint.parse("nope://x"))
                .isInstanceOf(PravahaClientException.class)
                .extracting(e -> ((PravahaClientException) e).retryable())
                .isEqualTo(false);
    }

    @Test
    void equalityIsByValue() {
        assertThat(Endpoint.parse("grpc://a:1"))
                .isEqualTo(Endpoint.parse("grpc://a:1"))
                .hasSameHashCodeAs(Endpoint.parse("grpc://a:1"));
        assertThat(Endpoint.parse("grpc://a:1")).isNotEqualTo(Endpoint.parse("grpc+tls://a:1"));
        assertThat(Endpoint.parse("grpc://a:1")).isNotEqualTo("not an endpoint");
    }

    @Test
    void fromConfigPrefersAFullConnectionStringOverHostsAndTlsEnabled() {
        // The scheme in 'endpoint' already answers the TLS question explicitly; tls.enabled is not
        // even consulted, let alone allowed to override it.
        Endpoint e = Endpoint.fromConfig(Map.of("endpoint", "grpc://a:1", "tls.enabled", "true"));
        assertThat(e.tls()).isFalse();
    }

    @Test
    void fromConfigWithHostsAndExplicitTlsEnabledFalseStaysPlaintextEvenWithCertMaterialPresent() {
        // This is the rule the owner corrected in the connector loader's PluginTls: an explicit
        // tls.enabled must win in both directions, never be overridden by inferring "on" from a
        // leftover certificate setting.
        Endpoint e = Endpoint.fromConfig(
                Map.of("hosts", "a:1", "tls.enabled", "false", "tls.ca-certificate", "/tmp/ca.pem"));
        assertThat(e.tls()).isFalse();
    }

    @Test
    void fromConfigWithHostsAndExplicitTlsEnabledTrueTurnsTlsOnWithNoCertMaterialAtAll() {
        Endpoint e = Endpoint.fromConfig(Map.of("hosts", "a:1", "tls.enabled", "true"));
        assertThat(e.tls()).isTrue();
    }

    @Test
    void fromConfigWithHostsAndNoTlsEnabledInfersTlsFromCertMaterial() {
        Endpoint withMaterial = Endpoint.fromConfig(Map.of("hosts", "a:1", "tls.trust-store", "/tmp/ts.jks"));
        assertThat(withMaterial.tls()).isTrue();

        // Unlike parse("a:1"), which assumes TLS when a scheme is simply omitted, the "hosts" form
        // mirrors PluginTls's inference rule: nothing said at all means TLS was not asked for.
        Endpoint withoutMaterial = Endpoint.fromConfig(Map.of("hosts", "a:1"));
        assertThat(withoutMaterial.tls()).isFalse();
    }

    @Test
    void fromConfigRequiresEitherEndpointOrHosts() {
        assertThatThrownBy(() -> Endpoint.fromConfig(Map.of("token", "x")))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("neither 'endpoint' nor 'hosts'");
    }

    @Test
    void fromConfigRejectsATlsEnabledValueThatIsNeitherTrueNorFalse() {
        assertThatThrownBy(() -> Endpoint.fromConfig(Map.of("hosts", "a:1", "tls.enabled", "maybe")))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("neither true nor false");
    }
}

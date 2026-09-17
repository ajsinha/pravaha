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
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsOptionsTest {

    private static final Path SOME_PATH = Path.of("/tmp/does-not-need-to-exist.pem");

    @Test
    void defaultsVerifyHostnameAndCarryNoMaterial() {
        TlsOptions tls = TlsOptions.defaults();
        assertThat(tls.verifyHostname()).isTrue();
        assertThat(tls.hasPemMaterial()).isFalse();
        assertThat(tls.hasKeystoreMaterial()).isFalse();
        assertThat(tls.isDefault()).isTrue();
        assertThat(tls.overrideHostname()).isEmpty();
    }

    @Test
    void aClientCertificateWithoutItsKeyIsRefusedNamingWhatIsMissing() {
        // CFG-6's shape, on the client's side: PravahaFlightServer.encryptedWith null-checks both
        // halves before either is dereferenced, and this is the same discipline.
        assertThatThrownBy(
                        () -> TlsOptions.builder().clientCertificate(SOME_PATH).build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("only the certificate")
                .hasMessageContaining("clientKey");
    }

    @Test
    void aClientKeyWithoutItsCertificateIsRefusedNamingWhatIsMissing() {
        assertThatThrownBy(() -> TlsOptions.builder().clientKey(SOME_PATH).build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("only the private key")
                .hasMessageContaining("clientCertificate");
    }

    @Test
    void givingBothPemAndAKeystoreIsRefusedRatherThanPreferringOne() {
        assertThatThrownBy(() -> TlsOptions.builder()
                        .caCertificate(SOME_PATH)
                        .trustStore(SOME_PATH, "changeit", "PKCS12")
                        .build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("both PEM material")
                .hasMessageContaining("a keystore");
    }

    @Test
    void aClientCertificateAndAKeyStoreTogetherAreAlsoRefused() {
        assertThatThrownBy(() -> TlsOptions.builder()
                        .clientCertificate(SOME_PATH)
                        .clientKey(SOME_PATH)
                        .keyStore(SOME_PATH, "changeit", "JKS")
                        .build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("both PEM material");
    }

    @Test
    void aMatchedClientCertificateAndKeyAreAccepted() {
        TlsOptions tls = TlsOptions.builder()
                .clientCertificate(SOME_PATH)
                .clientKey(SOME_PATH)
                .build();
        assertThat(tls.clientCertificate()).contains(SOME_PATH);
        assertThat(tls.clientKey()).contains(SOME_PATH);
        assertThat(tls.hasPemMaterial()).isTrue();
    }

    @Test
    void trustStoreBundlesPathPasswordAndTypeSoNoneCanBeSetAlone() {
        TlsOptions tls =
                TlsOptions.builder().trustStore(SOME_PATH, "secret", "pkcs12").build();
        assertThat(tls.trustStore()).contains(SOME_PATH);
        assertThat(tls.trustStorePassword()).contains("secret");
        assertThat(tls.trustStoreType()).isEqualTo("PKCS12");
        assertThat(tls.hasKeystoreMaterial()).isTrue();
    }

    @Test
    void anUnknownStoreTypeIsRefused() {
        assertThatThrownBy(() -> TlsOptions.builder().trustStore(SOME_PATH, "x", "PFX"))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("PFX")
                .hasMessageContaining("JKS");
    }

    @Test
    void disablingHostnameVerificationNeedsAnExplicitCallAndSaysSoInToString() {
        TlsOptions tls =
                TlsOptions.builder().disableHostnameVerificationInsecure(true).build();
        assertThat(tls.verifyHostname()).isFalse();
        assertThat(tls.toString()).contains("hostname verification disabled");
        assertThat(tls.isDefault())
                .as("a client that gave up hostname verification is not the default, safe shape")
                .isFalse();
    }

    @Test
    void overrideHostnameStillVerifiesJustAgainstADifferentName() {
        TlsOptions tls = TlsOptions.builder().overrideHostname("localhost").build();
        assertThat(tls.overrideHostname()).contains("localhost");
        assertThat(tls.verifyHostname())
                .as("overriding the expected name is not the same as giving up verification")
                .isTrue();
    }

    @Test
    void aBlankOverrideHostnameIsRefused() {
        assertThatThrownBy(() -> TlsOptions.builder().overrideHostname(" ")).isInstanceOf(PravahaClientException.class);
    }

    @Test
    void combiningDisabledHostnameVerificationWithCertificateMaterialIsRefused() {
        // Arrow's own transport refuses this combination -- "FlightClient has been configured to
        // disable server verification, but certificate options have been specified" -- found only
        // by actually connecting a client to a server (JavaSdkTlsTest), which is exactly why that
        // test exists and a builder-only test would not have caught it.
        assertThatThrownBy(() -> TlsOptions.builder()
                        .caCertificate(SOME_PATH)
                        .disableHostnameVerificationInsecure(true)
                        .build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("do not compose");
    }

    @Test
    void toStringNeverRendersAPassword() {
        TlsOptions tls = TlsOptions.builder()
                .trustStore(SOME_PATH, "super-secret-password", "JKS")
                .build();
        assertThat(tls.toString()).doesNotContain("super-secret-password");
    }

    @Test
    void applyConfigReadsPemMaterialFromAPlainMap() {
        Map<String, String> config = Map.of(
                "tls.ca-certificate", SOME_PATH.toString(),
                "tls.client-certificate", SOME_PATH.toString(),
                "tls.client-key", SOME_PATH.toString());
        TlsOptions tls = TlsOptions.fromConfig(config);
        assertThat(tls.hasPemMaterial()).isTrue();
        assertThat(tls.caCertificate()).contains(SOME_PATH);
    }

    @Test
    void applyConfigReadsKeystoreMaterialIncludingTypeAndPassword() {
        Map<String, String> config = Map.of(
                "tls.trust-store", SOME_PATH.toString(),
                "tls.trust-store-password", "secret",
                "tls.trust-store-type", "pkcs12");
        TlsOptions tls = TlsOptions.fromConfig(config);
        assertThat(tls.hasKeystoreMaterial()).isTrue();
        assertThat(tls.trustStore()).contains(SOME_PATH);
        assertThat(tls.trustStorePassword()).contains("secret");
        assertThat(tls.trustStoreType()).isEqualTo("PKCS12");
    }

    @Test
    void applyConfigGivenBothPemAndKeystoreIsRefusedTheSameAsTheBuilder() {
        Map<String, String> config = Map.of(
                "tls.ca-certificate", SOME_PATH.toString(),
                "tls.trust-store", SOME_PATH.toString());
        assertThatThrownBy(() -> TlsOptions.fromConfig(config))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void applyConfigReadsDisableHostnameVerificationInsecureAsABooleanString() {
        TlsOptions tls = TlsOptions.fromConfig(Map.of("tls.disable-hostname-verification-insecure", "true"));
        assertThat(tls.verifyHostname()).isFalse();
    }

    @Test
    void applyConfigIgnoresKeysItDoesNotRecognise() {
        TlsOptions tls = TlsOptions.fromConfig(Map.of("token", "irrelevant-to-tls", "hosts", "irrelevant"));
        assertThat(tls.isDefault()).isTrue();
    }

    @Test
    void directBuilderCallsAfterApplyConfigOverrideTheConfiguredValue() {
        TlsOptions tls = TlsOptions.builder()
                .applyConfig(Map.of("tls.override-hostname", "from-config"))
                .overrideHostname("from-code")
                .build();
        assertThat(tls.overrideHostname()).contains("from-code");
    }
}

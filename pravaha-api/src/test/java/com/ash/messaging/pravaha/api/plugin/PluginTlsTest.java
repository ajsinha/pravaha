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
package com.ash.messaging.pravaha.api.plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a connector's TLS configuration accepts, and — mostly — what it refuses.
 *
 * <p><strong>Every {@code keytool} below discards its output deliberately.</strong> Redirecting a
 * child's stream into a pipe nobody reads deadlocks the moment it writes more than the buffer
 * holds, and {@code -storetype JKS} does precisely that: it prints a paragraph recommending
 * migration to PKCS12. This suite hung for thirteen minutes on exactly that before the redirect was
 * corrected, which is a cost worth one sentence here.
 *
 * <p>The refusals are the point. Until this existed no connector spoke TLS at all, so every
 * Aerospike, Cassandra and JDBC connection carried its password in the clear; the risk in fixing
 * that is not failing to encrypt, it is <em>appearing</em> to encrypt. A half-configured pair that
 * is quietly ignored leaves an operator believing a connection presents a client identity when it
 * presents none — which is the shape of CFG-6, where this repository's own Flight server read a
 * private key into a field and served plaintext anyway.
 */
final class PluginTlsTest {

    /** The calling plugin's code, since a refusal names the plugin that was misconfigured. */
    private static final ErrorCode BAD_CONFIG = new ErrorCode(9999, "TEST_BAD_CONFIGURATION");

    private static PluginContext context(Map<String, String> options) {
        return new PluginContext() {
            @Override
            public Map<String, String> config() {
                return options;
            }

            @Override
            public String instanceName() {
                return "txn";
            }
        };
    }

    @Test
    void nothingConfiguredMeansNoContextAndNoComplaint() {
        // A connector that has never heard of TLS must keep working exactly as it did. Returning
        // empty rather than throwing is what lets every plugin call this unconditionally.
        assertThat(PluginTls.isConfigured(context(Map.of()))).isFalse();
        assertThat(PluginTls.from(context(Map.of()), BAD_CONFIG)).isEmpty();
    }

    @Test
    void anyOneOptionTurnsItOn() {
        // Deliberately not requiring tls.enabled beside the others: an operator who sets tls.ca and
        // nothing else has plainly asked for TLS, and a connection that stayed in plaintext because
        // a second flag was missing is the silent-downgrade failure this class exists to avoid.
        assertThat(PluginTls.isConfigured(context(Map.of("tls.ca", "/x")))).isTrue();
        assertThat(PluginTls.isConfigured(context(Map.of("tls.enabled", "true"))))
                .isTrue();
        assertThat(PluginTls.isConfigured(context(Map.of("tls.keystore", "/x"))))
                .isTrue();
    }

    @Test
    void hostnameVerificationIsOnUnlessTurnedOff() {
        assertThat(PluginTls.verifyHostname(context(Map.of()))).isTrue();
        assertThat(PluginTls.verifyHostname(context(Map.of("tls.verify-hostname", "false"))))
                .isFalse();
    }

    @Test
    void aCertificateWithoutItsKeyIsRefusedByName() {
        // CFG-6's shape. The message must name the setting that is MISSING, because the operator
        // has already set the other one and is looking for what they still owe.
        assertThatThrownBy(() -> PluginTls.from(context(Map.of("tls.certificate", "/c.pem")), BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("tls.key")
                .hasMessageContaining("txn");
    }

    @Test
    void aKeyWithoutItsCertificateIsRefusedByName() {
        assertThatThrownBy(() -> PluginTls.from(context(Map.of("tls.key", "/k.pem")), BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("tls.certificate");
    }

    @Test
    void bothFormsOfTheSameThingIsRefusedRatherThanResolved() {
        // Picking one would decide invisibly which material the connection presents. An operator
        // who has configured both is confused about something, and guessing hides it.
        assertThatThrownBy(() ->
                        PluginTls.from(context(Map.of("tls.ca", "/ca.pem", "tls.truststore", "/t.p12")), BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("tls.ca")
                .hasMessageContaining("tls.truststore");
    }

    @Test
    void anUnreadablePathIsRefusedBeforeAnythingIsOpened(@TempDir Path dir) {
        assertThatThrownBy(() -> PluginTls.from(
                        context(Map.of("tls.ca", dir.resolve("absent.pem").toString())), BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot read")
                .hasMessageContaining("absent.pem");
    }

    @Test
    void aPkcs1KeyIsRefusedWithTheConversionCommand(@TempDir Path dir) throws Exception {
        // Java has no PKCS#1 decoder, and "invalid key" would send somebody hunting a corrupt file.
        // The one-line openssl conversion is the whole remedy, so the refusal carries it.
        // A REAL certificate beside the PKCS#1 key. With a fake one the certificate failed first and
        // the PKCS#1 message was never reached -- which is how this test found that the guidance was
        // unreachable in the ordering rather than merely untested.
        Path certificate = realCertificate(dir);
        Path key = dir.resolve("k.pem");
        Files.writeString(key, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        assertThatThrownBy(() -> PluginTls.from(
                        context(Map.of(
                                "tls.certificate", certificate.toString(),
                                "tls.key", key.toString())),
                        BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PKCS#1")
                .hasMessageContaining("openssl pkcs8 -topk8");
    }

    @Test
    void aFileWithNoCertificateInItSaysSoRatherThanFailingObscurely(@TempDir Path dir) throws Exception {
        Path ca = dir.resolve("ca.pem");
        Files.writeString(ca, "this is not a certificate\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PluginTls.from(context(Map.of("tls.ca", ca.toString())), BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("no certificate");
    }

    @Test
    void aRealKeystoreLoadsAndProducesAContext(@TempDir Path dir) throws Exception {
        // keytool ships with every JDK, so a genuine PKCS12 costs no dependency and no committed
        // key. A test that only checked the builder stored a string would prove nothing about
        // whether the material can actually be turned into an SSLContext.
        Path store = dir.resolve("client.p12");
        int exit = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-keypass",
                        "secret",
                        "-alias",
                        "client",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        "secret",
                        "-dname",
                        "CN=pravaha-test",
                        "-validity",
                        "1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertThat(exit)
                .as("keytool must succeed for this test to mean anything")
                .isZero();

        var ssl = PluginTls.from(
                context(Map.of("tls.keystore", store.toString(), "tls.keystore.password", "secret")), BAD_CONFIG);
        assertThat(ssl).isPresent();
        assertThat(ssl.get().getProtocol()).isEqualTo("TLS");
    }

    @Test
    void aTruststoreAloneIsEnoughToVerifyAServer(@TempDir Path dir) throws Exception {
        // The common case by far: verify the server, present no client identity. It must not be
        // dragged into the mutual-TLS branch and refused for a missing certificate.
        Path store = dir.resolve("trust.p12");
        int exit = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-keypass",
                        "secret",
                        "-alias",
                        "ca",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        "secret",
                        "-dname",
                        "CN=pravaha-ca",
                        "-validity",
                        "1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertThat(exit).isZero();

        assertThat(PluginTls.from(
                        context(Map.of("tls.truststore", store.toString(), "tls.truststore.password", "secret")),
                        BAD_CONFIG))
                .isPresent();
    }

    @Test
    void aPemCaBundleIsEnoughToVerifyAServer(@TempDir Path dir) throws Exception {
        // The form a Kubernetes secret mounts, and the one most deployments actually have. A real
        // certificate, read through the same path a private CA would take.
        assertThat(PluginTls.from(context(Map.of("tls.ca", realCertificate(dir).toString())), BAD_CONFIG))
                .isPresent();
    }

    @Test
    void aPemCaBundleWithSeveralCertificatesLoadsThemAll(@TempDir Path dir) throws Exception {
        // A CA bundle is usually a chain, not one certificate. Concatenation is how PEM expresses
        // that, and reading only the first would silently trust less than the operator configured.
        Path first = realCertificate(dir);
        Path second = realCertificate(dir.resolve("second"));
        Path bundle = dir.resolve("bundle.pem");
        Files.createDirectories(bundle.getParent());
        Files.writeString(bundle, Files.readString(first) + Files.readString(second));

        assertThat(PluginTls.from(context(Map.of("tls.ca", bundle.toString())), BAD_CONFIG))
                .isPresent();
    }

    @Test
    void anEncryptedKeyIsRefusedAndPointedAtTheKeystoreForm(@TempDir Path dir) throws Exception {
        // Java will not decrypt one without a password this class deliberately does not take: a
        // password belongs in a keystore, which carries its own, rather than in a third option
        // beside two file paths.
        Path key = dir.resolve("k.pem");
        Files.writeString(key, "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");

        assertThatThrownBy(() -> PluginTls.from(
                        context(Map.of(
                                "tls.certificate", realCertificate(dir).toString(),
                                "tls.key", key.toString())),
                        BAD_CONFIG))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("encrypted")
                .hasMessageContaining("tls.keystore");
    }

    @Test
    void aJksTruststoreIsRecognisedFromItsName(@TempDir Path dir) throws Exception {
        // The type is guessed from the extension so an operator need not restate it, and JKS is the
        // one a long-lived JVM shop is most likely to still hold.
        Path store = dir.resolve("trust.jks");
        String keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        assertThat(new ProcessBuilder(
                                keytool,
                                "-genkeypair",
                                "-keypass",
                                "secret",
                                "-alias",
                                "ca",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-storetype",
                                "JKS",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "secret",
                                "-dname",
                                "CN=pravaha-jks",
                                "-validity",
                                "1")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor())
                .isZero();

        assertThat(PluginTls.from(
                        context(Map.of("tls.truststore", store.toString(), "tls.truststore.password", "secret")),
                        BAD_CONFIG))
                .isPresent();
    }

    /** A genuine self-signed certificate in PEM, via the keytool every JDK ships. */
    private static Path realCertificate(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path store = dir.resolve("real.p12");
        Path pem = dir.resolve("real.pem");
        String keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        assertThat(new ProcessBuilder(
                                keytool,
                                "-genkeypair",
                                "-keypass",
                                "secret",
                                "-alias",
                                "real",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "secret",
                                "-dname",
                                "CN=pravaha-real",
                                "-validity",
                                "1")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor())
                .isZero();
        assertThat(new ProcessBuilder(
                                keytool,
                                "-exportcert",
                                "-rfc",
                                "-alias",
                                "real",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "secret",
                                "-file",
                                pem.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor())
                .isZero();
        return pem;
    }
}

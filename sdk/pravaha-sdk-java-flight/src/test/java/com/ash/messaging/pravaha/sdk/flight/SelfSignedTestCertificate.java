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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A self-signed certificate and its key, generated fresh per test run rather than committed.
 *
 * <p>A key checked into the repository is a key everyone who has ever cloned it still has, long
 * after it should have been rotated; a real deployment's TLS material never belongs in source
 * control, and neither should a test's stand-in for it. So this shells out to {@code keytool} --
 * present in every JDK this project builds with -- to generate a fresh key pair and a self-signed
 * certificate into a PKCS12 keystore under a per-test temporary directory, then reads that keystore
 * back with the plain {@link java.security.KeyStore} API to produce whichever shape a test needs:
 * the keystore itself, or its certificate and key as standalone PEM files.
 *
 * <p>The certificate's subject alternative names cover both {@code localhost} and {@code 127.0.0.1},
 * because a test server started on {@code localhost} and reached by IP address needs the name it is
 * reached by to appear on the certificate, or hostname verification has something real to refuse.
 */
final class SelfSignedTestCertificate implements AutoCloseable {

    private final Path directory;
    private final Path keystorePath;
    private final String password;

    // Written on first use.
    private @Nullable Path pemCertificatePath;
    private @Nullable Path pemKeyPath;

    private SelfSignedTestCertificate(Path directory, Path keystorePath, String password) {
        this.directory = directory;
        this.keystorePath = keystorePath;
        this.password = password;
    }

    /** Generates a fresh certificate for {@code localhost} and {@code 127.0.0.1}, valid for one day. */
    static SelfSignedTestCertificate generate() {
        return generate("CN=localhost, OU=pravaha-sdk-test, O=pravaha", "dns:localhost,ip:127.0.0.1");
    }

    /**
     * Generates a fresh certificate for an arbitrary name, for the tests that need a certificate
     * whose name does <em>not</em> match how the client connects -- proving hostname verification
     * genuinely checks something rather than always passing.
     */
    static SelfSignedTestCertificate generate(String distinguishedName, String subjectAlternativeNames) {
        try {
            Path directory = Files.createTempDirectory("pravaha-sdk-tls-test");
            Path keystorePath = directory.resolve("test-keystore.p12");
            String password = "test-password";
            runKeytool(
                    "-genkeypair",
                    "-alias",
                    "pravaha-test",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "1",
                    "-keystore",
                    keystorePath.toString(),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    password,
                    "-keypass",
                    password,
                    "-dname",
                    distinguishedName,
                    "-ext",
                    "SAN=" + subjectAlternativeNames);
            return new SelfSignedTestCertificate(directory, keystorePath, password);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("cannot generate a self-signed test certificate", e);
        }
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("keytool");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("keytool " + String.join(" ", args) + " failed (" + exit + "): " + output);
        }
    }

    /** The generated keystore -- also usable as a truststore, since it holds the one self-signed certificate. */
    Path keystorePath() {
        return keystorePath;
    }

    String password() {
        return password;
    }

    /** {@code "PKCS12"}, the type this keystore was generated as. */
    String storeType() {
        return "PKCS12";
    }

    /** The certificate alone, as a standalone PEM file -- what a server or a CA trust list needs. */
    synchronized Path pemCertificate() {
        if (pemCertificatePath == null) {
            try (InputStream pem = KeystoreMaterial.trustedCertificatesPem(keystorePath, password, storeType())) {
                pemCertificatePath = directory.resolve("test-cert.pem");
                Files.write(pemCertificatePath, pem.readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException("cannot extract the test certificate as PEM", e);
            }
        }
        return pemCertificatePath;
    }

    /** The private key alone, as a standalone PEM file -- what {@code PravahaFlightServer.encryptedWith} needs. */
    synchronized Path pemPrivateKey() {
        if (pemKeyPath == null) {
            InputStream[] certAndKey = KeystoreMaterial.clientCertificateAndKeyPem(keystorePath, password, storeType());
            try {
                certAndKey[0].close();
                pemKeyPath = directory.resolve("test-key.pem");
                Files.write(pemKeyPath, certAndKey[1].readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException("cannot extract the test private key as PEM", e);
            } finally {
                closeQuietly(certAndKey[1]);
            }
        }
        return pemKeyPath;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // A leaked in-memory ByteArrayInputStream costs nothing; not worth failing a test over.
        }
    }

    @Override
    public void close() {
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort: a leftover temp file is the operating system's problem, not the
                    // test's, and failing the test over cleanup would blame the wrong thing.
                }
            });
        } catch (IOException e) {
            // Same reasoning as above.
        }
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A self-signed certificate and its key, generated fresh per test run rather than committed.
 *
 * <p>Duplicated from {@code sdk-java-flight}'s test-only fixture of the same name and purpose
 * (that module cannot be a test dependency here -- this module is upstream of it) rather than
 * moved, since both modules need it and neither may depend on the other's test sources. Shells
 * out to {@code keytool} -- present in every JDK this project builds with -- to generate a fresh
 * key pair and a self-signed certificate into a PKCS12 keystore under a per-test temporary
 * directory, then reads that keystore back with the plain {@link java.security.KeyStore} API to
 * produce the two standalone PEM files {@code PravahaFlightServer.encryptedWith} needs: the
 * certificate chain and its unencrypted PKCS#8 private key.
 *
 * <p>A key checked into the repository is a key everyone who has ever cloned it still has, long
 * after it should have been rotated; a real deployment's TLS material never belongs in source
 * control, and neither should a test's stand-in for it.
 *
 * <p>The certificate's subject alternative names cover both {@code localhost} and {@code
 * 127.0.0.1}, because a test server started on one and reached by the other needs the name it is
 * reached by to appear on the certificate, or hostname verification has something real to refuse.
 */
final class SelfSignedTestCertificate implements AutoCloseable {

    private final Path directory;
    private final Path keystorePath;
    private final String password;
    private final String alias = "pravaha-test";

    private Path pemCertificatePath;
    private Path pemKeyPath;

    private SelfSignedTestCertificate(Path directory, Path keystorePath, String password) {
        this.directory = directory;
        this.keystorePath = keystorePath;
        this.password = password;
    }

    /** Generates a fresh certificate for {@code localhost} and {@code 127.0.0.1}, valid for one day. */
    static SelfSignedTestCertificate generate() {
        try {
            Path directory = Files.createTempDirectory("pravaha-flight-tls-test");
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
                    "CN=localhost, OU=pravaha-flight-test, O=pravaha",
                    "-ext",
                    "SAN=dns:localhost,ip:127.0.0.1");
            return new SelfSignedTestCertificate(directory, keystorePath, password);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("cannot generate a self-signed test certificate", e);
        }
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("keytool");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("keytool " + String.join(" ", args) + " failed (" + exit + "): " + output);
        }
    }

    /** The certificate alone, as a standalone PEM file -- the chain half of {@code encryptedWith}. */
    synchronized Path pemCertificate() {
        if (pemCertificatePath == null) {
            pemCertificatePath = directory.resolve("test-cert.pem");
            writePem(pemCertificatePath, certificatePem());
        }
        return pemCertificatePath;
    }

    /** The private key alone, as a standalone PEM file -- the key half of {@code encryptedWith}. */
    synchronized Path pemPrivateKey() {
        if (pemKeyPath == null) {
            pemKeyPath = directory.resolve("test-key.pem");
            writePem(pemKeyPath, privateKeyPem());
        }
        return pemKeyPath;
    }

    private static void writePem(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + path, e);
        }
    }

    private String certificatePem() {
        try {
            KeyStore store = load();
            Certificate certificate = store.getCertificate(alias);
            if (certificate == null) {
                throw new IllegalStateException("keystore has no certificate under alias '" + alias + "'");
            }
            return pem("CERTIFICATE", certificate.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot read the generated certificate", e);
        }
    }

    private String privateKeyPem() {
        try {
            KeyStore store = load();
            Key key = store.getKey(alias, password.toCharArray());
            if (!(key instanceof PrivateKey privateKey)) {
                throw new IllegalStateException("keystore has no private key under alias '" + alias + "'");
            }
            // A private key's own getEncoded() is PKCS#8 DER for every standard JDK provider, which
            // is exactly the unencrypted PKCS#8 PEM Netty's SslContextBuilder (what
            // FlightServer.Builder.useTls builds on) requires for a server's private key.
            return pem("PRIVATE KEY", privateKey.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot read the generated private key", e);
        }
    }

    private KeyStore load() throws GeneralSecurityException {
        try (var in = Files.newInputStream(keystorePath)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password.toCharArray());
            return store;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + keystorePath, e);
        }
    }

    private static String pem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .encodeToString(der);
        List<String> lines = new ArrayList<>();
        lines.add("-----BEGIN " + label + "-----");
        lines.add(base64);
        lines.add("-----END " + label + "-----");
        return String.join("\n", lines) + "\n";
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

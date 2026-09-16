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
package com.ash.messaging.pravaha.pgwire;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * A fresh, throwaway self-signed certificate and PKCS#8 key, for tests only.
 *
 * <p>Nothing here is committed to the repository -- {@code keytool} (part of every JDK this project
 * builds with, so no new dependency) generates a PKCS#12 keystore in a temporary directory that JUnit
 * deletes afterward, and this class reads the certificate and private key back out of it and writes
 * them as the PEM pair {@link PgTls#load} expects. A key generated fresh per test run and thrown away
 * is the point: the alternative, a key checked into version control, is a secret every clone of this
 * repository would hold identically forever.
 *
 * <p>Deliberately not a general-purpose CA: one self-signed certificate for {@code localhost} and
 * {@code 127.0.0.1}, which is what a test connecting to its own loopback server needs and nothing
 * more.
 */
final class SelfSignedTestCertificate {

    final File certificatePem;
    final File privateKeyPem;

    private SelfSignedTestCertificate(File certificatePem, File privateKeyPem) {
        this.certificatePem = certificatePem;
        this.privateKeyPem = privateKeyPem;
    }

    /**
     * Generates a certificate valid for {@code localhost}/{@code 127.0.0.1} under {@code directory},
     * which the caller owns and is responsible for cleaning up (a JUnit {@code @TempDir} does this
     * automatically).
     */
    static SelfSignedTestCertificate generate(File directory) throws Exception {
        File keystore = new File(directory, "keystore.p12");
        String password = "changeit";
        Process keytool = new ProcessBuilder(
                        System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool",
                        "-genkeypair",
                        "-alias",
                        "pgwire",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "3650",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keystore.getAbsolutePath(),
                        "-storepass",
                        password,
                        "-keypass",
                        password,
                        "-dname",
                        "CN=localhost, O=Pravaha test",
                        "-ext",
                        "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!keytool.waitFor(30, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
            throw new IllegalStateException("keytool failed to generate a test certificate: " + output);
        }

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = new java.io.FileInputStream(keystore)) {
            store.load(in, password.toCharArray());
        }
        Certificate certificate = store.getCertificate("pgwire");
        PrivateKey key = (PrivateKey) store.getKey("pgwire", password.toCharArray());

        File certificatePem = new File(directory, "cert.pem");
        File privateKeyPem = new File(directory, "key.pem");
        writePem(certificatePem, "CERTIFICATE", certificate.getEncoded());
        writePem(privateKeyPem, "PRIVATE KEY", key.getEncoded());
        return new SelfSignedTestCertificate(certificatePem, privateKeyPem);
    }

    private static void writePem(File file, String label, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        String pem = "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
        Files.writeString(file.toPath(), pem, StandardCharsets.US_ASCII);
    }
}

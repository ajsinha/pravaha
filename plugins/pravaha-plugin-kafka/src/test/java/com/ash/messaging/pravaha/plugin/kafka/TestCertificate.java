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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;

/**
 * A self-signed certificate made with the JDK's keytool, as a PKCS12 keystore and as PEM files: the
 * two forms the shared {@code tls.*} options take.
 */
record TestCertificate(Path keystore, String password, Path certificatePem, Path keyPem) {

    static TestCertificate in(Path directory) throws Exception {
        Path keystore = directory.resolve("client.p12");
        String password = "test-password";
        Process keytool = new ProcessBuilder(List.of(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "client",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "1",
                        "-keystore",
                        keystore.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        password,
                        "-keypass",
                        password,
                        "-dname",
                        "CN=localhost, O=pravaha"))
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            store.load(in, password.toCharArray());
        }
        Path certificatePem = directory.resolve("client.crt");
        Files.writeString(
                certificatePem,
                pem("CERTIFICATE", store.getCertificate("client").getEncoded()));
        Path keyPem = directory.resolve("client.key");
        PrivateKey key = (PrivateKey) store.getKey("client", password.toCharArray());
        Files.writeString(keyPem, pem("PRIVATE KEY", key.getEncoded()));
        return new TestCertificate(keystore, password, certificatePem, keyPem);
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}

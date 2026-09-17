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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * Bridges a JKS or PKCS12 keystore to the PEM bytes Arrow Flight's client builder actually accepts.
 *
 * <p>{@code FlightClient.Builder} has no keystore API -- only {@code trustedCertificates(InputStream)}
 * and {@code clientCertificate(InputStream, InputStream)}, both PEM. A JVM shop's keystore is a real,
 * common shape ({@code TlsOptions} accepts it directly for exactly that reason), so this class reads
 * one with the standard {@link KeyStore} API and re-encodes what it finds as the PEM Arrow's builder
 * expects, entirely with the JDK's own APIs -- no third-party crypto library, and nothing shells out
 * to {@code keytool} or {@code openssl} on the request path.
 */
final class KeystoreMaterial {

    private KeystoreMaterial() {}

    /**
     * Every trusted certificate in the store, concatenated as one PEM stream.
     *
     * <p>Reads both shapes a "truststore" comes in: bare certificate entries, the way a dedicated
     * CA bundle (a JKS {@code cacerts} file, for instance) holds them, and the certificate chain
     * attached to a key entry -- what {@code keytool -genkeypair} produces, and what a self-signed
     * certificate looks like when the same keystore is being trusted as its own CA. Both are real;
     * neither is treated as the only correct shape.
     */
    static InputStream trustedCertificatesPem(Path path, String password, String type) {
        KeyStore store = load(path, password, type);
        StringBuilder pem = new StringBuilder();
        try {
            Enumeration<String> aliases = store.aliases();
            int certificates = 0;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (store.isCertificateEntry(alias)) {
                    pem.append(pemOf(store.getCertificate(alias)));
                    certificates++;
                } else if (store.isKeyEntry(alias)) {
                    Certificate[] chain = store.getCertificateChain(alias);
                    if (chain != null) {
                        for (Certificate certificate : chain) {
                            pem.append(pemOf(certificate));
                            certificates++;
                        }
                    }
                }
            }
            if (certificates == 0) {
                throw new PravahaClientException(
                        ClientErrors.TLS_UNREADABLE,
                        "truststore " + path + " has no certificate in it at all -- neither a bare "
                                + "certificate entry nor a key entry's own chain",
                        false);
            }
        } catch (GeneralSecurityException e) {
            throw unreadable(path, e);
        }
        return new ByteArrayInputStream(pem.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** The client's own certificate chain and private key, as two PEM streams: {@code {cert, key}}. */
    static InputStream[] clientCertificateAndKeyPem(Path path, String password, String type) {
        KeyStore store = load(path, password, type);
        try {
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!store.isKeyEntry(alias)) {
                    continue;
                }
                Key key = store.getKey(alias, password == null ? new char[0] : password.toCharArray());
                if (!(key instanceof PrivateKey privateKey)) {
                    continue; // a secret-key entry, not a private key; not what mutual TLS needs
                }
                Certificate[] chain = store.getCertificateChain(alias);
                if (chain == null || chain.length == 0) {
                    throw new PravahaClientException(
                            ClientErrors.TLS_UNREADABLE,
                            "keystore " + path + " has a private key under alias '" + alias
                                    + "' with no certificate chain; a client identity needs both",
                            false);
                }
                StringBuilder certPem = new StringBuilder();
                for (Certificate certificate : chain) {
                    certPem.append(pemOf(certificate));
                }
                String keyPem = pemOf(privateKey);
                return new InputStream[] {
                    new ByteArrayInputStream(certPem.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                    new ByteArrayInputStream(keyPem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                };
            }
        } catch (GeneralSecurityException e) {
            throw unreadable(path, e);
        }
        throw new PravahaClientException(
                ClientErrors.TLS_UNREADABLE,
                "keystore " + path + " has no private-key entry; a client identity for mutual TLS needs one",
                false);
    }

    private static KeyStore load(Path path, String password, String type) {
        if (!Files.isReadable(path)) {
            throw new PravahaClientException(
                    ClientErrors.TLS_UNREADABLE, "the store " + path + " is not a readable file", false);
        }
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password == null ? null : password.toCharArray());
            return store;
        } catch (IOException | GeneralSecurityException e) {
            throw unreadable(path, e);
        }
    }

    private static PravahaClientException unreadable(Path path, Exception cause) {
        return new PravahaClientException(
                ClientErrors.TLS_UNREADABLE,
                "cannot read " + path + ": " + cause.getMessage()
                        + ". Check the password and the store type (JKS or PKCS12)",
                false,
                cause);
    }

    private static String pemOf(Certificate certificate) throws GeneralSecurityException {
        try {
            return pem("CERTIFICATE", certificate.getEncoded());
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /**
     * A private key's own {@code getEncoded()} is PKCS#8 DER for every standard JDK provider -- RSA,
     * EC, and everything {@code keytool -genkeypair} can produce -- so no format conversion happens
     * here beyond the PEM wrapping every other key in this class also gets.
     */
    private static String pemOf(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
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
}

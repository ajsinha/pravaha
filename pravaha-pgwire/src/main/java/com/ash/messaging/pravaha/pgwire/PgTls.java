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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * TLS for this gateway, loaded from a PEM certificate chain and a PEM private key.
 *
 * <h2>Why this exists as its own class, in plain JDK</h2>
 *
 * <p>PostgreSQL's TLS is not "the listening socket is TLS": it is STARTTLS-shaped -- a client opens
 * a plaintext connection, sends an {@code SSLRequest}, and only upgrades the same socket if the
 * server agrees. Arrow Flight's TLS ({@code PravahaFlightServer.encryptedWith}) hands a certificate
 * and key straight to grpc-netty, which owns the whole connection from {@code accept()}; there is no
 * equivalent library on this module's path (and the module deliberately carries none -- see its
 * {@code pom.xml}), so this class does the two things a JDK alone needs to serve TLS from a PEM pair:
 * parse the PEM into a {@link SSLContext}, and layer an {@link SSLSocket} over an already-accepted
 * plaintext {@link Socket} without losing any bytes at the seam.
 *
 * <h2>What this reads, and what it refuses</h2>
 *
 * <p>The certificate file may hold one certificate or a chain, PEM-encoded, one or more {@code
 * -----BEGIN CERTIFICATE-----} blocks concatenated -- {@link CertificateFactory#generateCertificates}
 * handles that directly. The key file must be a single, <strong>unencrypted PKCS#8</strong> PEM key
 * ({@code -----BEGIN PRIVATE KEY-----}), RSA or EC. A PKCS#1 key ({@code -----BEGIN RSA PRIVATE
 * KEY-----}, OpenSSL's traditional format) is refused by name rather than silently rejected as
 * unparseable bytes: {@code java.security} has no PKCS#1 decoder built in, converting one would mean
 * writing an ASN.1 wrapper this module would then be the only tester of, and {@code openssl pkcs8
 * -topk8 -nocrypt} does the conversion in one command outside this process. An encrypted key is
 * refused for the same reason a plaintext credential in an unreadable file would be: this server has
 * nowhere to ask for the passphrase, and a key it cannot use is the same failure as a key that is not
 * there.
 *
 * <h2>Half a pair is refused, not silently ignored</h2>
 *
 * <p>CFG-6(b), on the Flight side: a private key configured without a certificate (or the reverse)
 * used to be read into a {@code File}, held in a field, and never used, because the guard was {@code
 * if (tlsCertificate != null)} and said nothing about the key -- so a node with half a TLS
 * configuration served plaintext while its own settings suggested otherwise. {@link
 * PravahaPgWireServer#encryptedWith} checks both halves are present before either is opened, for the
 * same reason.
 */
final class PgTls {

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----\\s*(.*?)-----END \\1-----", Pattern.DOTALL);

    /** Algorithms tried, in order, against a PKCS#8 key whose own algorithm OID Java does not surface. */
    private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC");

    private final SSLContext context;

    private PgTls(SSLContext context) {
        this.context = context;
    }

    /**
     * Loads a certificate chain and its private key, or refuses with {@link
     * PgWireErrors#TLS_UNREADABLE} naming exactly what is wrong.
     *
     * @throws PravahaException if either file is missing, unreadable, or not in a shape this server
     *     understands
     */
    static PgTls load(File certificateChain, File privateKey) {
        // CFG-6(b): null-checked before either readability branch, because both of those
        // dereference. See this class's own javadoc.
        if (certificateChain == null || privateKey == null) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    "TLS needs both halves and got "
                            + (certificateChain == null ? "only a private key" : "only a certificate chain")
                            + ". Set pravaha.pgwire.tls.certificate and pravaha.pgwire.tls.key together, or "
                            + "neither -- a node given one of them cannot serve TLS, and starting in plaintext "
                            + "because half a setting was missing is how a deployment that asked for encryption "
                            + "ends up without it.");
        }
        if (!certificateChain.isFile()) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    "the TLS certificate " + certificateChain.getAbsolutePath() + " is not a readable file");
        }
        if (!privateKey.isFile()) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    "the TLS private key " + privateKey.getAbsolutePath() + " is not a readable file");
        }
        try {
            Collection<? extends Certificate> chain = readCertificateChain(certificateChain);
            PrivateKey key = readPrivateKey(privateKey);

            // An in-memory keystore, never written to disk: this process is the only reader the
            // private key ever needs, and a temp file would be one more place it could be found.
            char[] noPassword = new char[0];
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("pgwire", key, noPassword, chain.toArray(new Certificate[0]));

            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keyStore, noPassword);

            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(managers.getKeyManagers(), null, null);
            return new PgTls(context);
        } catch (GeneralSecurityException | IOException failed) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    "could not load the TLS certificate/key pair (" + certificateChain.getName() + ", "
                            + privateKey.getName() + "): " + failed.getMessage(),
                    failed);
        }
    }

    /**
     * Layers TLS over an already-accepted plaintext socket, server side.
     *
     * <p>{@code consumed} is {@code null} deliberately, not an oversight: every read this server has
     * done before calling this -- the {@code SSLRequest} packet and nothing else, per {@link
     * PgWireConnection}'s prelude loop -- was done through an <em>unbuffered</em> stream that reads
     * exactly as many bytes as asked and never more, so nothing belonging to the TLS handshake has
     * been consumed off the wire yet for this method to hand back. A buffered prelude would need to
     * replay its own read-ahead here instead.
     *
     * <p>The handshake itself is not forced here: it runs lazily on the first read or write the
     * caller does against the returned socket's streams, and a failure there -- a client that opened
     * an {@code SSLRequest} and then spoke something that is not TLS -- surfaces as an ordinary {@code
     * IOException}, which {@code PgWireConnection.run} already treats as an unremarkable disconnect
     * rather than something to log.
     */
    Socket serverSocket(Socket plaintext) throws IOException {
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket tls = (SSLSocket) factory.createSocket(plaintext, null, true);
        tls.setUseClientMode(false);
        return tls;
    }

    private static Collection<? extends Certificate> readCertificateChain(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            Collection<? extends Certificate> chain =
                    CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (chain.isEmpty()) {
                throw new PravahaException(
                        PgWireErrors.TLS_UNREADABLE,
                        file.getAbsolutePath() + " has no certificate in it -- expected one or more PEM blocks "
                                + "beginning '-----BEGIN CERTIFICATE-----'");
            }
            return chain;
        } catch (GeneralSecurityException malformed) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    file.getAbsolutePath() + " could not be read as an X.509 certificate chain: "
                            + malformed.getMessage(),
                    malformed);
        }
    }

    private static PrivateKey readPrivateKey(File file) throws IOException {
        String text = Files.readString(file.toPath(), StandardCharsets.US_ASCII);
        Matcher block = PEM_BLOCK.matcher(text);
        if (!block.find()) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    file.getAbsolutePath() + " has no PEM block in it -- expected " + "'-----BEGIN PRIVATE KEY-----'");
        }
        String label = block.group(1);
        if (!"PRIVATE KEY".equals(label)) {
            throw new PravahaException(
                    PgWireErrors.TLS_UNREADABLE,
                    file.getAbsolutePath() + " is '-----BEGIN " + label + "-----', not PKCS#8. This server "
                            + "reads unencrypted PKCS#8 keys only ('-----BEGIN PRIVATE KEY-----'); convert with "
                            + "`openssl pkcs8 -topk8 -nocrypt -in " + file.getName() + " -out key8.pem` if this "
                            + "is a PKCS#1 ('RSA PRIVATE KEY') or an encrypted key.");
        }
        byte[] der = Base64.getMimeDecoder().decode(block.group(2).replaceAll("\\s+", ""));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        InvalidKeySpecException last = null;
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException wrongAlgorithm) {
                last = wrongAlgorithm;
            } catch (GeneralSecurityException unavailable) {
                // This JVM does not carry a provider for this algorithm at all; try the rest.
                last = new InvalidKeySpecException(unavailable);
            }
        }
        throw new PravahaException(
                PgWireErrors.TLS_UNREADABLE,
                file.getAbsolutePath() + " is a PKCS#8 key this server could not load as any of " + KEY_ALGORITHMS
                        + ": " + last.getMessage(),
                last);
    }
}

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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Proves, before the node says it serves TLS, that its certificate and its key are a pair.
 *
 * <p><strong>Finding SX-17.</strong> A certificate and a key that are each individually valid but
 * do not belong together <em>started the node successfully</em> and printed
 * {@code flight transport=TLS} in the startup summary. The mismatch surfaced only at the first
 * client handshake, as {@code SSL alert number 80 / tlsv1 alert internal error} — on the client,
 * with nothing in the server's log. An operator watching startup saw a healthy TLS node while every
 * client that tried to use it failed. Swapping the two files was worse again: a raw
 * {@code IllegalArgumentException} out of the gRPC builder, which {@code start()}'s
 * {@code IOException}-only catch did not see, so it reached the operator with no {@code PRV-} code
 * at all.
 *
 * <p>The check is a signature round trip rather than a comparison of key material: sign a fixed
 * nonce with the private key and verify it with the certificate's public key. That works for RSA
 * and for EC without this class knowing anything about either representation, and it is the same
 * question TLS itself will ask a moment later — which is the point. A few milliseconds once, at
 * startup, in exchange for the difference between a refusal an operator reads and an outage every
 * client discovers.
 *
 * <p>Deliberately <em>not</em> a second TLS implementation. Arrow still builds the transport from
 * the same two files; this only refuses a pair it would have accepted and then failed on.
 */
final class FlightTlsPair {

    private FlightTlsPair() {}

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    /** The algorithms a PKCS#8 block here may hold, in the order they are tried. */
    private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC");

    /** What is signed and verified. Any fixed bytes would do; these say what they are for. */
    private static final byte[] NONCE = "pravaha tls pair check".getBytes(StandardCharsets.US_ASCII);

    /**
     * Refuses a certificate and key that are not a pair, or that cannot be read at all.
     *
     * @throws PravahaException {@code PRV-6104} naming both files and which of the two questions
     *     failed
     */
    static void requireMatching(File certificateChain, File privateKey) {
        Certificate certificate = firstCertificateOf(certificateChain);
        PrivateKey key = keyOf(privateKey);
        PublicKey published = certificate.getPublicKey();

        String algorithm = signatureAlgorithmFor(key.getAlgorithm(), certificateChain, privateKey);
        boolean matches;
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(NONCE);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(published);
            verifier.update(NONCE);
            matches = verifier.verify(signature);
        } catch (GeneralSecurityException cannotAsk) {
            throw refusal(
                    "the TLS certificate " + certificateChain.getAbsolutePath() + " and the private key "
                            + privateKey.getAbsolutePath() + " could not be checked against each other ("
                            + cannotAsk.getMessage() + "). A pair this node cannot verify is one it will "
                            + "not claim to serve",
                    cannotAsk);
        }
        if (!matches) {
            throw refusal(
                    "the TLS certificate " + certificateChain.getAbsolutePath() + " and the private key "
                            + privateKey.getAbsolutePath()
                            + " are each valid and are not a pair: the key does not sign what the "
                            + "certificate's public key verifies. Left to start, this node would report "
                            + "transport=TLS and every client would fail its handshake with 'tlsv1 alert "
                            + "internal error' -- on the client, with nothing in this log. Check that the "
                            + "two files came from the same request, and that the certificate is the one "
                            + "issued for this key rather than a neighbouring host's",
                    null);
        }
    }

    private static String signatureAlgorithmFor(String keyAlgorithm, File certificateChain, File privateKey) {
        return switch (keyAlgorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            default ->
                throw refusal(
                        "the TLS private key " + privateKey.getAbsolutePath() + " is a " + keyAlgorithm
                                + " key, which this node cannot check against the certificate "
                                + certificateChain.getAbsolutePath() + ". Use an RSA or EC key",
                        null);
        };
    }

    private static Certificate firstCertificateOf(File file) {
        try (InputStream in = new FileInputStream(file)) {
            Collection<? extends Certificate> chain =
                    CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (chain.isEmpty()) {
                throw refusal(
                        file.getAbsolutePath() + " has no certificate in it -- expected one or more PEM blocks "
                                + "beginning '-----BEGIN CERTIFICATE-----'",
                        null);
            }
            // The leaf, which is the one whose key has to match: a chain is ordered leaf-first.
            return chain.iterator().next();
        } catch (java.io.IOException | GeneralSecurityException unreadable) {
            throw refusal(
                    file.getAbsolutePath() + " could not be read as an X.509 certificate chain: "
                            + unreadable.getMessage(),
                    unreadable);
        }
    }

    private static PrivateKey keyOf(File file) {
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.US_ASCII);
        } catch (java.io.IOException unreadable) {
            throw refusal(
                    "the TLS private key " + file.getAbsolutePath() + " could not be read: " + unreadable.getMessage(),
                    unreadable);
        }
        Matcher block = PEM_BLOCK.matcher(text);
        if (!block.find()) {
            throw refusal(
                    file.getAbsolutePath() + " has no PEM block in it -- expected '-----BEGIN PRIVATE KEY-----'. "
                            + "A certificate given where the key belongs is the commonest way to reach this",
                    null);
        }
        String label = block.group(1);
        if (!"PRIVATE KEY".equals(label)) {
            throw refusal(
                    file.getAbsolutePath() + " is '-----BEGIN " + label + "-----', not PKCS#8. This node reads "
                            + "unencrypted PKCS#8 keys only ('-----BEGIN PRIVATE KEY-----'); convert with "
                            + "`openssl pkcs8 -topk8 -nocrypt -in " + file.getName() + " -out key8.pem` if this "
                            + "is a PKCS#1 ('RSA PRIVATE KEY') or an encrypted key",
                    null);
        }
        byte[] der = Base64.getMimeDecoder().decode(block.group(2).replaceAll("\\s+", ""));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        GeneralSecurityException last = null;
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException wrongAlgorithm) {
                last = wrongAlgorithm;
            }
        }
        throw refusal(
                file.getAbsolutePath() + " is a PKCS#8 key this node could not load as any of " + KEY_ALGORITHMS + ": "
                        + (last == null ? "no provider" : last.getMessage()),
                last);
    }

    private static PravahaException refusal(String message, Throwable cause) {
        return new PravahaException(FlightErrors.TLS_UNREADABLE, message, cause);
    }
}

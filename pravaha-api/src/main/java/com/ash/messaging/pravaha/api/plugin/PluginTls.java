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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * One TLS configuration surface for every connector, over three unrelated driver APIs.
 *
 * <p>Until this existed, <strong>no connector spoke TLS at all</strong>. Pravaha reached Aerospike,
 * Cassandra and every JDBC database in plaintext, with the password crossing the wire in the clear --
 * the same thing the PostgreSQL gateway refuses to do by default. Each driver exposes encryption
 * differently (Aerospike wants a {@code TlsPolicy} and a {@code tlsName}, Cassandra an {@code
 * SSLContext}, JDBC a set of URL parameters that differ per database), so this builds the one thing
 * they can all be given and lets each plugin hand it over in its own dialect.
 *
 * <h2>The options, and why both forms</h2>
 *
 * <p>A deployment has whichever it has. A Kubernetes secret mounts PEM; a JVM shop has a keystore
 * somebody's security team issued. Supporting one and not the other means telling an operator to
 * convert their material with {@code openssl} before they can encrypt a connection, which is how a
 * security feature goes unused.
 *
 * <table border="1">
 * <caption>Recognised options, each prefixed {@code tls.}</caption>
 * <tr><th>Option</th><th>Means</th></tr>
 * <tr><td>{@code tls.enabled}</td><td>Turns it on. Implied when any other option below is set</td></tr>
 * <tr><td>{@code tls.ca}</td><td>PEM bundle of certificates to trust when verifying the server</td></tr>
 * <tr><td>{@code tls.certificate} + {@code tls.key}</td><td>PEM pair identifying <em>this</em> client, for mutual TLS</td></tr>
 * <tr><td>{@code tls.truststore} + {@code .password} + {@code .type}</td><td>The same trust decision as a JKS or PKCS12 file</td></tr>
 * <tr><td>{@code tls.keystore} + {@code .password} + {@code .type}</td><td>The same client identity as a JKS or PKCS12 file</td></tr>
 * <tr><td>{@code tls.verify-hostname}</td><td>Default {@code true}. See below</td></tr>
 * </table>
 *
 * <h2>What it refuses, which is the part that matters</h2>
 *
 * <p><strong>Half a pair is refused, naming the half that is missing.</strong> This repository has a
 * scar for exactly that: the Flight server once checked only the certificate, so a key configured
 * alone was read into a field and never used -- the node served plaintext while advising the
 * operator to set the keys they had already half-set (CFG-6). A security setting that is silently
 * ignored is worse than one that is absent, because the operator believes it is on.
 *
 * <p><strong>Both forms at once is refused rather than resolved.</strong> A PEM pair and a keystore
 * both supplied is a configuration somebody is confused about; picking one and ignoring the other
 * would decide, invisibly, which identity the connection presents.
 *
 * <p><strong>{@code tls.verify-hostname: false} is allowed and is a downgrade.</strong> It turns off
 * the check that the certificate belongs to the host being dialled, which is what stops an attacker
 * who holds <em>any</em> certificate this truststore trusts from impersonating the server. It exists
 * because a deployment dialling a node by IP has no practical alternative; the plugin that honours
 * it says so at startup, and it is never the default.
 */
public final class PluginTls {

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-+BEGIN\\s+(?<kind>[A-Z ]+)-+(?<body>[^-]+)-+END\\s+\\1-+", Pattern.DOTALL);

    private PluginTls() {}

    /** Whether any TLS option is set, so a plugin can skip building a context it will not use. */
    public static boolean isConfigured(PluginContext context) {
        // An explicit setting decides, in BOTH directions. Inference only fills the silence.
        //
        // This was written the other way round and it was wrong: any option present meant on, so an
        // operator who wrote tls.enabled: false beside a certificate path they had stopped using
        // would have had TLS switched on against their written instruction. Enablement and
        // disablement are configuration's to state, not this method's to guess, and a guess that
        // overrides what somebody wrote down is the worst of the two directions.
        String explicit = context.get("tls.enabled", "");
        if (!explicit.isBlank()) {
            return Boolean.parseBoolean(explicit);
        }
        for (String option : List.of("tls.ca", "tls.certificate", "tls.key", "tls.truststore", "tls.keystore")) {
            if (!context.get(option, "").isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the certificate's name must match the host being dialled. Default {@code true}.
     *
     * <p>Separate from {@link #from} because the drivers apply it in their own way -- Aerospike
     * matches against a configured {@code tlsName}, Cassandra against the endpoint -- so each plugin
     * has to ask and then honour it itself rather than receive it inside an {@link SSLContext}.
     */
    public static boolean verifyHostname(PluginContext context) {
        return Boolean.parseBoolean(context.get("tls.verify-hostname", "true"));
    }

    /**
     * The context these options describe, or empty when none are set.
     *
     * @param badConfiguration the calling plugin's own configuration error code, so a refusal carries
     *     the code of the plugin that was misconfigured rather than a shared one nobody recognises
     */
    public static Optional<SSLContext> from(PluginContext context, ErrorCode badConfiguration) {
        if (!isConfigured(context)) {
            return Optional.empty();
        }
        String ca = context.get("tls.ca", "");
        String certificate = context.get("tls.certificate", "");
        String key = context.get("tls.key", "");
        String truststore = context.get("tls.truststore", "");
        String keystore = context.get("tls.keystore", "");

        refuseHalfAPair(context, badConfiguration, certificate, key);
        refuseTwoForms(context, badConfiguration, certificate, keystore, "tls.certificate", "tls.keystore");
        refuseTwoForms(context, badConfiguration, ca, truststore, "tls.ca", "tls.truststore");

        try {
            TrustManagerFactory trust = null;
            if (!ca.isBlank()) {
                trust = trustFromPem(context, badConfiguration, ca);
            } else if (!truststore.isBlank()) {
                trust = trustFromKeyStore(context, badConfiguration, truststore);
            }

            KeyManagerFactory identity = null;
            if (!certificate.isBlank()) {
                identity = identityFromPem(context, badConfiguration, certificate, key);
            } else if (!keystore.isBlank()) {
                identity = identityFromKeyStore(context, badConfiguration, keystore);
            }

            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(
                    identity == null ? null : identity.getKeyManagers(),
                    trust == null ? null : trust.getTrustManagers(),
                    new SecureRandom());
            return Optional.of(ssl);
        } catch (ConfigurationException refusal) {
            throw refusal;
        } catch (Exception failure) {
            throw new ConfigurationException(
                    badConfiguration,
                    "source '" + context.instanceName() + "' has TLS options that could not be made into a "
                            + "usable context: " + failure,
                    failure);
        }
    }

    private static void refuseHalfAPair(PluginContext context, ErrorCode code, String certificate, String key) {
        if (certificate.isBlank() == key.isBlank()) {
            return;
        }
        String missing = certificate.isBlank() ? "tls.certificate" : "tls.key";
        String present = certificate.isBlank() ? "tls.key" : "tls.certificate";
        throw new ConfigurationException(
                code,
                "source '" + context.instanceName() + "' sets " + present + " but not " + missing
                        + ". A client certificate and its private key are read together and are useless apart, "
                        + "so this is refused rather than ignored -- half a pair that is silently dropped means "
                        + "a connection that presents no identity while its operator believes it presents one.");
    }

    private static void refuseTwoForms(
            PluginContext context, ErrorCode code, String pem, String store, String pemOption, String storeOption) {
        if (pem.isBlank() || store.isBlank()) {
            return;
        }
        throw new ConfigurationException(
                code,
                "source '" + context.instanceName() + "' sets both " + pemOption + " and " + storeOption
                        + ". They are two ways to say the same thing and this refuses to choose between them: "
                        + "picking one would decide invisibly which material the connection actually uses. "
                        + "Remove whichever is not intended.");
    }

    private static TrustManagerFactory trustFromPem(PluginContext context, ErrorCode code, String path)
            throws Exception {
        List<Certificate> certificates = certificatesIn(context, code, path, "tls.ca");
        KeyStore store = emptyKeyStore();
        int index = 0;
        for (Certificate certificate : certificates) {
            store.setCertificateEntry("ca-" + index++, certificate);
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return factory;
    }

    private static TrustManagerFactory trustFromKeyStore(PluginContext context, ErrorCode code, String path)
            throws Exception {
        KeyStore store = loadKeyStore(context, code, path, "tls.truststore");
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return factory;
    }

    private static KeyManagerFactory identityFromPem(
            PluginContext context, ErrorCode code, String certificatePath, String keyPath) throws Exception {
        List<Certificate> chain = certificatesIn(context, code, certificatePath, "tls.certificate");
        PrivateKey privateKey = privateKeyIn(context, code, keyPath);
        KeyStore store = emptyKeyStore();
        store.setKeyEntry("client", privateKey, new char[0], chain.toArray(new Certificate[0]));
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, new char[0]);
        return factory;
    }

    private static KeyManagerFactory identityFromKeyStore(PluginContext context, ErrorCode code, String path)
            throws Exception {
        String password = context.get("tls.keystore.password", "");
        KeyStore store = loadKeyStore(context, code, path, "tls.keystore");
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password.toCharArray());
        return factory;
    }

    private static KeyStore loadKeyStore(PluginContext context, ErrorCode code, String path, String option)
            throws Exception {
        String type = context.get(option + ".type", guessTypeFrom(path));
        String password = context.get(option + ".password", "");
        KeyStore store = KeyStore.getInstance(type);
        try (var in = Files.newInputStream(readable(context, code, path, option))) {
            store.load(in, password.isEmpty() ? null : password.toCharArray());
        }
        return store;
    }

    /** PKCS12 unless the name says JKS, because PKCS12 is the JDK default and the portable one. */
    private static String guessTypeFrom(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jks") ? "JKS" : "PKCS12";
    }

    private static List<Certificate> certificatesIn(PluginContext context, ErrorCode code, String path, String option)
            throws Exception {
        byte[] pem = Files.readAllBytes(readable(context, code, path, option));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> certificates;
        try {
            certificates = new ArrayList<>(factory.generateCertificates(new ByteArrayInputStream(pem)));
        } catch (java.security.cert.CertificateException unreadable) {
            // Caught rather than allowed to propagate. The factory's own words for a file that is
            // not a certificate are "No certificate data found" and "extra data at the end", which
            // send somebody hunting corruption in a file whose real problem is that it is the wrong
            // file. This branch was written as the isEmpty() check below and never ran at all,
            // because the factory throws instead of returning empty -- found by the test that
            // asserted the message, which is the only reason it is not still dead code.
            certificates = List.of();
        }
        if (certificates.isEmpty()) {
            throw new ConfigurationException(
                    code,
                    "source '" + context.instanceName() + "' points " + option + " at " + path
                            + ", which holds no certificate this JDK can read. A PEM certificate is a base64 "
                            + "block between BEGIN CERTIFICATE and END CERTIFICATE lines, and a DER file works "
                            + "too. If the file looks right, check that it is the certificate and not the key: "
                            + "pointing " + option + " at a key is the common way to arrive here.");
        }
        return certificates;
    }

    private static PrivateKey privateKeyIn(PluginContext context, ErrorCode code, String path) throws Exception {
        String pem = Files.readString(readable(context, code, path, "tls.key"), StandardCharsets.UTF_8);
        Matcher block = PEM_BLOCK.matcher(pem);
        if (!block.find()) {
            throw new ConfigurationException(
                    code,
                    "source '" + context.instanceName() + "' points tls.key at " + path
                            + ", which contains no PEM block.");
        }
        String kind = block.group("kind").trim();
        if (kind.contains("RSA PRIVATE KEY") || kind.contains("EC PRIVATE KEY")) {
            throw new ConfigurationException(
                    code,
                    "source '" + context.instanceName() + "' points tls.key at a PKCS#1 key (BEGIN " + kind
                            + "). Java reads PKCS#8 only, and has no decoder for PKCS#1. Convert it once:\n"
                            + "  openssl pkcs8 -topk8 -nocrypt -in " + path + " -out " + path + ".pk8");
        }
        if (kind.contains("ENCRYPTED")) {
            throw new ConfigurationException(
                    code,
                    "source '" + context.instanceName() + "' points tls.key at an encrypted private key. "
                            + "Decrypt it, or use tls.keystore, which carries its own password.");
        }
        byte[] der = Base64.getMimeDecoder().decode(block.group("body").replaceAll("\\s", ""));
        var spec = new java.security.spec.PKCS8EncodedKeySpec(der);
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception wrongAlgorithm) {
                // Tried in turn: a PKCS#8 body does not say which it is without parsing the ASN.1,
                // and two attempts are cheaper than a parser.
            }
        }
        throw new ConfigurationException(
                code,
                "source '" + context.instanceName() + "' has a tls.key that is neither an RSA nor an EC "
                        + "PKCS#8 private key.");
    }

    private static Path readable(PluginContext context, ErrorCode code, String path, String option) {
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new ConfigurationException(
                    code,
                    "source '" + context.instanceName() + "' points " + option + " at " + path
                            + ", which this process cannot read. Checked before anything is opened, so a "
                            + "misconfigured path is refused at startup rather than at the first connection.");
        }
        return file;
    }

    private static KeyStore emptyKeyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        return store;
    }
}

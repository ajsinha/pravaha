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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * A Kafka binding's security options -- the shared {@code tls.*} options, {@code user}, {@code
 * password} and {@code sasl.mechanism} -- as the Kafka client properties that carry them, with
 * {@code security.protocol} following from which are set.
 *
 * <p>One mapping for {@code kafka-sink} and the {@code kafka} source, so a binding's security reads
 * the same whichever direction it points; each refuses in its own words, through {@code refusal}.
 */
final class KafkaSecurity {

    private final Function<String, ConfigurationException> refusal;

    KafkaSecurity(Function<String, ConfigurationException> refusal) {
        this.refusal = refusal;
    }

    /** Every security property the options describe, {@code security.protocol} included. */
    Map<String, Object> properties(PluginContext context) {
        Map<String, Object> security = new LinkedHashMap<>();
        security.putAll(tls(context));
        boolean encrypted = PluginTls.isConfigured(context);
        security.putAll(sasl(context, encrypted));
        String protocol = encrypted ? "SSL" : "PLAINTEXT";
        if (security.containsKey(SaslConfigs.SASL_MECHANISM)) {
            protocol = "SASL_" + protocol;
        }
        security.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        return security;
    }

    /**
     * The shared {@code tls.*} options as Kafka's {@code ssl.*} properties.
     *
     * <p>{@link PluginTls#from} is run first and its context thrown away: it is what refuses an
     * unknown option, half a certificate pair, both forms of one thing, an unreadable file and a
     * PKCS#1 key -- with the same words every other connector uses -- and a context it can build is
     * material Kafka's own loader will read too. Kafka takes files and properties, not an {@code
     * SSLContext}, so the mapping is by option: PEM files become {@code PEM} stores, keystores and
     * truststores are passed by location.
     */
    private Map<String, Object> tls(PluginContext context) {
        PluginTls.from(context, KafkaErrors.BAD_CONFIGURATION);
        Map<String, Object> ssl = new LinkedHashMap<>();
        if (!PluginTls.isConfigured(context)) {
            return ssl;
        }
        String ca = context.get("tls.ca", "").strip();
        String truststore = context.get("tls.truststore", "").strip();
        if (!ca.isEmpty()) {
            ssl.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
            ssl.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ca);
        } else if (!truststore.isEmpty()) {
            ssl.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststore);
            ssl.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, storeType(context, "tls.truststore", truststore));
            String password = context.get("tls.truststore.password", "");
            if (!password.isEmpty()) {
                ssl.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, password);
            }
        }
        String certificate = context.get("tls.certificate", "").strip();
        String keystore = context.get("tls.keystore", "").strip();
        if (!certificate.isEmpty()) {
            ssl.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
            ssl.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, read(certificate));
            ssl.put(
                    SslConfigs.SSL_KEYSTORE_KEY_CONFIG,
                    read(context.get("tls.key", "").strip()));
        } else if (!keystore.isEmpty()) {
            ssl.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keystore);
            ssl.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, storeType(context, "tls.keystore", keystore));
            String password = context.get("tls.keystore.password", "");
            ssl.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, password);
            ssl.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, password);
        }
        // Kafka's spelling of "check the name": HTTPS endpoint identification, on by default in the
        // client and switched off only by the empty string.
        ssl.put(
                SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG,
                PluginTls.verifyHostname(context) ? "https" : "");
        return ssl;
    }

    private static String storeType(PluginContext context, String option, String path) {
        String explicit = context.get(option + ".type", "").strip();
        if (!explicit.isEmpty()) {
            return explicit.toUpperCase(Locale.ROOT);
        }
        return path.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
    }

    private String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw refusal.apply("cannot read " + path + ": " + e.getMessage());
        }
    }

    /**
     * {@code user} and {@code password} as SASL. PLAIN sends the password as it is, so PLAIN without
     * TLS is refused; the SCRAM mechanisms never send it and are allowed either way.
     */
    private Map<String, Object> sasl(PluginContext context, boolean encrypted) {
        String user = context.get("user", "");
        String password = context.get("password", "");
        String mechanism = context.get("sasl.mechanism", "").strip().toUpperCase(Locale.ROOT);
        Map<String, Object> sasl = new LinkedHashMap<>();
        if (user.isEmpty() && password.isEmpty()) {
            if (!mechanism.isEmpty()) {
                throw refusal.apply("sets sasl.mechanism without user and password");
            }
            return sasl;
        }
        if (user.isEmpty() || password.isEmpty()) {
            throw refusal.apply("sets " + (user.isEmpty() ? "password but not user" : "user but not password")
                    + "; SASL needs both, and half a credential would connect as nobody");
        }
        if (mechanism.isEmpty()) {
            mechanism = "PLAIN";
        }
        String module =
                switch (mechanism) {
                    case "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule";
                    case "SCRAM-SHA-256", "SCRAM-SHA-512" -> "org.apache.kafka.common.security.scram.ScramLoginModule";
                    default ->
                        throw refusal.apply(
                                "sasl.mechanism '" + mechanism + "' is not PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512");
                };
        if (mechanism.equals("PLAIN") && !encrypted) {
            throw refusal.apply("uses SASL PLAIN without TLS, which sends the password to the broker in the clear. "
                    + "Turn TLS on (tls.enabled: true, and tls.ca if the broker's CA is not in the JVM's trust "
                    + "store), or use sasl.mechanism: SCRAM-SHA-256 or SCRAM-SHA-512, which never send it.");
        }
        sasl.put(SaslConfigs.SASL_MECHANISM, mechanism);
        sasl.put(
                SaslConfigs.SASL_JAAS_CONFIG,
                module + " required username=\"" + jaasEscaped(user) + "\" password=\"" + jaasEscaped(password)
                        + "\";");
        return sasl;
    }

    private static String jaasEscaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

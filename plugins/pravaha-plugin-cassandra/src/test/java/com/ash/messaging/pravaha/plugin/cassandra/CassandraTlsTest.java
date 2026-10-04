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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assertion that matters here is the boolean, not the null check.
 *
 * <p>Encrypting the connection is the easy half and the driver's {@code withSslContext} does it.
 * Checking that the certificate belongs to the node that answered is the half that is silently
 * skipped by that method, and a test that only asserted "TLS is on" would pass just as happily
 * over a connection any trusted certificate could impersonate a node on.
 */
final class CassandraTlsTest {

    private static PluginContext context(Map<String, String> options) {
        return new PluginContext() {
            @Override
            public Map<String, String> config() {
                return options;
            }

            @Override
            public String instanceName() {
                return "trades";
            }
        };
    }

    private static Map<String, String> tlsOptions(Path store) {
        Map<String, String> options = new HashMap<>();
        options.put("tls.enabled", "true");
        options.put("tls.truststore", store.toString());
        options.put("tls.truststore.password", "changeit");
        return options;
    }

    private static Path truststore(Path dir) throws Exception {
        Path store = dir.resolve("trust.p12");
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, null);
        try (OutputStream out = Files.newOutputStream(store)) {
            empty.store(out, "changeit".toCharArray());
        }
        return store;
    }

    private static boolean verifiesHostnames(ProgrammaticSslEngineFactory factory) throws Exception {
        // The flag is protected on the driver's own class, and reading it is the only way to prove
        // from outside a live cluster that it was passed through rather than defaulted.
        Field field = ProgrammaticSslEngineFactory.class.getDeclaredField("requireHostnameValidation");
        field.setAccessible(true);
        return field.getBoolean(factory);
    }

    @Test
    void tlsOffMeansNoFactory() {
        assertThat(CassandraTls.engineFactory(context(Map.of()))).isNull();
    }

    @Test
    void hostnamesAreVerifiedUnlessSomebodySaysOtherwise(@TempDir Path dir) throws Exception {
        // The default has to be the safe one. An operator who turned TLS on and said nothing about
        // hostnames has asked for a secure connection, not an encrypted one.
        ProgrammaticSslEngineFactory factory = CassandraTls.engineFactory(context(tlsOptions(truststore(dir))));
        assertThat(factory).isNotNull();
        assertThat(verifiesHostnames(Objects.requireNonNull(factory))).isTrue();
    }

    @Test
    void verificationCanBeTurnedOffDeliberatelyAndOnlyDeliberately(@TempDir Path dir) throws Exception {
        // Self-signed certificates in a test environment are a real reason to want this off. It is
        // reachable, it takes saying so in config, and it is never where a default lands you.
        Map<String, String> options = tlsOptions(truststore(dir));
        options.put("tls.verify-hostname", "false");
        assertThat(verifiesHostnames(Objects.requireNonNull(CassandraTls.engineFactory(context(options)))))
                .isFalse();
    }
}

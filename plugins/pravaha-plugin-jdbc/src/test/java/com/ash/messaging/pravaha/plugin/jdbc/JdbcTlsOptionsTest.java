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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC is the one connector that cannot be handed an {@code SSLContext}, so the shared {@code
 * tls.*} options have to be refused rather than accepted and ignored.
 *
 * <p>Accepting them is the failure worth testing for: a config file that reads {@code tls.enabled:
 * true} over a connection that is plaintext on the wire is worse than no TLS support at all,
 * because nobody goes looking for a problem the configuration says they do not have.
 */
final class JdbcTlsOptionsTest {

    private static PluginContext context(Map<String, String> options) {
        return new PluginContext() {
            @Override
            public Map<String, String> config() {
                return options;
            }

            @Override
            public String instanceName() {
                return "orders";
            }
        };
    }

    @Test
    void aSourceRefusesTlsOptionsAndSaysWhereTheyBelong() {
        assertThatThrownBy(() -> new JdbcSourcePlugin().configure(context(Map.of("tls.enabled", "true"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("do not apply to a JDBC connector")
                .hasMessageContaining("sslmode=verify-full")
                .hasMessageContaining("VERIFY_IDENTITY");
    }

    @Test
    void aLookupRefusesThemToo() {
        // Both entry points, because a deployment that got the source right and the lookup wrong
        // would be encrypted for its stream and plaintext for its joins.
        assertThatThrownBy(() -> new JdbcLookupPlugin().configure(context(Map.of("tls.ca", "/etc/ca.pem"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("do not apply to a JDBC connector");
    }

    @Test
    void anExplicitFalseIsAcceptedAsADeliberateChoice() {
        // Writing tls.enabled: false is an operator saying the plaintext connection is intended.
        // That is a statement, not a mistake, and it must not be met with an error -- so the
        // refusal has to stop short of it. configure() still fails on the missing url; that it
        // reaches the url check at all is the assertion.
        assertThatThrownBy(() -> new JdbcSourcePlugin().configure(context(Map.of("tls.enabled", "false"))))
                .isInstanceOf(Exception.class)
                .hasMessageNotContaining("do not apply to a JDBC connector");
    }

    @Test
    void aConnectorThatNeverMentionsTlsIsUntouched() {
        assertThatCode(() -> new JdbcSourcePlugin().configure(context(Map.of())))
                .isInstanceOf(Exception.class)
                .hasMessageNotContaining("tls");
    }
}

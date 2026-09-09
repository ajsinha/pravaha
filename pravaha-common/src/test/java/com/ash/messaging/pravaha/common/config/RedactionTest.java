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
package com.ash.messaging.pravaha.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "db.password", "DB.PASSWORD", "aerospike.passwd", "signing.secret",
                "auth.token", "vault.credential", "tls.private-key", "service.apiKey",
                "x.api-key", "oidc.key", "key"
            })
    void masksThingsThatLookLikeSecrets(String key) {
        assertThat(Redaction.isSecret(key)).as(key).isTrue();
        assertThat(Redaction.mask(key, "hunter2")).isEqualTo(Redaction.MASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pravaha.lanes", "state.key.fields", "sink.key.prefix", "keyspace", "monkey.count"})
    void leavesOrdinaryKeysAlone(String key) {
        // "key" alone is far too common to match on: state.key.fields is not a credential, and
        // masking it would make the startup dump useless.
        assertThat(Redaction.isSecret(key)).as(key).isFalse();
        assertThat(Redaction.mask(key, "value")).isEqualTo("value");
    }

    @Test
    void theMaskDoesNotLeakTheValuesLength() {
        assertThat(Redaction.mask("password", "a")).isEqualTo(Redaction.mask("password", "a-very-long-secret"));
    }

    @Test
    void configValueRendersMasked() {
        ConfigValue v = new ConfigValue("db.password", "hunter2", ConfigSource.FILE, "/etc/app.properties");
        assertThat(v.describe()).contains(Redaction.MASK).doesNotContain("hunter2");
        // toString delegates to describe, so a stray log.info(value) cannot leak a credential.
        assertThat(v.toString()).doesNotContain("hunter2");
    }

    @Test
    void configValueRetainsProvenanceWhenResolved() {
        ConfigValue v = new ConfigValue("k", "${a}", ConfigSource.FILE, "/etc/app.properties");
        ConfigValue resolved = v.withValue("expanded");
        assertThat(resolved.value()).isEqualTo("expanded");
        assertThat(resolved.source()).isEqualTo(ConfigSource.FILE);
        assertThat(resolved.origin()).isEqualTo("/etc/app.properties");
        assertThat(v.withValue("${a}")).isSameAs(v);
    }
}

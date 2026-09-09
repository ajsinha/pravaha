/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

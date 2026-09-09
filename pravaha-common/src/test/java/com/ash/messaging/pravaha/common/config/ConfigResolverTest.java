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

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigResolverTest {

    private static Configuration of(String... keyThenValue) {
        ConfigurationBuilder b = Configuration.builder();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            b.set(keyThenValue[i], keyThenValue[i + 1]);
        }
        return b.build();
    }

    @Test
    void expandsASimpleReference() {
        assertThat(of("host", "db01", "url", "jdbc://${host}:5432").requireString("url"))
                .isEqualTo("jdbc://db01:5432");
    }

    @Test
    void expandsSeveralReferencesInOneValue() {
        assertThat(of("h", "a", "p", "1", "u", "${h}:${p}/${h}").requireString("u"))
                .isEqualTo("a:1/a");
    }

    @Test
    void expandsTransitively() {
        assertThat(of("a", "1", "b", "${a}2", "c", "${b}3").requireString("c")).isEqualTo("123");
    }

    @Test
    void usesTheInlineDefaultWhenTheKeyIsAbsent() {
        assertThat(of("u", "${missing:fallback}").requireString("u")).isEqualTo("fallback");
    }

    @Test
    void prefersTheActualValueOverTheInlineDefault() {
        assertThat(of("k", "real", "u", "${k:fallback}").requireString("u")).isEqualTo("real");
    }

    @Test
    void resolvesReferencesInsideADefault() {
        assertThat(of("fb", "from-fallback", "u", "${missing:${fb}}").requireString("u"))
                .isEqualTo("from-fallback");
    }

    @Test
    void anEmptyDefaultIsAValidEmptyString() {
        assertThat(of("u", "x${missing:}y").requireString("u")).isEqualTo("xy");
    }

    @Test
    void aDefaultMayContainColons() {
        // Only the first colon separates key from default, so a URL default survives intact.
        assertThat(of("u", "${missing:redis://host:6379}").requireString("u")).isEqualTo("redis://host:6379");
    }

    @Test
    void resolvesAcrossSources() {
        // A value in a file referring to a key supplied by the environment is the whole point of
        // resolving once against the merged result rather than per source.
        Configuration c = Configuration.builder()
                .addDefaults(Map.of("url", "jdbc://${db.host}/app"))
                .addEnvironment("db", () -> Map.of("DB_HOST", "prod-db"))
                .build();
        assertThat(c.requireString("url")).isEqualTo("jdbc://prod-db/app");
    }

    @Test
    void anUnresolvedReferenceIsFatalAndSaysHowToFixIt() {
        // Leaving the literal ${...} in place so "the problem is visible" is how a placeholder ends
        // up in a connection string. Refusing to start is louder and safer.
        assertThatThrownBy(() -> of("url", "jdbc://${db.host}/app"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1010")
                .hasMessageContaining("db.host")
                .hasMessageContaining("${db.host:some-default}");
    }

    @Test
    void aDirectCycleIsReportedWithThePath() {
        assertThatThrownBy(() -> of("a", "${b}", "b", "${a}"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1011")
                .hasMessageContaining("circular");
    }

    @Test
    void aLongerCycleIsAlsoCaught() {
        assertThatThrownBy(() -> of("a", "${b}", "b", "${c}", "c", "${a}"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1011");
    }

    @Test
    void aSelfReferenceIsACycle() {
        assertThatThrownBy(() -> of("a", "${a}"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1011");
    }

    @Test
    void anUnclosedBraceIsMalformedRatherThanIgnored() {
        assertThatThrownBy(() -> of("a", "${unclosed"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1002")
                .hasMessageContaining("unclosed");
    }

    @Test
    void aValueWithNoReferenceIsUntouched() {
        assertThat(of("a", "plain $ value { with } braces").requireString("a"))
                .isEqualTo("plain $ value { with } braces");
    }

    @Test
    void secretsAreMaskedInResolutionErrors() {
        // The failing value is echoed back to help diagnosis, so it must not echo a credential.
        assertThatThrownBy(() -> of("db.password", "${unclosed"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(Redaction.MASK)
                .hasMessageNotContaining("unclosed$");
    }
}

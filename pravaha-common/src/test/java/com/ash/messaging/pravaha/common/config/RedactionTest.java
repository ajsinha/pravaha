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
    @SuppressWarnings("NullAway") // null on purpose: pins that a null text passes through
    void bindingOptionValuesAreStruckByKeyAndByLength() {
        java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
        options.put("password", "abc"); // a credential's key: struck at three characters
        options.put("topic", "orders-events-v2"); // long enough to be one whatever its key
        options.put("mode", "fast"); // short, and not a credential's key: kept
        options.put("user", "ab"); // a credential's key, but too short to strike without gutting the text

        String struck = Redaction.strikeOptionValues(
                "login abc to orders-events-v2 in fast mode as ab", java.util.List.of(options));

        assertThat(struck).isEqualTo("login [redacted password] to [redacted topic] in fast mode as ab");
        assertThat(Redaction.strikeOptionValues(null, java.util.List.of(options)))
                .isNull();
    }

    @Test
    void theOptionRedactionRuleIsWrittenOnceInTheWholeTree() throws Exception {
        // SINK-4: the rule was written out three times -- the sinks' resolver, the source feeds'
        // redaction and the HTTP surface -- so a change to it would reach one of three. The
        // pattern's text is the fingerprint of a copy.
        String fingerprint = "pass|secret|token|key|credential";
        java.nio.file.Path root = java.nio.file.Path.of("..").toRealPath();
        java.util.List<String> copies = new java.util.ArrayList<>();
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                    java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attributes) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                // Hidden directories hold other worktrees' copies of this tree; build output and
                // the console's dependencies are not source.
                boolean skip = !dir.equals(root)
                        && (name.startsWith(".") || name.equals("target") || name.equals("node_modules"));
                return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                    java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attributes)
                    throws java.io.IOException {
                String relative = root.relativize(file).toString();
                if (relative.endsWith(".java")
                        && relative.contains("src/main/java/")
                        && java.nio.file.Files.readString(file).contains(fingerprint)) {
                    copies.add(relative);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        assertThat(copies)
                .containsExactly("pravaha-common/src/main/java/com/ash/messaging/pravaha/common/config/Redaction.java");
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

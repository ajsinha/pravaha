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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-052, stage 6: no configuration file in this repository holds a secret. People's passwords and
 * API keys live in the engine's store, hashed; what a deployment must supply at install time is a
 * placeholder (@@NAME@@) that install.sh fills, or an environment reference (${NAME}). A literal here
 * would ship in every checkout, image and bundle.
 */
class NoSecretsInConfigurationTest {

    /** A key that names a secret, with a value on the same line. */
    private static final Pattern SECRET = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]*(?:password|secret|token|api[_-]?key)[A-Za-z0-9_.-]*)\\s*:\\s*(\\S.*?)\\s*(?:#.*)?$",
            Pattern.CASE_INSENSITIVE);

    /** An entry of a token table: a quoted map key, which is the credential itself. */
    private static final Pattern TOKEN_ENTRY = Pattern.compile("^\\s+\"([^\"]+)\"\\s*:\\s*$");

    private static final Pattern SAFE = Pattern.compile(
            "\"\"|''|\"?@@[A-Z0-9_]+@@\"?|\"?\\$\\{[^}]*}\"?|([\"']?)(true|false|[0-9]+|[0-9]+(ms|s|m|h|d))\\1|\\{\\}|\\[\\]");

    @Test
    void noTrackedConfigurationHoldsALiteralSecret() throws IOException {
        List<String> found = new ArrayList<>();
        Path root = repoRoot();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> isShippedConfiguration(root.relativize(f)))
                    .toList()) {
                found.addAll(
                        secretsIn(root.relativize(file).toString(), Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        assertThat(found)
                .as("configuration that ships holds these secrets; use a placeholder install.sh fills (@@NAME@@) or "
                        + "an environment reference (${NAME}), and keep people's credentials in the engine's store")
                .isEmpty();
    }

    @Test
    void aRootInsideAWorktreeIsScannedAndOnlyNestedWorktreesAreSkipped() {
        assertThat(isShippedConfiguration(Path.of("deploy/qa/server.application.yaml")))
                .isTrue();
        assertThat(isShippedConfiguration(Path.of(".claude/worktrees/other/deploy/qa/server.application.yaml")))
                .isFalse();
        assertThat(isShippedConfiguration(Path.of("pravaha-server/target/classes/application.yaml")))
                .isFalse();
    }

    @Test
    void theCheckFindsASecretWrittenIntoConfiguration() {
        String yaml = String.join(
                "\n",
                "console:",
                "  password: hunter2hunter2",
                "  session_secret: \"@@SESSION_SECRET@@\"",
                "  token: ${PRAVAHA_TOKEN}",
                "  reset-token-life: 60m",
                "  tokens:",
                "    \"a-real-bearer-credential\":",
                "      id: qa",
                "    \"@@QA_TOKEN@@\":",
                "      id: qa2");
        assertThat(secretsIn("seed.yaml", yaml))
                .containsExactly("seed.yaml:2 password", "seed.yaml:7 a token-table credential");
    }

    static List<String> secretsIn(String name, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("#")) {
                continue;
            }
            Matcher secret = SECRET.matcher(line);
            if (secret.matches()
                    && !namesWhereASecretIs(secret.group(1))
                    && !SAFE.matcher(secret.group(2)).matches()) {
                found.add(name + ":" + (i + 1) + " " + secret.group(1));
            }
            Matcher entry = TOKEN_ENTRY.matcher(line);
            if (entry.matches() && !entry.group(1).matches("@@[A-Z0-9_]+@@")) {
                found.add(name + ":" + (i + 1) + " a token-table credential");
            }
        }
        return found;
    }

    /**
     * A key whose value says where a secret is rather than what it is: a file holding one, or the name
     * of a Kubernetes Secret the chart mounts.
     */
    private static boolean namesWhereASecretIs(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.endsWith("-file") || k.equals("existingsecret") || k.equals("pullsecrets") || k.equals("secretname");
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("").toAbsolutePath() : path;
    }

    /**
     * Judged on the path relative to the root being scanned (NOSECRETSSKIP-1): excluding every absolute
     * path that contains {@code /.claude/} excluded everything when the test ran from a worktree, which
     * itself lives under {@code .claude/worktrees/}. Relative to the root, {@code .claude/} matches only
     * other worktrees nested below it, which are other checkouts.
     */
    static boolean isShippedConfiguration(Path relative) {
        String p = "/" + relative.toString().replace('\\', '/');
        return (p.endsWith(".yaml") || p.endsWith(".yml"))
                && !p.contains("/target/")
                && !p.contains("/.claude/")
                && !p.contains("/.git/")
                && !p.contains("/.venv/")
                && !p.contains("/node_modules/")
                && !p.contains("/src/test/")
                && !p.contains("/tests/")
                && !p.contains("/.github/")
                && !p.contains("/examples/")
                && !p.contains("/templates/")
                && !p.endsWith(".local.yaml");
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationBuilderTest {

    @Test
    void higherRankedSourcesWinRegardlessOfTheOrderTheyWereAdded(@TempDir Path dir) throws IOException {
        // Precedence is by source rank, not insertion order. Adding a file last must not let it
        // override a command-line flag -- that inversion is easy to introduce and hard to notice.
        Path file = write(dir, "a.properties", "pravaha.lanes=4");

        Configuration fileLast = Configuration.builder()
                .addCommandLine("--pravaha.lanes=16")
                .addFile(file)
                .build();
        Configuration fileFirst = Configuration.builder()
                .addFile(file)
                .addCommandLine("--pravaha.lanes=16")
                .build();

        assertThat(fileLast.requireInt("pravaha.lanes")).isEqualTo(16);
        assertThat(fileFirst.requireInt("pravaha.lanes")).isEqualTo(16);
        assertThat(fileLast.sourceOf("pravaha.lanes")).hasValue(ConfigSource.COMMAND_LINE);
    }

    @Test
    void fullPrecedenceChain(@TempDir Path dir) throws IOException {
        Path file = write(dir, "app.properties", "k=from-file");
        write(dir, "app.local.properties", "k=from-overlay");

        assertThat(Configuration.builder()
                        .addDefaults(Map.of("k", "from-default"))
                        .build()
                        .requireString("k"))
                .isEqualTo("from-default");

        assertThat(Configuration.builder()
                        .addDefaults(Map.of("k", "from-default"))
                        .addFile(file)
                        .build()
                        .sourceOf("k"))
                .hasValue(ConfigSource.LOCAL_OVERLAY);

        Configuration all = Configuration.builder()
                .addDefaults(Map.of("k", "from-default"))
                .addFile(file)
                .addEnvironment("k", () -> Map.of("K", "from-env"))
                .addCommandLine("--k=from-cli")
                .set("k", "from-code")
                .build();
        assertThat(all.requireString("k")).isEqualTo("from-code");
        assertThat(all.sourceOf("k")).hasValue(ConfigSource.PROGRAMMATIC);
    }

    @Test
    void rightmostFileWinsAmongEqualRankedSources(@TempDir Path dir) throws IOException {
        Path first = write(dir, "1.properties", "k=first\nonly-first=yes");
        Path second = write(dir, "2.properties", "k=second");
        Configuration c = Configuration.builder().addFile(first).addFile(second).build();

        assertThat(c.requireString("k")).isEqualTo("second");
        // A later file sets only what it names; it does not replace the earlier one wholesale.
        assertThat(c.requireString("only-first")).isEqualTo("yes");
    }

    @Test
    void localOverlayIsPickedUpAutomaticallyAndItsAbsenceIsANoOp(@TempDir Path dir) throws IOException {
        Path file = write(dir, "app.properties", "shared=base\nsecret=placeholder");
        Configuration without = Configuration.builder().addFile(file).build();
        assertThat(without.requireString("secret")).isEqualTo("placeholder");

        write(dir, "app.local.properties", "secret=real-value");
        Configuration with = Configuration.builder().addFile(file).build();
        assertThat(with.requireString("secret")).isEqualTo("real-value");
        assertThat(with.requireString("shared")).isEqualTo("base");
        assertThat(with.sourceOf("secret")).hasValue(ConfigSource.LOCAL_OVERLAY);
        assertThat(with.sourceOf("shared")).hasValue(ConfigSource.FILE);
    }

    @Test
    void environmentVariableNamesMapToDottedKeys() {
        Configuration c = Configuration.builder()
                .addEnvironment(
                        "pravaha",
                        () -> Map.of(
                                "PRAVAHA_RUNTIME_LANES", "16",
                                "PRAVAHA_STATE_DEFAULT_TIER", "HYBRID",
                                "UNRELATED_THING", "ignored"))
                .build();

        assertThat(c.requireInt("pravaha.runtime.lanes")).isEqualTo(16);
        assertThat(c.requireString("pravaha.state.default.tier")).isEqualTo("HYBRID");
        assertThat(c.has("unrelated.thing")).isFalse();
    }

    @Test
    @SuppressWarnings("NullAway") // a null argv entry on purpose: the parser skips it
    void commandLineIgnoresAnythingThatIsNotAKeyValueFlag() {
        // The same argv usually carries a subcommand and positional arguments that are none of
        // this parser's business, so they are skipped rather than rejected.
        Configuration c = Configuration.builder()
                .addCommandLine("dev", "--lanes=8", "-x", "--", "--=empty", "positional", null)
                .build();
        assertThat(c.requireInt("lanes")).isEqualTo(8);
        assertThat(c.size()).isOne();
    }

    @Test
    void commandLineValueMayContainEqualsSigns() {
        Configuration c = Configuration.builder()
                .addCommandLine("--uri=redis://h:6379?a=b")
                .build();
        assertThat(c.requireString("uri")).isEqualTo("redis://h:6379?a=b");
    }

    @Test
    void aMissingRequiredFileIsFatalButAnOptionalOneIsNot(@TempDir Path dir) {
        Path absent = dir.resolve("nope.properties");
        assertThatThrownBy(() -> Configuration.builder().addFile(absent))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1001")
                .hasMessageContaining("nope.properties");
        assertThat(Configuration.builder().addOptionalFile(absent).build().size())
                .isZero();
    }

    @Test
    void anUnknownExtensionNamesTheFormatsThatAreRegistered(@TempDir Path dir) throws IOException {
        Path odd = write(dir, "config.xyz", "k=v");
        assertThatThrownBy(() -> Configuration.builder().addFile(odd))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("properties");
    }

    @Test
    void systemPropertiesAreRead() {
        String key = "pravaha.test.systemprop";
        System.setProperty(key, "yes");
        try {
            assertThat(Configuration.builder()
                            .addSystemProperties("pravaha.test.")
                            .build()
                            .requireString(key))
                    .isEqualTo("yes");
        } finally {
            System.clearProperty(key);
        }
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }
}

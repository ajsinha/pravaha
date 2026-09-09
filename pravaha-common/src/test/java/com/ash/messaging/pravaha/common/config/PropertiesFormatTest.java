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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesFormatTest {

    private final PropertiesFormat format = new PropertiesFormat();

    @Test
    void parsesKeysValuesCommentsAndBothSeparators(@TempDir Path dir) throws IOException {
        Map<String, String> parsed = format.parse(write(dir, """
                # a comment
                ! also a comment

                pravaha.lanes = 16
                pravaha.mode: HA
                empty.value=
                spaces  =  trimmed
                """));

        assertThat(parsed)
                .containsEntry("pravaha.lanes", "16")
                .containsEntry("pravaha.mode", "HA")
                .containsEntry("empty.value", "")
                .containsEntry("spaces", "trimmed")
                .hasSize(4);
    }

    @Test
    void preservesDeclarationOrder(@TempDir Path dir) throws IOException {
        // java.util.Properties is a Hashtable and loses this. Order matters for diagnostics: an
        // operator reading the startup dump expects it to look like the file they wrote.
        Map<String, String> parsed = format.parse(write(dir, "z=1\na=2\nm=3\n"));
        assertThat(parsed.keySet()).containsExactly("z", "a", "m");
    }

    @Test
    void joinsContinuationLines(@TempDir Path dir) throws IOException {
        Map<String, String> parsed = format.parse(write(dir, """
                peers = node-1:9070, \\
                        node-2:9070, \\
                        node-3:9070
                """));
        // Whitespace before the backslash is kept and leading whitespace on the continuation is
        // dropped, matching java.util.Properties. getList strips the rest, which is what callers
        // actually consume.
        assertThat(parsed.get("peers")).isEqualTo("node-1:9070, node-2:9070, node-3:9070");
        assertThat(Configuration.builder()
                        .set("peers", parsed.get("peers"))
                        .build()
                        .getList("peers"))
                .containsExactly("node-1:9070", "node-2:9070", "node-3:9070");
    }

    @Test
    void aValueMayContainSeparatorCharacters(@TempDir Path dir) throws IOException {
        Map<String, String> parsed = format.parse(write(dir, "uri=redis://host:6379?a=b\n"));
        assertThat(parsed.get("uri")).isEqualTo("redis://host:6379?a=b");
    }

    @Test
    void unescapesTheUsualSequences(@TempDir Path dir) throws IOException {
        Map<String, String> parsed = format.parse(write(dir, "msg=line\\nnext\\ttabbed\n"));
        assertThat(parsed.get("msg")).isEqualTo("line\nnext\ttabbed");
    }

    @Test
    void namesTheLineNumberOnAMalformedEntry(@TempDir Path dir) throws IOException {
        // A configuration error is read by someone who is stuck. "Cannot parse" alone is not enough.
        Path file = write(dir, "good=1\n\nthis line has no separator\n");
        assertThatThrownBy(() -> format.parse(file))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1002")
                .hasMessageContaining(":3")
                .hasMessageContaining("no separator");
    }

    @Test
    void rejectsAnEmptyKey(@TempDir Path dir) throws IOException {
        Path file = write(dir, "=orphan\n");
        assertThatThrownBy(() -> format.parse(file))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("empty key");
    }

    @Test
    void rejectsAFileEndingInAContinuation(@TempDir Path dir) throws IOException {
        Path file = write(dir, "k=value \\\n");
        assertThatThrownBy(() -> format.parse(file))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("continuation");
    }

    @Test
    void reportsAnUnreadableFile(@TempDir Path dir) {
        assertThatThrownBy(() -> format.parse(dir.resolve("absent.properties")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1001");
    }

    @Test
    void lastDuplicateWins(@TempDir Path dir) throws IOException {
        assertThat(format.parse(write(dir, "k=first\nk=second\n"))).containsEntry("k", "second");
    }

    @Test
    void claimsTheExpectedExtensions() {
        assertThat(format.extensions()).contains("properties", "props", "conf");
        assertThat(ConfigFormat.forFile(Path.of("a.properties"))).isPresent();
        assertThat(ConfigFormat.forFile(Path.of("a.yaml"))).isEmpty();
        assertThat(ConfigFormat.forFile(Path.of("noextension"))).isEmpty();
    }

    private static Path write(Path dir, String content) throws IOException {
        Path p = dir.resolve("test.properties");
        Files.writeString(p, content);
        return p;
    }
}

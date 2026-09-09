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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every source file carries the copyright and licence notice.
 *
 * <p>Spotless applies the header on {@code validate}, but it skips {@code package-info.java}, and a
 * file added outside the build's reach would slip through silently. Copyright notices are the kind
 * of thing that is complete or worthless, so it is checked rather than assumed.
 */
class LicenseHeaderTest {

    private static final String COPYRIGHT = "Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>";
    private static final String LICENCE = "Licensed under the Apache License, Version 2.0";
    private static final List<String> EXEMPT = List.of("/target/", "/node_modules/", "/src/main/frontend/");

    @Test
    void everyJavaSourceCarriesTheCopyrightAndLicence() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path file : sources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.contains(COPYRIGHT) || !text.contains(LICENCE)) {
                missing.add(repoRoot().relativize(file).toString());
            }
        }
        assertThat(missing)
                .as("every .java file must carry the copyright and Apache 2.0 notice; run ./mvnw spotless:apply")
                .isEmpty();
    }

    @Test
    void theRepositoryCarriesItsLicenceAndNotice() {
        Path root = repoRoot();
        assertThat(root.resolve("LICENSE")).exists();
        assertThat(root.resolve("NOTICE")).exists();
        assertThat(contentOf(root.resolve("NOTICE"))).contains("Ashutosh Sinha").contains("Apache License");
        assertThat(contentOf(root.resolve("LICENSE")))
                .contains("Apache License")
                .contains("Version 2.0")
                // The appendix boilerplate must name the actual owner, as the licence instructs.
                .contains("Copyright 2026 Ashutosh Sinha");
    }

    @Test
    void theHeaderIsAsciiOnly() {
        // The header appears in every source file. A non-ASCII byte there is a gratuitous encoding
        // risk for patch tools, terminals and IDEs; the Devanagari belongs in the docs instead.
        String header = contentOf(repoRoot().resolve("config/spotless/license-header.txt"));
        assertThat(header.chars().allMatch(c -> c < 128))
                .as("the source-file licence header must stay ASCII-only")
                .isTrue();
    }

    @Test
    void theRuleActuallyScansSomething() {
        assertThat(sources()).hasSizeGreaterThan(20);
    }

    private static String contentOf(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(repoRoot())) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> EXEMPT.stream()
                            .noneMatch(seg -> p.toString().replace('\\', '/').contains(seg)))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk the repository", e);
        }
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }
}

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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the project's file-size limit.
 *
 * <p>A 1500-line ceiling is a proxy for modularity, not an end in itself: a file that long has
 * almost always accumulated more than one responsibility. Catching it mechanically means the
 * conversation happens at the pull request rather than two years later during a refactor.
 *
 * <p>Documentation and UI code are exempt by project rule -- prose and component trees have
 * different natural shapes.
 */
class SourceFileSizeTest {

    private static final int MAX_LINES = 1500;

    /** Warn well before the limit so a file is split deliberately rather than in a panic. */
    private static final int WARN_LINES = 1200;

    private static final List<String> EXEMPT_PATH_SEGMENTS = List.of("/pravaha-ui/", "/src/main/frontend/", "/target/");

    @Test
    void noJavaSourceFileExceedsTheLineLimit() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> approaching = new ArrayList<>();

        for (Path file : javaSources()) {
            long lines = countLines(file);
            String rendered = repoRoot().relativize(file) + " (" + lines + " lines)";
            if (lines > MAX_LINES) {
                violations.add(rendered);
            } else if (lines > WARN_LINES) {
                approaching.add(rendered);
            }
        }

        if (!approaching.isEmpty()) {
            System.out.println("Files approaching the " + MAX_LINES + "-line limit:");
            approaching.forEach(f -> System.out.println("  " + f));
        }

        assertThat(violations)
                .as(
                        "Java source files must stay under %d lines (project rule). Split the "
                                + "responsibilities rather than raising the limit.",
                        MAX_LINES)
                .isEmpty();
    }

    @Test
    void theRuleActuallyScansSomething() {
        // A path-matching bug would make this suite silently vacuous, which is worse than no rule
        // at all: it would read as a passing check while enforcing nothing.
        assertThat(javaSources()).hasSizeGreaterThan(20);
    }

    private static List<Path> javaSources() {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> EXEMPT_PATH_SEGMENTS.stream()
                            .noneMatch(seg -> p.toString().replace('\\', '/').contains(seg)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + root, e);
        }
    }

    private static long countLines(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null
                && !Files.exists(p.resolve("pom.xml").normalize().getParent().resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }
}

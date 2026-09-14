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
                    .filter(p -> !p.startsWith(nestedCheckouts()))
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

    /**
     * The directory QA agents keep their git worktrees in, under the tree being walked.
     *
     * <p>Those worktrees are full copies of this repository, so a walk of the root sees one copy of
     * every source file per running agent -- and a check that counts the files a call site appears
     * in reports three where it expects one. It presents as a product change and is not one.
     *
     * <p>Compared against the root actually being walked, not as a substring. A test run from
     * inside one of those worktrees has a root whose own path contains {@code /.claude/}, and a
     * substring test would discard the entire tree and pass on nothing at all.
     */
    private static Path nestedCheckouts() {
        return repoRoot().resolve(".claude");
    }
}

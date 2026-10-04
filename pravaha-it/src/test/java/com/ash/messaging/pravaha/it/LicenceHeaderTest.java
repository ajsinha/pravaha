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
 * Every source file says who owns it and on what terms.
 *
 * <p>The licence is proprietary and nothing has been published from this tree, so a file without
 * the notice grants nothing by accident -- but it is the file that gets copied into a support
 * ticket, an issue or somebody's editor, and it travels without the LICENSE beside it. LIC-1 was
 * the same failure one level up: the root POM granted Apache-2.0 while every other statement in
 * the repository said proprietary, and a POM is what a downstream build machine parses.
 *
 * <p>Nothing enforced this, which is how twenty-one Python files and two shell scripts came to
 * carry no notice at all while all 1,000-odd Java files carried one. A convention that only holds
 * because everyone remembers it holds until somebody does not.
 *
 * <p>The check is deliberately weak about wording: three spellings are in use (the Java block, the
 * console's two docstring lines, the SDK's) and unifying them would be churn for its own sake.
 * What it requires is that the first lines of the file name the copyright holder and say the terms
 * are proprietary.
 */
class LicenceHeaderTest {

    /** Where a notice has to be. Past this, a reader scrolling to the code has already missed it. */
    private static final int HEADER_LINES = 12;

    private static final List<String> EXTENSIONS = List.of(".java", ".py", ".sh");

    private static final List<String> EXEMPT_PATH_SEGMENTS = List.of(
            "/target/",
            "/build/",
            "/node_modules/",
            "/.venv/",
            "/venv/",
            "/mvnw",
            // Vendored third-party code keeps its own licence; ours would be a false claim on it.
            "/static/vendor/");

    @Test
    void everySourceFileCarriesTheProprietaryNotice() throws IOException {
        List<String> bare = new ArrayList<>();
        for (Path file : sources()) {
            if (!carriesNotice(file)) {
                bare.add(repoRoot().relativize(file).toString());
            }
        }

        assertThat(bare)
                .as(
                        "every source file states the copyright holder and that the terms are "
                                + "proprietary, within its first %d lines. The licence is the owner's "
                                + "standing instruction -- everywhere, the proprietary licence -- and a "
                                + "file travels without the LICENSE beside it",
                        HEADER_LINES)
                .isEmpty();
    }

    @Test
    void theRuleActuallyScansSomething() {
        // A path-matching bug would make this vacuous, which reads as a passing check enforcing
        // nothing -- the same shape of defect the notice itself is meant to prevent.
        assertThat(sources()).hasSizeGreaterThan(500);
        assertThat(sources().stream().filter(p -> p.toString().endsWith(".py")))
                .as("Python is where the drift was, so a scan that stopped seeing it must fail")
                .hasSizeGreaterThan(20);
    }

    private static boolean carriesNotice(Path file) throws IOException {
        String head;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            head = lines.limit(HEADER_LINES).reduce("", (a, b) -> a + "\n" + b);
        }
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ashutosh sinha") && lower.contains("proprietary");
    }

    private static List<Path> sources() {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> EXTENSIONS.stream().anyMatch(e -> p.toString().endsWith(e)))
                    .filter(p -> !p.startsWith(nestedCheckouts()))
                    .filter(p -> EXEMPT_PATH_SEGMENTS.stream()
                            .noneMatch(seg -> p.toString().replace('\\', '/').contains(seg)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + root, e);
        }
    }

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
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

    /** The directory QA agents keep their worktrees in: full copies of this tree, not this tree. */
    private static Path nestedCheckouts() {
        return repoRoot().resolve(".claude");
    }
}

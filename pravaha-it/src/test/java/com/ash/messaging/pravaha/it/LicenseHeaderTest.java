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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every source file carries the copyright and proprietary-notice header.
 *
 * <p>Spotless applies the header on {@code validate}, but it skips {@code package-info.java}, and a
 * file added outside the build's reach would slip through silently. For proprietary software the
 * notice is not decoration: a file that escapes without it is materially harder to defend as
 * confidential, so completeness is checked rather than assumed.
 */
class LicenseHeaderTest {

    private static final String COPYRIGHT = "Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>";
    private static final String LICENCE = "PROPRIETARY AND CONFIDENTIAL";
    private static final List<String> EXEMPT = List.of("/target/", "/node_modules/", "/src/main/frontend/");

    @Test
    void everyJavaSourceCarriesTheCopyrightAndProprietaryNotice() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path file : sources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.contains(COPYRIGHT) || !text.contains(LICENCE)) {
                missing.add(repoRoot().relativize(file).toString());
            }
        }
        assertThat(missing)
                .as("every .java file must carry the copyright and proprietary notice; run ./mvnw spotless:apply")
                .isEmpty();
    }

    @Test
    void theRepositoryCarriesItsLicenceAndThirdPartyNotices() {
        Path root = repoRoot();
        assertThat(root.resolve("LICENSE")).exists();
        assertThat(root.resolve("THIRD-PARTY-NOTICES.md")).exists();

        assertThat(contentOf(root.resolve("LICENSE")))
                .contains("Ashutosh Sinha")
                .contains("All rights reserved")
                .contains("Proprietary and Confidential")
                .doesNotContain("Apache License, Version 2.0");

        // Third-party attribution survives the move to proprietary terms: those licences are not
        // ours to waive, and several of them require their notices be reproduced.
        assertThat(contentOf(root.resolve("THIRD-PARTY-NOTICES.md")))
                .contains("Agrona")
                .contains("JCTools")
                .contains("Apache License 2.0");
    }

    @Test
    void noSourceFileClaimsAnOpenSourceLicence() {
        // Guards against a file being pasted in with its original header intact, which would put a
        // contradictory grant on proprietary code.
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String text = contentOf(file);
            int headerEnd = text.indexOf("*/");
            String header = headerEnd < 0 ? text : text.substring(0, headerEnd);
            if (header.contains("Apache License")
                    || header.contains("MIT License")
                    || header.contains("GNU General Public")) {
                offenders.add(repoRoot().relativize(file).toString());
            }
        }
        assertThat(offenders)
                .as("source headers must not carry a third-party licence grant")
                .isEmpty();
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
                    .filter(p -> !p.startsWith(nestedCheckouts()))
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

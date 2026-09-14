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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/qa/FINDINGS.md} as a register rather than a narrative.
 *
 * <p>It was a narrative, and that made it useless as a status report. Findings were appended, some
 * were re-confirmed under new identifiers in later rounds, and whether a fix was recorded depended
 * on who last edited the entry. Counting headings said 102 of 129 had no fix recorded -- while nine
 * of those named in that count had been fixed, verified and merged, one of them the same day. A
 * record that cannot answer "what is still open" is not one.
 *
 * <p>So every finding carries a status line, and this checks that it does. The ratchet below is the
 * part that makes it useful: untriaged findings may only ever decrease.
 */
class FindingsRegisterTest {

    /** The statuses a finding may carry. Anything else is a typo, not a new category. */
    private static final Set<String> STATUSES = Set.of("OPEN", "FIXED", "SUPERSEDED", "BY DESIGN", "UNTRIAGED");

    /**
     * How many findings may still be untriaged.
     *
     * <p>A ratchet, not a target. Lower it as findings are triaged; it may never be raised, which is
     * what stops the register sliding back into a pile of prose. It started at 108.
     */
    private static final int UNTRIAGED_CEILING = 0;

    private static final Pattern HEADING = Pattern.compile("^#{2,3} ([A-Z]+-[A-Z]?\\d+)([^\\n]*)$");

    private record Finding(String id, String status) {}

    private static List<Finding> findings() throws IOException {
        String text = Files.readString(repoRoot().resolve("docs/qa/FINDINGS.md"), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        List<Finding> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher heading = HEADING.matcher(lines[i]);
            if (!heading.matches()) {
                continue;
            }
            String status = null;
            // The status line comes straight after the heading, blank line permitted.
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                if (lines[j].startsWith("> **Status:**")) {
                    status = lines[j].substring("> **Status:**".length()).trim();
                    break;
                }
            }
            found.add(new Finding(heading.group(1), status));
        }
        return found;
    }

    @Test
    void everyFindingCarriesAStatus() throws IOException {
        List<String> without = findings().stream()
                .filter(f -> f.status() == null)
                .map(Finding::id)
                .toList();

        assertThat(without)
                .as("every finding needs a line '> **Status:** <OPEN|FIXED|SUPERSEDED|BY DESIGN|UNTRIAGED>' "
                        + "directly under its heading. Without it the file cannot answer what is still open, "
                        + "which is the only question anybody reads it to answer")
                .isEmpty();
    }

    @Test
    void everyStatusIsOneThisProjectRecognises() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Finding finding : findings()) {
            if (finding.status() == null) {
                continue;
            }
            String word = finding.status().split("—|--|\\.")[0].trim().toUpperCase(java.util.Locale.ROOT);
            if (!STATUSES.contains(word)) {
                wrong.add(finding.id() + ": '" + word + "'");
            }
        }
        assertThat(wrong)
                .as("a status must be one of %s, optionally followed by an em-dash and the evidence", STATUSES)
                .isEmpty();
    }

    @Test
    void theUntriagedCountOnlyEverFalls() throws IOException {
        long untriaged = findings().stream()
                .filter(f -> f.status() != null
                        && f.status().toUpperCase(java.util.Locale.ROOT).startsWith("UNTRIAGED"))
                .count();

        assertThat(untriaged)
                .as("untriaged findings may only decrease. If this fails because the number went up, a finding "
                        + "was added without being triaged; if it fails because the number went down, lower "
                        + "UNTRIAGED_CEILING to match and keep the ratchet tight")
                .isLessThanOrEqualTo(UNTRIAGED_CEILING);
    }

    @Test
    void aFixedFindingSaysWhatMakesItFixed() throws IOException {
        // A bare "FIXED" is the state this file was already in: a claim with nothing behind it.
        List<String> bare = findings().stream()
                .filter(f -> f.status() != null)
                .filter(f -> f.status().trim().equalsIgnoreCase("FIXED"))
                .map(Finding::id)
                .toList();

        assertThat(bare)
                .as("a FIXED status must name its evidence after an em-dash -- the commit, the test, or the "
                        + "prose entry that records it")
                .isEmpty();
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("").toAbsolutePath() : path;
    }
}

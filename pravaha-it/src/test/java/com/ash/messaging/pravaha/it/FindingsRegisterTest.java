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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/project/qa/FINDINGS.md} as a register rather than a narrative.
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

    /**
     * What a finding's heading looks like.
     *
     * <p>Widened twice, and both times because findings had quietly stopped being covered. First for
     * {@code API-F1}..{@code API-F11}, whose letter before the number slipped past {@code [A-Z]+-\d+}.
     * Then for {@code W8-1}..{@code W8-14} -- every Wave 8 finding, written by three people in one
     * afternoon -- whose digit inside the prefix slipped past {@code [A-Z]+-}. Ten findings were in
     * the file, well-formed and readable, and the register could not see one of them.
     *
     * <p>{@link #everyHeadingThatLooksLikeAFindingIsCaptured()} exists so the third shape is caught
     * by the build rather than by somebody noticing.
     */
    private static final Pattern HEADING = Pattern.compile("^#{2,3} ([A-Z]+[0-9]*-[A-Z]?\\d+)([^\\n]*)$");

    /** The severity token in a heading, where the heading carries one. */
    private static final Pattern SEVERITY = Pattern.compile("^ \\(([A-Z-]+)\\)");

    /**
     * The only severity words this register may use.
     *
     * <p>Before the triage it used six spellings for three levels -- {@code MED} and {@code MEDIUM}
     * both, {@code MED-HIGH} and {@code MEDIUM-HIGH} both. A register that cannot be sorted by
     * severity cannot be triaged by severity, which is most of why 144 findings sat undisposed.
     */
    private static final Set<String> SEVERITIES =
            Set.of("BLOCKER", "HIGH", "MEDIUM-HIGH", "MEDIUM", "LOW-MEDIUM", "LOW");

    /**
     * What an open finding says about a release.
     *
     * <p>GA-BLOCKER is reserved for a promise broken <em>silently</em> -- a wrong answer returned as
     * correct, data lost without a refusal, or data reaching a principal not authorised for it.
     * NOTE means the entry is not a defect at all.
     */
    private static final Set<String> DISPOSITIONS = Set.of("GA-BLOCKER", "GA-REQUIRED", "POST-GA", "WON'T-FIX", "NOTE");

    /**
     * Anything that reads like a finding identifier, however it is spelled.
     *
     * <p>Deliberately looser than {@link #HEADING}. Its job is to notice headings that {@code
     * HEADING} does not, which is the only way a pattern can report its own blind spot.
     */
    private static final Pattern LOOKS_LIKE_A_FINDING =
            Pattern.compile("^#{2,3} ([A-Za-z][A-Za-z0-9]*-[A-Za-z0-9]+)\\b.*$");

    private record Finding(String id, String status, String disposition, String severity) {}

    private static List<Finding> findings() throws IOException {
        String text = Files.readString(repoRoot().resolve("docs/project/qa/FINDINGS.md"), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        List<Finding> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher heading = HEADING.matcher(lines[i]);
            if (!heading.matches()) {
                continue;
            }
            String status = null;
            String disposition = null;
            // The status line comes straight after the heading, blank line permitted. The
            // disposition line, where there is one, follows the status line.
            for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                if (status == null && lines[j].startsWith("> **Status:**")) {
                    status = lines[j].substring("> **Status:**".length()).trim();
                } else if (lines[j].startsWith("> **Disposition:**")) {
                    disposition =
                            lines[j].substring("> **Disposition:**".length()).trim();
                }
            }
            Matcher severity = SEVERITY.matcher(heading.group(2));
            found.add(new Finding(heading.group(1), status, disposition, severity.find() ? severity.group(1) : ""));
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

    @Test
    void theHeaderCountsMatchTheRegisterBeneathIt() throws IOException {
        // The file opens with its own totals, and they had drifted: the header said 173 findings
        // where the pattern this class uses finds 289, because a hand count saw only the "###"
        // headings and the register also uses "##". A summary that disagrees with the detail is read
        // instead of the detail, which is the whole reason the header is there.
        //
        // So the header states the numbers and this derives them. Update the sentence when the
        // register changes; the build will say when it needs updating.
        String text = Files.readString(repoRoot().resolve("docs/project/qa/FINDINGS.md"), StandardCharsets.UTF_8);
        Matcher header = Pattern.compile("\\*\\*(\\d+) findings carrying a\\s+status \u2014 (\\d+) FIXED, "
                        + "(\\d+) OPEN, (\\d+) BY DESIGN, (\\d+) SUPERSEDED\\.\\*\\*")
                .matcher(text);
        assertThat(header.find())
                .as("FINDINGS.md must state its own totals in the form this check reads, so that a "
                        + "drifting summary fails the build instead of misleading a reader")
                .isTrue();

        List<Finding> findings = findings();
        assertThat(Integer.parseInt(header.group(1)))
                .as("the header's finding count against the register")
                .isEqualTo(findings.size());

        List<String> words = List.of("FIXED", "OPEN", "BY DESIGN", "SUPERSEDED");
        for (String word : words) {
            long actual = findings.stream()
                    .filter(f -> f.status() != null)
                    .filter(f -> f.status().split("\u2014|--|\\.")[0].trim().equalsIgnoreCase(word))
                    .count();
            assertThat((long) Integer.parseInt(header.group(words.indexOf(word) + 2)))
                    .as("the header's %s count against the register", word)
                    .isEqualTo(actual);
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/design/adr"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("").toAbsolutePath() : path;
    }

    @Test
    void everyFindingIdentifiesExactlyOneFinding() throws IOException {
        // Three agents recorded findings into this file in one afternoon and two of them chose W8-2.
        // A register whose identifiers are not unique cannot answer "what is the status of W8-2",
        // which is the only question it exists to answer -- and the duplicate is invisible in a diff,
        // because each side is a well-formed entry that reads correctly on its own.
        Map<String, Long> byId = findings().stream()
                .collect(java.util.stream.Collectors.groupingBy(Finding::id, java.util.stream.Collectors.counting()));
        List<String> duplicated = byId.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(entry -> entry.getKey() + " (x" + entry.getValue() + ")")
                .sorted()
                .toList();

        assertThat(duplicated)
                .as("these identifiers name more than one finding, so neither can be looked up: %s", duplicated)
                .isEmpty();
    }

    @Test
    void everyHeadingThatLooksLikeAFindingIsCaptured() throws IOException {
        // The register's own blind spot, made visible. Twice now a new identifier shape has walked
        // past HEADING and taken its findings out of every other check in this class -- silently,
        // because an uncaptured finding looks exactly like one that was never written.
        //
        // A loose pattern cannot decide what a finding is, but it can say "this heading names
        // something-dash-something and the strict pattern ignored it", which is enough.
        String text = Files.readString(repoRoot().resolve("docs/project/qa/FINDINGS.md"), StandardCharsets.UTF_8);
        List<String> uncaptured = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (LOOKS_LIKE_A_FINDING.matcher(line).matches()
                    && !HEADING.matcher(line).matches()) {
                uncaptured.add(line.length() > 90 ? line.substring(0, 90) + "..." : line);
            }
        }

        assertThat(uncaptured)
                .as(
                        "these headings name a finding that HEADING does not match, so every check in this "
                                + "class silently skips them -- widen HEADING: %s",
                        uncaptured)
                .isEmpty();
    }

    @Test
    void everyOpenFindingSaysWhatItMeansForARelease() throws IOException {
        // The check that makes the triage a fact about the build rather than a document that rots.
        // An open finding with no disposition is one nobody has decided about, and 144 of those is
        // what made this register impossible to argue a release against.
        List<String> undisposed = findings().stream()
                .filter(finding -> finding.status() != null && finding.status().startsWith("OPEN"))
                .filter(finding -> finding.disposition() == null)
                .map(Finding::id)
                .toList();

        assertThat(undisposed)
                .as(
                        "every OPEN finding needs a `> **Disposition:**` line. Add one of %s, with the reason "
                                + "on the same line. A new finding is not triaged by being written down",
                        DISPOSITIONS)
                .isEmpty();
    }

    @Test
    void everyDispositionIsOneThisProjectRecognises() throws IOException {
        List<String> wrong = findings().stream()
                .filter(finding -> finding.disposition() != null)
                .filter(finding -> DISPOSITIONS.stream()
                        .noneMatch(known -> finding.disposition().startsWith(known)))
                .map(finding -> finding.id() + " -> " + finding.disposition())
                .toList();

        assertThat(wrong)
                .as(
                        "a disposition must begin with one of %s. An invented word is a finding that looks "
                                + "triaged and is not",
                        DISPOSITIONS)
                .isEmpty();
    }

    @Test
    void severityUsesOneVocabulary() throws IOException {
        List<String> wrong = findings().stream()
                .filter(finding -> !finding.severity().isEmpty())
                .filter(finding -> !SEVERITIES.contains(finding.severity()))
                .map(finding -> finding.id() + " -> " + finding.severity())
                .toList();

        assertThat(wrong)
                .as(
                        "severity must be one of %s. This register carried MED and MEDIUM, MED-HIGH and "
                                + "MEDIUM-HIGH, and so could not be sorted by the thing triage sorts by",
                        SEVERITIES)
                .isEmpty();
    }

    @Test
    void theHeaderTriageCountsMatchTheDispositionsBeneathIt() throws IOException {
        // The same ratchet the status counts already have. A triage summary that drifts from the
        // register is worse than none, because it is the part a release argument would quote.
        String text = Files.readString(repoRoot().resolve("docs/project/qa/FINDINGS.md"), StandardCharsets.UTF_8);
        Matcher header = Pattern.compile(
                        "\\*\\*(\\d+) (?:are|is)\\s+GA-BLOCKER, (\\d+) GA-REQUIRED, (\\d+) POST-GA and (\\d+) are not defects")
                .matcher(text);
        assertThat(header.find())
                .as("the header's triage sentence must still be there and still be machine-readable")
                .isTrue();

        List<Finding> open = findings().stream()
                .filter(finding -> finding.status() != null && finding.status().startsWith("OPEN"))
                .toList();
        // Accepts "is" as well as "are": the blocker count reached one, and a ratchet that
        // forced the summary into bad grammar to keep matching would be teaching the register
        // to read worse the closer it gets to zero.
        // Read from the header rather than hard-coded: the blocker count is the number this project
        // most wants to see fall, and a test that pinned it would need editing every time one is
        // fixed -- which is how a ratchet turns into a rubber stamp.
        assertThat(count(open, "GA-BLOCKER")).isEqualTo(Integer.parseInt(header.group(1)));
        assertThat(count(open, "GA-REQUIRED")).isEqualTo(Integer.parseInt(header.group(2)));
        assertThat(count(open, "POST-GA")).isEqualTo(Integer.parseInt(header.group(3)));
        assertThat(count(open, "NOTE")).isEqualTo(Integer.parseInt(header.group(4)));
    }

    private static long count(List<Finding> open, String disposition) {
        return open.stream()
                .filter(finding ->
                        finding.disposition() != null && finding.disposition().startsWith(disposition))
                .count();
    }
}

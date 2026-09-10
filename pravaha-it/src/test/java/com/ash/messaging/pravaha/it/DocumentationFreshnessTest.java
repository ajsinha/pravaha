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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documentation is checked against reality, not against good intentions.
 *
 * <p>Written because the documentation had rotted within two waves: the README described a console
 * built on a stack that had since been replaced, claimed a wave that had already finished, and
 * listed a module set that was four modules out of date. None of that was noticed by anyone
 * reading it, because nobody reads a README they wrote last week.
 *
 * <p>A periodic manual pass does not work on a project changing this fast. These checks are
 * deliberately mechanical and deliberately narrow -- they verify the things that go stale silently
 * and can be verified without judgement:
 *
 * <ul>
 *   <li>every Maven module is described somewhere in the documentation;
 *   <li>no document names a module that no longer exists;
 *   <li>every file path a document points at is really there;
 *   <li>the README's stated wave matches the newest gate that has been recorded.
 * </ul>
 *
 * <p>What it deliberately does not check is prose accuracy, which no test can. It narrows the
 * surface a human has to re-read, rather than replacing them.
 */
class DocumentationFreshnessTest {

    private static final List<String> DOCS = List.of(
            "README.md",
            "docs/system_design.md",
            "docs/implementation_plan.md",
            "docs/QUICKSTART.md",
            "docs/ARCHITECTURE.md",
            "docs/HANDOVER.md");

    @Test
    void everyMavenModuleIsDescribedInTheDocumentation() throws IOException {
        // A module nobody documented is a module nobody can find, and by the time anyone notices,
        // the person who added it has forgotten why.
        Set<String> modules = mavenModules();
        String corpus = corpus();

        List<String> undocumented =
                modules.stream().filter(module -> !corpus.contains(module)).toList();

        assertThat(undocumented)
                .as("these modules exist in pom.xml but appear in no document: %s", undocumented)
                .isEmpty();
    }

    @Test
    void noDocumentNamesAModuleThatNoLongerExists() throws IOException {
        // The rot that matters most: a document confidently describing something that was renamed
        // or deleted. A reader trusts it and loses an afternoon.
        Set<String> modules = mavenModules();
        List<String> phantom = new ArrayList<>();

        Pattern reference = Pattern.compile("`(pravaha-[a-z0-9-]+)`");
        for (String doc : DOCS) {
            Path path = repoRoot().resolve(doc);
            if (!Files.exists(path)) {
                continue;
            }
            Matcher matcher = reference.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String named = matcher.group(1);
                boolean exists = modules.stream().anyMatch(m -> m.endsWith(named)) || KNOWN_FUTURE.contains(named);
                if (!exists) {
                    phantom.add(doc + " -> " + named);
                }
            }
        }

        assertThat(phantom).as("""
                        These documents name modules that do not exist and are not listed as planned.

                        Either the module was renamed or removed and the document was not updated, or
                        it is genuinely future work and belongs in KNOWN_FUTURE with the others.""").isEmpty();
    }

    @Test
    void everyRepositoryPathADocumentPointsAtExists() throws IOException {
        List<String> broken = new ArrayList<>();
        // Markdown links to files inside the repository, ignoring anchors and external URLs.
        Pattern link = Pattern.compile("\\]\\((?!https?://)([A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)(?:#[^)]*)?\\)");

        for (String doc : DOCS) {
            Path path = repoRoot().resolve(doc);
            if (!Files.exists(path)) {
                continue;
            }
            Matcher matcher = link.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (matcher.find()) {
                Path target = path.getParent().resolve(matcher.group(1)).normalize();
                if (!Files.exists(target)) {
                    broken.add(doc + " -> " + matcher.group(1));
                }
            }
        }
        assertThat(broken).as("documents linking to files that do not exist").isEmpty();
    }

    @Test
    void theReadmeStatedWaveMatchesTheNewestRecordedGate() throws IOException {
        // The single most reliable form of rot: a status line written once and never revisited.
        Path gates = repoRoot().resolve("docs/gates");
        if (!Files.exists(gates)) {
            return;
        }
        int newestGate;
        try (Stream<Path> waves = Files.list(gates)) {
            newestGate = waves.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("wave-"))
                    .mapToInt(n -> Integer.parseInt(n.substring("wave-".length())))
                    .max()
                    .orElse(0);
        }
        if (newestGate == 0) {
            return;
        }

        String readme = Files.readString(repoRoot().resolve("README.md"), StandardCharsets.UTF_8);
        Matcher stated = Pattern.compile("Wave (\\d+)").matcher(readme);
        assertThat(stated.find())
                .as("the README must state which wave the project is in")
                .isTrue();

        int claimed = Integer.parseInt(stated.group(1));
        assertThat(claimed).as("""
                        The README says Wave %d but the newest recorded gate is Wave %d.

                        Either a wave finished and the README was not updated, or a gate pack was
                        written for a wave that has not started.""", claimed, newestGate).isGreaterThanOrEqualTo(newestGate);
    }

    @Test
    void theQuickstartExistsAndNamesRunnableCommands() throws IOException {
        // A quickstart that cannot be followed is worse than none: it is the first thing an
        // evaluator tries, and for a closed-source product they cannot fall back to reading code.
        Path quickstart = repoRoot().resolve("docs/QUICKSTART.md");
        assertThat(quickstart).exists();
        String text = Files.readString(quickstart, StandardCharsets.UTF_8);
        assertThat(text).contains("./mvnw").contains("pravaha");
    }

    @Test
    void examplesReferencedByTheQuickstartArePresent() throws IOException {
        Path examples = repoRoot().resolve("examples");
        assertThat(examples)
                .as("the quickstart refers to examples/, which must exist")
                .exists();
        try (Stream<Path> files = Files.list(examples)) {
            assertThat(files.count()).as("examples/ must not be empty").isPositive();
        }
    }

    @Test
    void theCheckItselfHasSomethingToCheck() throws IOException {
        // Guards against a path bug making every assertion above vacuous.
        assertThat(mavenModules()).hasSizeGreaterThan(10);
        assertThat(corpus().length()).isGreaterThan(50_000);
    }

    /** Modules that documents may name before they are built. Keep this list short and honest. */
    private static final Set<String> KNOWN_FUTURE = Set.of(
            "pravaha-state",
            "pravaha-catalog",
            "pravaha-serving",
            "pravaha-backfill",
            "pravaha-adaptive",
            "pravaha-cluster",
            "pravaha-security",
            "pravaha-gateway-grpc",
            "pravaha-gateway-avatica",
            "pravaha-spring-boot-starter",
            "pravaha-debug",
            "pravaha-ui",
            "pravaha-dist",
            "pravaha-bom",
            "pravaha-plugin-aerospike",
            "pravaha-plugin-cassandra",
            "pravaha-plugin-kafka",
            "pravaha-plugin-redis",
            "pravaha-plugin-jdbc",
            "pravaha-plugin-http",
            "pravaha-sdk-go",
            "pravaha-client-scala",
            "pravaha-dsl-scala");

    private static Set<String> mavenModules() throws IOException {
        String pom = Files.readString(repoRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<module>([^<]+)</module>").matcher(pom);
        Set<String> modules = new LinkedHashSet<>();
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }
        return modules;
    }

    private static String corpus() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String doc : DOCS) {
            Path path = repoRoot().resolve(doc);
            if (Files.exists(path)) {
                all.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
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

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
            "docs/HANDOVER.md",
            "docs/SQL_SUPPORT.md",
            "docs/README.md",
            "docs/CONCEPTS.md",
            "docs/USER_GUIDE.md",
            "docs/OPERATIONS.md",
            "docs/SECURITY.md",
            "docs/TROUBLESHOOTING.md");

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
    void everyDecisionADocumentCitesHasAnAdrFile() throws IOException {
        // Found by looking, not by a test: ADR-024, ADR-025 and ADR-026 were cited by name in three
        // documents -- including the design's own decision table -- while no such file existed. The
        // path check above did not catch it because a bare "ADR-024" is not a link, and a decision
        // record that is cited but unwritten is the most expensive kind of rot: the reasoning is
        // gone, and the citation makes it look as though it was captured.
        Set<String> recorded = new LinkedHashSet<>();
        Path adrDir = repoRoot().resolve("docs/adr");
        try (Stream<Path> files = Files.list(adrDir)) {
            files.map(f -> f.getFileName().toString())
                    .filter(n -> n.matches("\\d{3}-.*\\.md"))
                    .forEach(n -> recorded.add(n.substring(0, 3)));
        }
        assertThat(recorded)
                .as("the ADR directory must contain numbered records")
                .hasSizeGreaterThan(10);

        List<String> uncaptured = new ArrayList<>();
        Pattern citation = Pattern.compile("ADR-(\\d{3})");
        for (String doc : DOCS) {
            Path path = repoRoot().resolve(doc);
            if (!Files.exists(path)) {
                continue;
            }
            Matcher matcher = citation.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (!recorded.contains(matcher.group(1))) {
                    uncaptured.add(doc + " -> ADR-" + matcher.group(1));
                }
            }
        }
        assertThat(uncaptured)
                .as("these documents cite decisions that have no record in docs/adr: %s", uncaptured)
                .isEmpty();
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
    void theReadmeStatusBadgeTheStatusLineAndTheRoadmapAgree() throws IOException {
        // Wave 8 shipped and the README said three different things about it: a badge reading
        // "wave 7 of 10", a status line reading "Wave 7 of 10", and a roadmap row marking Wave 8
        // "not started" -- while ADR-035, the implementation plan and HANDOVER all described it as
        // the wave being worked on. The check above only compares the status line with the newest
        // gate directory, and a gate pack lags the work by design, so it passed over all of it.
        //
        // Three statements of one fact, in one file, is rot waiting to happen. This makes them
        // disagree loudly instead.
        String readme = Files.readString(repoRoot().resolve("README.md"), StandardCharsets.UTF_8);

        Matcher badge =
                Pattern.compile("badge/status-wave%20(\\d+)%20of%20(\\d+)").matcher(readme);
        assertThat(badge.find())
                .as("the README must carry a status badge naming the wave")
                .isTrue();
        Matcher line = Pattern.compile("Project status: Wave (\\d+) of (\\d+)").matcher(readme);
        assertThat(line.find())
                .as("the README must carry a 'Project status: Wave N of M' line")
                .isTrue();

        int badged = Integer.parseInt(badge.group(1));
        int claimed = Integer.parseInt(line.group(1));
        assertThat(badged)
                .as("the status badge says wave %d and the status line says wave %d", badged, claimed)
                .isEqualTo(claimed);
        assertThat(badge.group(2))
                .as("the badge and the status line disagree on how many waves there are")
                .isEqualTo(line.group(2));

        // Every roadmap row, against that one number. A row is "| 8 | 39-45 | milestone | state |",
        // and its wave column may be a range ("9-10"), in which case the first number rules.
        List<String> disagreements = new ArrayList<>();
        Matcher row = Pattern.compile("^\\| (\\d+)(?:[^|]*?) \\| [^|]*\\|[^|]*\\|([^|]*)\\|$", Pattern.MULTILINE)
                .matcher(readme);
        int rows = 0;
        while (row.find()) {
            int wave = Integer.parseInt(row.group(1));
            String state = row.group(2);
            rows++;
            boolean built = state.contains("\u2705");
            boolean notStarted = state.contains("not started");
            if (wave <= claimed && !built) {
                disagreements.add("wave " + wave + " is at or below the claimed wave " + claimed
                        + " and its roadmap row does not say it is built: " + state.strip());
            }
            if (wave > claimed && !notStarted) {
                disagreements.add("wave " + wave + " is above the claimed wave " + claimed
                        + " and its roadmap row does not say it is not started: " + state.strip());
            }
            if (built && notStarted) {
                disagreements.add("wave " + wave + " is marked built and not started at once");
            }
        }

        assertThat(rows)
                .as("the roadmap table must have rows; the row pattern found none")
                .isPositive();
        assertThat(disagreements).as("""
                        The README's roadmap contradicts its own status line.

                        A wave that has shipped must be marked built, and one that has not must be
                        marked not started -- the roadmap is the only place a reader looks for
                        either.""").isEmpty();
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
    void everyJavaTypeTheReadmeShowsExists() throws IOException {
        // The README's headline example read:
        //
        //     pravaha.view("user_volume").consistency(Consistency.CONSISTENT).get("user_42")
        //
        // There is no view(...) on PravahaEngine and no fluent consistency(...) anywhere in the
        // repository. It was the first code an evaluator would try, and it had never been true.
        // The checks above all passed over it, because they read module names, ADR references and
        // paths -- none of which a fabricated API touches.
        //
        // A block that deliberately shows an API which does not exist yet marks itself with an
        // <!-- illustrative --> comment on the line before it, which is a thing a reader sees too.
        String readme = Files.readString(repoRoot().resolve("README.md"), StandardCharsets.UTF_8);
        Set<String> sources = sourceTypeNames();

        String allSource = allSourceText();
        List<String> fabricated = new ArrayList<>();
        for (String block : javaBlocks(readme)) {
            for (String name : pravahaTypesIn(block)) {
                if (!sources.contains(name)) {
                    fabricated.add("type " + name);
                }
            }
            // Methods as well as types, because the fabrication was a method: Consistency is a
            // real class, and `view(...).consistency(...)` was invented around it. A type-level
            // check passed this snippet, which is how I found out that checking types was not
            // enough -- the seeded README went green.
            for (String method : methodsCalledIn(block)) {
                if (!allSource.contains(method + "(")) {
                    fabricated.add("method " + method + "(...)");
                }
            }
        }

        assertThat(fabricated)
                .as("the README shows Java types that do not exist in this repository. Either build "
                        + "them, use the API that does exist, or mark the block <!-- illustrative --> "
                        + "so a reader knows it describes something unbuilt")
                .isEmpty();
    }

    /** The fenced {@code java} blocks of a document, minus any marked illustrative. */
    private static List<String> javaBlocks(String markdown) {
        List<String> blocks = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        boolean illustrative = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("<!-- illustrative")) {
                illustrative = true;
                continue;
            }
            if (!lines[i].strip().startsWith("```java")) {
                if (!lines[i].isBlank()) {
                    illustrative = false;
                }
                continue;
            }
            StringBuilder block = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].strip().startsWith("```")) {
                block.append(lines[j]).append('\n');
                j++;
            }
            if (!illustrative) {
                blocks.add(block.toString());
            }
            illustrative = false;
            i = j;
        }
        return blocks;
    }

    /**
     * Type-shaped names in a snippet that look like this project's rather than the JDK's.
     *
     * <p>Deliberately narrow. A snippet names domain types a reader is meant to substitute -- an
     * {@code Order}, a {@code FraudService} -- and failing on those would make the check noise.
     * What it catches is a name this repository is claimed to provide.
     */
    private static Set<String> pravahaTypesIn(String block) {
        Set<String> named = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher annotations =
                java.util.regex.Pattern.compile("@([A-Z][A-Za-z0-9]+)").matcher(block);
        while (annotations.find()) {
            named.add(annotations.group(1));
        }
        // A capitalised name used statically -- Consistency.CONSISTENT, SubscriptionOptions.DEFAULT
        // -- or constructed. Both are claims that the type exists here.
        java.util.regex.Matcher statics = java.util.regex.Pattern.compile(
                        "\\b([A-Z][A-Za-z0-9]+)\\.[A-Z_]{2,}\\b|new\\s+([A-Z][A-Za-z0-9]+)\\s*\\(")
                .matcher(block);
        while (statics.find()) {
            named.add(statics.group(1) != null ? statics.group(1) : statics.group(2));
        }
        named.removeAll(JDK_TYPES);
        return named;
    }

    /**
     * Method names a snippet calls, minus the ones every Java program calls.
     *
     * <p>Matched on the name alone rather than on a signature. A false negative -- a method that
     * exists somewhere else with the same name -- costs nothing; a false positive would make this
     * check noise and get it deleted.
     */
    private static Set<String> methodsCalledIn(String block) {
        Set<String> called = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher calls =
                java.util.regex.Pattern.compile("\\.([a-z][A-Za-z0-9]*)\\s*\\(").matcher(block);
        while (calls.find()) {
            called.add(calls.group(1));
        }
        called.removeAll(COMMON_METHODS);
        return called;
    }

    /** Methods the JDK provides, which a snippet naming them is not claiming this repository has. */
    private static final Set<String> COMMON_METHODS = Set.of(
            "get",
            "map",
            "orElse",
            "orElseThrow",
            "toString",
            "equals",
            "hashCode",
            "of",
            "size",
            "add",
            "stream",
            "filter",
            "forEach",
            "collect",
            "isEmpty",
            "close",
            "println",
            "format",
            "valueOf",
            "length",
            "contains",
            "iterator",
            "next",
            "run",
            "start",
            "join",
            "accept",
            "apply",
            "test",
            "compare",
            "build",
            "builder");

    /** Every character of every Java source file, for a name-level existence check. */
    private static String allSourceText() throws IOException {
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.walk(repoRoot())) {
            for (Path path : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .filter(f -> !f.startsWith(nestedCheckouts()))
                    // This file quotes the fabricated call it exists to catch, so scanning it
                    // would let that call prove its own existence. The check defeated itself
                    // exactly once, in the seed that was meant to confirm it worked.
                    .filter(f -> !f.getFileName().toString().equals("DocumentationFreshnessTest.java"))
                    .toList()) {
                text.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    /** Names the JDK and common libraries provide, which this repository is not claiming to. */
    private static final Set<String> JDK_TYPES = Set.of(
            "String",
            "Integer",
            "Long",
            "Double",
            "Boolean",
            "BigDecimal",
            "List",
            "Map",
            "Set",
            "Optional",
            "Duration",
            "Instant",
            "Objects",
            "Math",
            "System",
            "Arrays",
            "Collections",
            "Thread",
            "Files",
            "Path",
            "Stream",
            "Service",
            "Override",
            "Test",
            "Autowired",
            "Bean",
            "Component",
            "Configuration",
            "RestController",
            "SpringBootApplication");

    /** Every type this repository defines, by simple name. */
    private static Set<String> sourceTypeNames() throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot())) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Nested agent worktrees under .claude/ are copies of this repository. Counting
                    // them would let a type deleted here go on existing in a stale copy.
                    .filter(path -> !path.startsWith(nestedCheckouts()))
                    .map(path -> path.getFileName().toString().replace(".java", ""))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void theReadmesModuleListMatchesTheBuild() throws IOException {
        // The list said eleven modules; the build had thirty-one. A reader sizing the project, or
        // looking for where something lives, was being told about a third of it.
        String readme = Files.readString(repoRoot().resolve("README.md"), StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String module : mavenModules()) {
            String simple = module.substring(module.lastIndexOf('/') + 1);
            if (!readme.contains(simple)) {
                missing.add(simple);
            }
        }
        assertThat(missing)
                .as("every module in pom.xml must be named in the README's module list")
                .isEmpty();
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

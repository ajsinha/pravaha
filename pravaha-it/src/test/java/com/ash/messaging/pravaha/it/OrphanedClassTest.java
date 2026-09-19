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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classes that production builds, tests exercise, and nothing runs.
 *
 * <p>This codebase has produced the same defect four times: a component is written, given a good
 * test, described in the documentation, and never wired into a path that executes. {@code
 * FileCheckpointStore.prune} was called only by its own test. {@code PeriodicCheckpointer} was never
 * constructed. {@code RegisteredQuery.accept} -- the entry point for every row into a registered
 * continuous query -- is called by four test classes and nothing else. And {@code L0StateMap}, the
 * off-heap state map the architecture was written around, was referenced by no production file at
 * all -- and turned out to be the wrong shape for the state it was written for, so Wave 8 deleted it
 * rather than wiring it (W8-12).
 *
 * <p>Each was found by accident, months apart, by someone reading for another reason. The test suite
 * could not find them because a test that constructs the class is exactly what makes it look used.
 *
 * <p>So this asserts the property directly: a public type in {@code src/main} that is named by no
 * other {@code src/main} file, and is named by a test, is an orphan. The allow-list below is the
 * interesting part -- every entry is a claim that a type is reachable by a mechanism this scan
 * cannot see, and each one says which mechanism.
 */
class OrphanedClassTest {

    /**
     * Types reachable by something other than a direct reference.
     *
     * <p>Adding an entry is a deliberate act. If the reason is not one of the ones listed here, the
     * honest options are to wire it, delete it, or move it to a module that is documented as a
     * reference implementation -- not to widen this list.
     */
    private static final Map<String, String> REACHABLE_OTHERWISE = Map.ofEntries(
            // ServiceLoader and the plugin registry: named in configuration, not in code.
            Map.entry("AerospikeSourcePlugin", "ServiceLoader / plugin registry"),
            Map.entry("AerospikeSinkPlugin", "ServiceLoader / plugin registry"),
            Map.entry("AerospikeLookupPlugin", "ServiceLoader / plugin registry"),
            Map.entry("JdbcLookupPlugin", "ServiceLoader / plugin registry"),
            Map.entry("DeltaSourcePlugin", "ServiceLoader / plugin registry"),
            // Declared in plugins/pravaha-cluster-zookeeper/src/main/resources/META-INF/services/
            // com.ash.messaging.pravaha.cluster.CoordinatorProvider. It became visible to this scan
            // only when the scan stopped counting the QA agents' worktrees: each running agent held
            // a copy of this file, and a copy of a class counts as a reference to it, so every
            // orphan in the repository was masked while an agent was running.
            Map.entry("ZooKeeperProvider", "ServiceLoader / CoordinatorProvider"),
            // Named in pravaha-spring-boot-starter/src/main/resources/META-INF/spring/
            // org.springframework.boot.autoconfigure.AutoConfiguration.imports, which is how Spring
            // Boot finds an auto-configuration; StarterApplicationTest proves Boot loads it from there.
            Map.entry("PravahaAutoConfiguration", "Spring Boot AutoConfiguration.imports"),
            // Spring instantiates these from annotations.
            // The process entry point: @SpringBootApplication with a main method, started by the
            // launcher rather than referenced from any other class. It became visible to this scan
            // only when AuditSinkSharingTest became the first test to construct one -- which is the
            // check working, not a false positive: until then nothing referenced it from either side.
            Map.entry("PravahaServerApplication", "Spring @SpringBootApplication, main() entry point"),
            Map.entry("QueryController", "Spring @RestController"),
            Map.entry("StreamController", "Spring @RestController"),
            Map.entry("SinkController", "Spring @RestController"),
            Map.entry("ViewController", "Spring @RestController"),
            Map.entry("AuditController", "Spring @RestController"),
            Map.entry("PluginController", "Spring @RestController"),
            Map.entry("PermissionsController", "Spring @RestController"),
            Map.entry("PravahaMetrics", "Spring @Component"),
            Map.entry("EngineHealthIndicator", "Spring @Component, contributed to /actuator/health"),
            Map.entry("ApiExceptionHandler", "Spring @RestControllerAdvice"),
            // JMH generates the harness that calls these.
            Map.entry("FalseSharingBenchmark", "JMH harness"),
            Map.entry("MemoryAccessBenchmark", "JMH harness"),
            // pravaha-testkit exists to be used by tests. That is the whole module.
            Map.entry("CapturingRowWriter", "testkit, by design"),
            Map.entry("SourcePluginTck", "testkit, by design"),
            Map.entry("VirtualClock", "testkit, by design"));

    /**
     * The debt that existed when this check was written.
     *
     * <p>Twenty-one types when this was written, each built, tested, documented and unreachable.
     * Three are load bearing in the documentation and not in the product: {@code WatermarkTracker}
     * and {@code TimerWheel} are how event time is described as working, and the four {@code
     * pravaha-algebra} types are the DBSP correctness oracle ADR-013 rests on.
     *
     * <p>Wave 8 took three off it, one by each of the three available verdicts.
     * {@code FileDeadLetterQueue} is wired (W8-11: {@code pravaha run --dlq}). {@code L0StateMap} is
     * deleted (W8-12: fixed-width keys, and the state it was written for has variable-width ones).
     * {@code ChangelogAnalysis} stayed longest, as the one entry that was deliberate rather than
     * owed: it refuses a plan a sink cannot take, and nothing bound a query to a sink. Something does
     * now (ADR-043), the registry calls it before opening the sink, and it is off this list.
     * {@code ErrcSqlTest} pins that ordering.
     *
     * <p>This list is a record, not a permission. Every entry needs a verdict -- wire, delete, or
     * move to a module documented as a reference implementation -- and the assertion below stops it
     * growing in the meantime.
     */
    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            "SplicedReader",
            "ShadowDeployment",
            "Rebalancer",
            "FileCheckpointStore",
            "Lift",
            "Frontier",
            "IncrementalJoin",
            "Differentiate",
            "StaticTokenVerifier",
            "LaneMultiplexer",
            "WatermarkTracker",
            "TimerWheel",
            "DeduplicatingSink",
            "DeadLetterRate",
            "PeriodicCheckpointer",
            "BatchingController",
            "PluginClassLoader",
            "StageUpgradeService");

    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "\\bpublic\\s+(?:final\\s+|abstract\\s+|sealed\\s+)?(?:class|record|interface|enum)\\s+(\\w+)");

    @Test
    void noProductionTypeIsReachableOnlyFromTests() throws IOException {
        List<Path> main = sources("src/main/java");
        List<Path> tests = sources("src/test/java");

        Map<Path, String> mainText = read(main);
        String testText = String.join("\n", read(tests).values());

        List<String> orphans = new ArrayList<>();
        for (Map.Entry<Path, String> file : mainText.entrySet()) {
            String name = file.getKey().getFileName().toString().replace(".java", "");
            Matcher declares = PUBLIC_TYPE.matcher(file.getValue());
            boolean declaresItself = false;
            while (declares.find()) {
                if (declares.group(1).equals(name)) {
                    declaresItself = true;
                    break;
                }
            }
            if (!declaresItself || REACHABLE_OTHERWISE.containsKey(name)) {
                continue;
            }
            Pattern reference = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            boolean usedInMain = mainText.entrySet().stream()
                    .anyMatch(other -> !other.getKey().equals(file.getKey())
                            && reference.matcher(other.getValue()).find());
            boolean usedInTests = reference.matcher(testText).find();
            if (!usedInMain && usedInTests) {
                orphans.add(name + "  (" + file.getKey() + ")");
            }
        }

        List<String> fresh = orphans.stream()
                .filter(entry -> !KNOWN.contains(entry.substring(0, entry.indexOf(' '))))
                .toList();

        assertThat(fresh).as("""
                        These types are built and tested and nothing in production uses them.

                        That is the defect this project has produced four times: the component is
                        real, the test is real, the documentation is confident, and the running code
                        went a different way. A green build is not evidence to the contrary -- the
                        test that constructs the class is exactly what makes it look used.

                        Choose one: wire it into a path that executes, delete it, or -- if it really
                        is reachable by a mechanism this scan cannot see, such as ServiceLoader or a
                        framework annotation -- add it to REACHABLE_OTHERWISE naming the mechanism.

                        Adding it to KNOWN is not a fourth option. That list is the debt that existed
                        when this check was written, and it is meant to shrink.

                        New: %s""", fresh).isEmpty();

        // And the baseline may not grow by stealth: a type that gets wired up should leave KNOWN,
        // so the list stays an accurate account of the debt rather than a place things go to be
        // forgotten.
        assertThat(orphans.size())
                .as(
                        "KNOWN lists %d types; the scan found %d. If you wired one up, remove it from "
                                + "KNOWN so the list keeps meaning something.",
                        KNOWN.size(), orphans.size())
                .isLessThanOrEqualTo(KNOWN.size());
    }

    private static List<Path> sources(String under) throws IOException {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains(under))
                    .filter(p -> !p.toString().contains("/target/"))
                    // Agent worktrees under .claude/ are copies of this repository; counting
                    // them would let a class deleted here go on looking referenced.
                    .filter(p -> !p.startsWith(nestedCheckouts()))
                    .toList();
        }
    }

    private static Map<Path, String> read(List<Path> paths) throws IOException {
        Map<Path, String> out = new java.util.LinkedHashMap<>();
        for (Path path : paths) {
            out.put(path, Files.readString(path, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("pravaha-api"))) {
            here = here.getParent();
        }
        if (here == null) {
            throw new IllegalStateException("cannot find the repository root");
        }
        return here;
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

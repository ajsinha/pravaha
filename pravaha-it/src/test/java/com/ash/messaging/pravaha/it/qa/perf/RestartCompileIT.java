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
package com.ash.messaging.pravaha.it.qa.perf;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CGRESTART-1: what a restart with many distinct queries costs, and how much of it is Janino.
 *
 * <p>Registers N queries whose filter literals differ, so each is its own filter/projection chain
 * and the code generator compiles each one (a chain is cached by its structure, so N copies of one
 * query would compile once and measure nothing). Waits until every query has a checkpoint, stops the
 * node, and starts a new one on the same journal and checkpoint directory, twice: once with the code
 * generator installed, as a node runs, and once with {@code pravaha.codegen.enabled=false}.
 *
 * <p>There is no interpreted-then-upgraded phase to wait for: a lane's pipeline offers its chains
 * to the generator when it is built, and recovery builds it, so when {@code PravahaNode.start()}
 * returns every recovered query is RUNNING <em>on generated code</em> -- the test asserts both, per
 * query. Time to all RUNNING is therefore time to full speed, and the difference between the two
 * restarts is what generating and compiling costs.
 *
 * <p>The restarts run in a JVM that has already registered the same queries once, so Janino and the
 * planner are warm; a cold process pays class loading and JIT on top, which the first restart of
 * a fresh process would show. Not part of the default build (named {@code *IT}) and skipped under
 * the coverage agent. Run it on purpose:
 *
 * <pre>
 * ./mvnw -o -pl pravaha-it test -Dtest=RestartCompileIT -Djacoco.skip=true \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Timeout(1800)
final class RestartCompileIT {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @BeforeAll
    static void refuseACoverageAgent() {
        // It also instruments every class the generator loads (PERF-1's shared check).
        org.junit.jupiter.api.Assumptions.assumeFalse(
                com.ash.messaging.pravaha.common.observe.CoverageAgent.attached(),
                com.ash.messaging.pravaha.common.observe.CoverageAgent.DECLINED);
    }

    @ParameterizedTest(name = "{0} distinct queries")
    @ValueSource(ints = {100, 500, 1000})
    void aRestartRecoversEveryQueryOnGeneratedCode(int queries, @TempDir Path dir) throws Exception {
        Path journal = dir.resolve("registry.journal");
        Path checkpoints = dir.resolve("checkpoints");
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal.toString());
        persistence.getCheckpoint().setDirectory(checkpoints.toString());
        persistence.getCheckpoint().setInterval(Duration.ofSeconds(1));
        persistence.getCheckpoint().setKeep(1);

        List<String> names = new ArrayList<>(queries);
        String previous = System.getProperty(PravahaNode.CODEGEN_PROPERTY);
        try {
            System.clearProperty(PravahaNode.CODEGEN_PROPERTY);
            PravahaNode first = node(catalog, security, persistence);
            long registerMillis;
            first.start();
            try {
                QueryRegistry registry = first.registry().orElseThrow();
                long start = System.nanoTime();
                for (int i = 0; i < queries; i++) {
                    String name = "q" + i;
                    registry.register(
                            name,
                            "SELECT user_id, amount, ts FROM txn WHERE amount > " + i + " AND amount < "
                                    + (1_000_000 + i),
                            List.of(0),
                            DANA);
                    names.add(name);
                }
                registerMillis = millisSince(start);
                long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
                while (!everyQueryHasACheckpoint(checkpoints, names) && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                }
                assertThat(everyQueryHasACheckpoint(checkpoints, names))
                        .as("every query checkpointed before the restart")
                        .isTrue();
            } finally {
                first.stop();
            }

            Restart generated = restart(catalog, security, persistence, names, true);
            System.setProperty(PravahaNode.CODEGEN_PROPERTY, "false");
            Restart interpreted = restart(catalog, security, persistence, names, false);

            long compileMillis = Math.max(0, generated.startMillis - interpreted.startMillis);
            System.out.printf(
                    "CGRESTART-1 %d distinct queries on %d cores, load %.1f:%n"
                            + "  first registration:            %6d ms%n"
                            + "  restart, generated code:       %6d ms to all RUNNING, all on generated code%n"
                            + "  restart, codegen disabled:     %6d ms to all RUNNING, interpreted%n"
                            + "  generation + Janino:           %6d ms (%.0f %% of the restart, %.2f ms per query)%n",
                    queries,
                    Runtime.getRuntime().availableProcessors(),
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                            .getSystemLoadAverage(),
                    registerMillis,
                    generated.startMillis,
                    interpreted.startMillis,
                    compileMillis,
                    100.0 * compileMillis / Math.max(1, generated.startMillis),
                    compileMillis / (double) queries);
        } finally {
            if (previous == null) {
                System.clearProperty(PravahaNode.CODEGEN_PROPERTY);
            } else {
                System.setProperty(PravahaNode.CODEGEN_PROPERTY, previous);
            }
        }
    }

    private record Restart(long startMillis) {}

    private static Restart restart(
            StreamCatalog catalog,
            SecurityProperties security,
            PersistenceProperties persistence,
            List<String> names,
            boolean expectGenerated) {
        PravahaNode node = node(catalog, security, persistence);
        long start = System.nanoTime();
        node.start();
        long startMillis = millisSince(start);
        try {
            QueryRegistry registry = node.registry().orElseThrow();
            assertThat(registry.names()).hasSize(names.size());
            for (String name : names) {
                RegisteredQuery query = registry.find(name).orElseThrow();
                assertThat(query.state()).as("%s after the restart", name).isEqualTo(QueryState.RUNNING);
                assertThat(query.executionPaths())
                        .as("%s's chain, with the generator %s", name, expectGenerated ? "installed" : "removed")
                        .anyMatch(path -> path.startsWith(expectGenerated ? "generated: " : "interpreted: "));
            }
        } finally {
            node.stop();
        }
        return new Restart(startMillis);
    }

    private static PravahaNode node(
            StreamCatalog catalog, SecurityProperties security, PersistenceProperties persistence) {
        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new SourceBindingProperties())
                .withDeclaredStreams(new StreamDeclarationProperties())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("cgrestart-node")
                .build();
    }

    private static boolean everyQueryHasACheckpoint(Path checkpoints, List<String> names) {
        for (String name : names) {
            if (new FileCheckpointStore(checkpoints.resolve(name))
                    .availableIds()
                    .isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}

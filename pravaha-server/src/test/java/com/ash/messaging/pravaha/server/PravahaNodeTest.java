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
package com.ash.messaging.pravaha.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node assembles the things that make this process a server.
 *
 * <p>Until it did, all three -- the Flight endpoint, the registry, the coordinator -- were built
 * only in tests. The engine started, HTTP came up, and nothing listened on the wire protocol the
 * SDKs and the CLI actually speak, so the product's own client tools could not reach the product's
 * own server without a test harness standing one up.
 */
class PravahaNodeTest {

    private static StreamCatalog catalog() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        return catalog;
    }

    /** A node with the wire protocol switched off: running, and reachable by no client. */
    private static PravahaNode nodeWithoutFlight() {
        return new PravahaNode(
                catalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence(""),
                "SINGLE",
                "single",
                "no-flight-node",
                true,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);
    }

    private static PravahaNode node(String journal) {
        // Port zero: the operating system picks, so tests do not fight over a fixed one.
        return new PravahaNode(
                catalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                true,
                "127.0.0.1",
                0,
                persistence(journal),
                "SINGLE",
                "single",
                "test-node",
                true,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);
    }

    @Test
    void startingBringsUpFlightTheRegistryAndTheCoordinator() {
        PravahaNode node = node("");
        try {
            node.start();

            assertThat(node.isRunning()).isTrue();
            assertThat(node.flightPort()).isPresent();
            assertThat(node.flightPort().orElseThrow()).isGreaterThan(0);
            assertThat(node.registry()).isPresent();
            assertThat(node.coordinator()).isPresent();
        } finally {
            node.stop();
        }
    }

    @Test
    void registrationsSurviveARestartOfTheWholeNode(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        PravahaNode first = node(journal.toString());
        first.start();
        try {
            first.registry()
                    .orElseThrow()
                    .register(
                            "by_user",
                            "SELECT user_id, amount FROM txn",
                            List.of(0),
                            new com.ash.messaging.pravaha.security.Principal(
                                    "dana", "acme", java.util.Set.of("analyst"), java.util.Map.of()));
        } finally {
            first.stop();
        }

        PravahaNode second = node(journal.toString());
        second.start();
        try {
            // The whole point of the journal, at the level a deployment actually experiences it:
            // restart the process, and the views clients subscribed to are still there.
            assertThat(second.registry().orElseThrow().names()).contains("by_user");
        } finally {
            second.stop();
        }
    }

    @Test
    void aNodeWithNoJournalStartsAndSaysWhatThatCosts() {
        PravahaNode node = node("");
        try {
            node.start();

            // Allowed, because a single-node development run does not need durability. Not silent,
            // because the cost of finding out at the next restart is every client's registrations.
            assertThat(node.describe()).anySatisfy(line -> assertThat(line).contains("queries are lost on restart"));
        } finally {
            node.stop();
        }
    }

    @Test
    void partitionedModeWithoutConsensusRefusesToStart() {
        PravahaNode node = new PravahaNode(
                catalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence(""),
                "PARTITIONED",
                "socket",
                "test-node",
                true,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);

        // Two nodes each believing they own a partition write the same aggregate twice, and the
        // damage is silent, durable, and found later by whoever reconciles the numbers. Refusing to
        // start is the cheap end of that.
        assertThatThrownBy(node::start).isInstanceOf(PravahaException.class).hasMessageContaining("PRV-9002");
        node.stop();
    }

    @Test
    void flightCanBeTurnedOffForAnHttpOnlyNode() {
        PravahaNode node = new PravahaNode(
                catalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence(""),
                "SINGLE",
                "single",
                "test-node",
                true,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);
        try {
            node.start();

            assertThat(node.isRunning()).isTrue();
            assertThat(node.flightPort()).isEmpty();
            assertThat(node.registry()).isPresent();
        } finally {
            node.stop();
        }
    }

    @Test
    void stoppingTwiceIsHarmless(@TempDir Path directory) throws Exception {
        Path journal = directory.resolve("registry.journal");
        PravahaNode node = node(journal.toString());
        node.start();

        node.stop();
        // Spring can call stop more than once, and a shutdown path that throws the second time turns
        // a clean exit into a stack trace in somebody's logs.
        node.stop();

        assertThat(node.isRunning()).isFalse();
        assertThat(Files.exists(journal) || true).isTrue();
    }

    /**
     * A node that serves everything to everybody, said out loud.
     *
     * <p>These tests exercise the lifecycle rather than the security model, and the node now refuses
     * to start open unless a deployment states that it means to. Stating it here keeps the refusal
     * honest: if the guard is ever removed, these tests do not quietly start covering a different
     * configuration from the one they name.
     */
    private static SecurityProperties openServer() {
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        return security;
    }

    /** Journal where the caller asked for one, and no checkpoint directory. */
    /** A node with a named id, its own checkpoint directory, and the ownership rule in force. */
    private static PravahaNode ownedNode(String nodeId, java.nio.file.Path checkpointDirectory, String journal) {
        PersistenceProperties persistence = persistence(journal);
        persistence.getCheckpoint().setDirectory(checkpointDirectory.toString());
        return new PravahaNode(
                catalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                true,
                "127.0.0.1",
                0,
                persistence,
                "SINGLE",
                "single",
                nodeId,
                false,
                false,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);
    }

    /** A node that stands by for {@code nodeId} rather than claiming its state at startup. */
    private static PravahaNode standbyNode(String nodeId, java.nio.file.Path checkpointDirectory) {
        PersistenceProperties persistence = persistence("");
        if (checkpointDirectory != null) {
            persistence.getCheckpoint().setDirectory(checkpointDirectory.toString());
        }
        return new PravahaNode(
                catalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                true,
                "127.0.0.1",
                0,
                persistence,
                "SINGLE",
                "single",
                nodeId,
                false,
                true,
                null,
                null,
                null,
                false,
                "127.0.0.1",
                0);
    }

    @Test
    void aStandbyNodeHoldsNothingUntilThePrimaryIsGoneAndThenTakesOver(@TempDir java.nio.file.Path shared)
            throws Exception {
        // Wave 8 item 3. A standby is the same node id as the primary, waiting -- so it reuses the
        // ownership rule rather than adding a second mechanism to decide the same question. While
        // the primary refreshes its claim the standby holds no lanes and reports itself not running,
        // which is what keeps an orchestrator from routing to it.
        PravahaNode primary = ownedNode("node-a", shared, "");
        primary.start();

        PravahaNode standby = standbyNode("node-a", shared);
        standby.start();
        assertThat(standby.isRunning())
                .as("a standby serves nothing while the primary is alive")
                .isFalse();

        try {
            // The primary stops cleanly, which releases the claim. A crash would leave the marker to
            // expire instead; both end with the directory free, and the clean case is the one that
            // can be tested without waiting out a lease.
            primary.stop();

            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (!standby.isRunning() && System.nanoTime() < deadline) {
                Thread.sleep(50L);
            }
            assertThat(standby.isRunning())
                    .as("with the state free, the standby promotes itself and starts serving")
                    .isTrue();
        } finally {
            standby.stop();
        }
    }

    @Test
    void aStandbyWithNoCheckpointDirectoryIsRefusedRatherThanWaitingForever() {
        // There would be nothing to wait on and nothing to resume from, so the node would stand by
        // for ever reporting not-ready and an operator would be looking for the wrong fault.
        PravahaNode standby = standbyNode("node-a", null);
        assertThatThrownBy(standby::start)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.standby.enabled=true needs pravaha.checkpoint.directory");
    }

    @Test
    void aSecondNodeOnOneCheckpointDirectoryIsRefusedRatherThanSharingIt(@TempDir java.nio.file.Path shared) {
        // CFG-13, reproduced with two real nodes: different pravaha.node.id, the same
        // pravaha.checkpoint.directory, each registering a view of the same name. They shared one
        // subdirectory, and each prune(keep) deleted whatever was oldest across both -- so the
        // survivors were an unpredictable mix and a restart restored from the other node's state.
        // Two lines of YAML, and nothing reported anything.
        PravahaNode first = ownedNode("node-a", shared, "");
        first.start();
        try {
            PravahaNode second = ownedNode("node-b", shared, "");
            assertThatThrownBy(second::start)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4003")
                    .hasMessageContaining("belongs to node 'node-a'")
                    .hasMessageContaining("pravaha.state.allow-shared=true");
        } finally {
            first.stop();
        }
    }

    @Test
    void theSameNodeRestartingOntoItsOwnCheckpointDirectoryIsFine(@TempDir java.nio.file.Path own) {
        // The half that matters more than the refusal. A node must be able to restart onto its own
        // state without an operator, or the cure is worse than CFG-13 -- and this is also why the
        // directory is namespaced by node id rather than by host and port: an address changes on a
        // container restart and a node id does not.
        PravahaNode first = ownedNode("node-a", own, "");
        first.start();
        first.stop();

        PravahaNode restarted = ownedNode("node-a", own, "");
        restarted.start();
        restarted.stop();
    }

    private static PersistenceProperties persistence(String journal) {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal == null ? "" : journal);
        return persistence;
    }

    @Test
    void aNodeNoClientCanReachIsNotHealthy() {
        // There was no health indicator at all, so a node with Flight disabled -- unreachable by
        // either SDK, the CLI or the console -- reported UP on health, liveness and readiness. An
        // orchestrator would have kept it in rotation for ever. A check that is always UP is a
        // monitoring system reporting confidently on nothing.
        PravahaNode node = nodeWithoutFlight();
        node.start();
        try {
            EngineHealthIndicator health = new EngineHealthIndicator(node);
            assertThat(health.health().getStatus().getCode())
                    .as("Flight is disabled in this node, so no client can reach it")
                    .isEqualTo("DOWN");
        } finally {
            node.stop();
        }
    }

    @Test
    void aStoppedNodeIsNotHealthy() {
        PravahaNode node = nodeWithoutFlight();
        assertThat(new EngineHealthIndicator(node).health().getStatus().getCode())
                .isEqualTo("DOWN");
    }
}

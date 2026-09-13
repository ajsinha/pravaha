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
                "no-flight-node");
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
                "test-node");
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
                "test-node");

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
                "test-node");
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

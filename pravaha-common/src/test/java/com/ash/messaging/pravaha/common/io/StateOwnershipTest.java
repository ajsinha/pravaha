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
package com.ash.messaging.pravaha.common.io;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four outcomes of claiming a state directory, each of which an operator depends on.
 *
 * <p>The interesting pair is the two that look alike and must not behave alike: a node reclaiming
 * its own state after a crash has to succeed without anyone being woken up, and a node finding
 * another node's state has to refuse even when that other node is plainly gone. A stale claim says
 * the owner is not running. It does not say the state is yours.
 */
final class StateOwnershipTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private static StateOwnership.Owner node(String id) {
        return StateOwnership.Owner.current(id, "10.0.0.1", 19090);
    }

    @Test
    void anUnownedDirectoryIsClaimedAndTheMarkerSaysWho(@TempDir Path root) throws Exception {
        try (StateOwnership claim = StateOwnership.claim(root, node("node-a"), LEASE, false)) {
            assertThat(claim.owner().nodeId()).isEqualTo("node-a");
            Properties marker = read(root);
            assertThat(marker.getProperty("node.id")).isEqualTo("node-a");
            assertThat(marker.getProperty("host")).isEqualTo("10.0.0.1");
            assertThat(marker.getProperty("port")).isEqualTo("19090");
            assertThat(marker.getProperty("pid")).isNotBlank();
            assertThat(marker.getProperty("claimed.at")).isNotBlank();
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aCleanCloseLeavesNothingForTheNextStartToReasonAbout(@TempDir Path root) {
        try (StateOwnership ignored = StateOwnership.claim(root, node("node-a"), LEASE, false)) {
            assertThat(root.resolve(StateOwnership.MARKER)).exists();
        }
        assertThat(root.resolve(StateOwnership.MARKER)).doesNotExist();
    }

    @Test
    void anotherNodesStateIsRefusedWhileItIsRunning(@TempDir Path root) throws Exception {
        writeMarker(root, "node-b", "10.0.0.2", 19090, 4242L, System.currentTimeMillis());

        assertThatThrownBy(() -> StateOwnership.claim(root, node("node-a"), LEASE, false))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4003")
                .hasMessageContaining("belongs to node 'node-b'")
                .hasMessageContaining("10.0.0.2:19090")
                .hasMessageContaining("it is running now");
    }

    @Test
    void anotherNodesStateIsStillRefusedAfterItsClaimExpires(@TempDir Path root) throws Exception {
        // The case that decides whether this mechanism is worth anything. The other node is plainly
        // gone -- its claim expired an hour ago -- and taking its state is still the corruption
        // CFG-13 recorded: pruning its checkpoints, restoring from a view computed on a different
        // partition assignment. "Not running" and "yours" are different facts.
        writeMarker(
                root,
                "node-b",
                "10.0.0.2",
                19090,
                4242L,
                System.currentTimeMillis() - Duration.ofHours(1).toMillis());

        assertThatThrownBy(() -> StateOwnership.claim(root, node("node-a"), LEASE, false))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4003")
                .hasMessageContaining("belongs to node 'node-b'")
                .hasMessageContaining("it is not running -- it does not say the state is yours")
                .hasMessageContaining("pravaha.state.allow-shared=true");
    }

    @Test
    void ourOwnStateIsReclaimedAfterACrash(@TempDir Path root) throws Exception {
        // A restart after a crash must not need an operator. The marker is ours, the process that
        // wrote it is gone, and the lease expired: that is exactly what a crash leaves behind.
        writeMarker(
                root,
                "node-a",
                "10.0.0.1",
                19090,
                999_999L,
                System.currentTimeMillis() - Duration.ofHours(1).toMillis());

        try (StateOwnership claim = StateOwnership.claim(root, node("node-a"), LEASE, false)) {
            assertThat(claim.owner().nodeId()).isEqualTo("node-a");
            assertThat(read(root).getProperty("pid"))
                    .as("the marker now names this process, not the dead one")
                    .isNotEqualTo("999999");
        }
    }

    @Test
    void aSecondLiveInstanceOfOneNodeIsRefused(@TempDir Path root) throws Exception {
        // The case an operator hits by starting the service twice, or by rolling a deployment
        // without waiting for the old pod to go. Same node id, different process, claim still fresh.
        writeMarker(root, "node-a", "10.0.0.9", 19090, 888_888L, System.currentTimeMillis());

        assertThatThrownBy(() -> StateOwnership.claim(root, node("node-a"), LEASE, false))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4003")
                .hasMessageContaining("another instance of node 'node-a'")
                .hasMessageContaining("10.0.0.9")
                .hasMessageContaining("Stop the other one");
    }

    @Test
    void theOverrideIsHonouredBecauseRefusingToStartIsAlsoAFailure(@TempDir Path root) throws Exception {
        writeMarker(root, "node-b", "10.0.0.2", 19090, 4242L, System.currentTimeMillis());

        try (StateOwnership claim = StateOwnership.claim(root, node("node-a"), LEASE, true)) {
            assertThat(claim.owner().nodeId()).isEqualTo("node-a");
            assertThat(read(root).getProperty("node.id"))
                    .as("the override takes ownership rather than leaving the marker lying")
                    .isEqualTo("node-a");
        }
    }

    @Test
    void aTruncatedMarkerIsRefusedRatherThanTreatedAsUnowned(@TempDir Path root) throws Exception {
        // An empty marker and an absent one mean different things, and guessing which costs exactly
        // what this class exists to prevent.
        Files.createDirectories(root);
        Files.writeString(root.resolve(StateOwnership.MARKER), "# nothing useful here\n");

        assertThatThrownBy(() -> StateOwnership.claim(root, node("node-a"), LEASE, false))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4004")
                .hasMessageContaining("names no node");
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void theClaimIsRefreshedSoALiveNodeNeverLooksCrashed(@TempDir Path root) throws Exception {
        // The refresh is what makes the lease mean "alive" rather than "started recently". Without
        // it a node quieter than the lease would be reclaimed out from under itself.
        try (StateOwnership ignored = StateOwnership.claim(root, node("node-a"), Duration.ofSeconds(3), false)) {
            long first = Long.parseLong(read(root).getProperty("claimed.at"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            long latest = first;
            while (latest == first && System.nanoTime() < deadline) {
                Thread.sleep(100L);
                latest = Long.parseLong(read(root).getProperty("claimed.at"));
            }
            assertThat(latest)
                    .as("the marker was refreshed within the lease, so the claim stays live")
                    .isGreaterThan(first);
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aSecondClaimInThisProcessIsRefusedWhileTheFirstIsHeld(@TempDir Path root) throws Exception {
        // SAMEPIDCLAIM-1: the marker names this process either way, so it cannot tell two engines in
        // one JVM apart; the second used to take the "our own claim, being re-made" branch and run
        // beside the first. Refused now, and the first's marker survives the attempt.
        try (StateOwnership ignored = StateOwnership.claim(root, node("node-a"), LEASE, false)) {
            String written = read(root).getProperty("claim.id");
            assertThatThrownBy(() -> StateOwnership.claim(root, node("node-a"), LEASE, false))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4003")
                    .hasMessageContaining("another engine in this process")
                    .hasMessageContaining("Close the other engine first");
            // Another spelling of the same directory is the same directory.
            Files.createDirectories(root.resolve("x"));
            assertThatThrownBy(
                            () -> StateOwnership.claim(root.resolve("x").resolve(".."), node("node-a"), LEASE, false))
                    .hasMessageContaining("another engine in this process");
            assertThat(read(root).getProperty("claim.id")).isEqualTo(written);
        }
        // Released by close: the next claim is an ordinary one.
        try (StateOwnership ignored = StateOwnership.claim(root, node("node-a"), LEASE, false)) {
            assertThat(Files.exists(root.resolve(StateOwnership.MARKER))).isTrue();
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void closeDeletesOnlyTheMarkerThisClaimWrote(@TempDir Path root) throws Exception {
        // Two claims sharing a directory on purpose (allow-shared): the second rewrote the marker, so
        // the first closing must leave it -- it is the second's, and the second is still running.
        StateOwnership first = StateOwnership.claim(root, node("node-a"), LEASE, false);
        try (StateOwnership ignored = StateOwnership.claim(root, node("node-a"), LEASE, true)) {
            String secondId = read(root).getProperty("claim.id");
            first.close();
            assertThat(Files.exists(root.resolve(StateOwnership.MARKER))).isTrue();
            assertThat(read(root).getProperty("claim.id")).isEqualTo(secondId);
        }
        assertThat(Files.exists(root.resolve(StateOwnership.MARKER))).isFalse();
    }

    @Test
    void oneEngineNamingADirectoryTwiceHoldsOneClaim(@TempDir Path root) {
        // A journal kept in the checkpoint directory: one engine, one directory, one claim.
        java.util.List<StateOwnership> held = new java.util.ArrayList<>();
        try {
            StateOwnership.claimInto(held, root, node("node-a"), LEASE, false);
            StateOwnership.claimInto(held, root.resolve("."), node("node-a"), LEASE, false);
            assertThat(held).hasSize(1);
        } finally {
            held.forEach(StateOwnership::close);
        }
    }

    private static Properties read(Path root) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(root.resolve(StateOwnership.MARKER))) {
            properties.load(in);
        }
        return properties;
    }

    private static void writeMarker(Path root, String nodeId, String host, int port, long pid, long claimedAt)
            throws Exception {
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("node.id", nodeId);
        properties.setProperty("host", host);
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("pid", Long.toString(pid));
        properties.setProperty("claimed.at", Long.toString(claimedAt));
        try (OutputStream out = Files.newOutputStream(root.resolve(StateOwnership.MARKER))) {
            properties.store(out, "test fixture");
        }
    }
}

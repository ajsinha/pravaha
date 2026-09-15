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
package com.ash.messaging.pravaha.server.state;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.io.StateOwnership;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A standby waits, takes over when the primary stops refreshing, and says what the takeover lost.
 *
 * <p>The three cases are the three ways this can be wrong: promoting too early is a split brain,
 * never promoting is a standby that is not one, and promoting onto another node's state is the
 * corruption {@code StateOwnership} exists to prevent, arrived at from the other direction.
 */
@Timeout(60)
final class StandbyWatchTest {

    private static final Duration LEASE = Duration.ofSeconds(2);
    private static final Duration POLL = Duration.ofMillis(50);

    @Test
    void aStandbyDoesNotPromoteWhileThePrimaryKeepsRefreshingItsClaim(@TempDir Path root) throws Exception {
        CountDownLatch promoted = new CountDownLatch(1);
        // A live primary: its claim is refreshed by StateOwnership's own lease thread.
        try (StateOwnership primary = StateOwnership.claim(
                        root, StateOwnership.Owner.current("node-a", "10.0.0.1", 9090), LEASE, false);
                StandbyWatch watch = new StandbyWatch(root, "node-a", LEASE, POLL, takeover -> promoted.countDown())) {
            watch.start();

            assertThat(promoted.await(3 * LEASE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the primary is alive and refreshing, so there is nothing to take over")
                    .isFalse();
            assertThat(primary.owner().nodeId()).isEqualTo("node-a");
        }
    }

    @Test
    void aStandbyPromotesOnceThePrimaryStopsRefreshing(@TempDir Path root) throws Exception {
        // A claim written by hand and never refreshed: what a crashed primary leaves behind.
        writeMarker(root, "node-a", "10.0.0.1", 9090, 4242L, System.currentTimeMillis());

        CountDownLatch promoted = new CountDownLatch(1);
        AtomicReference<StandbyWatch.Takeover> seen = new AtomicReference<>();
        try (StandbyWatch watch = new StandbyWatch(root, "node-a", LEASE, POLL, takeover -> {
            seen.set(takeover);
            promoted.countDown();
        })) {
            watch.start();
            assertThat(promoted.await(30, TimeUnit.SECONDS))
                    .as("the claim went unrefreshed for longer than the lease, so the primary is gone")
                    .isTrue();
        }

        assertThat(seen.get().previousOwner()).contains("node-a").contains("10.0.0.1:9090");
        assertThat(seen.get().describe())
                .as("a takeover buys recovery time, not continuity, and has to say so at the moment it happens")
                .contains("promoted from standby")
                .contains("is not in the state this node resumes from")
                .contains("anything the source can no longer supply is lost");
    }

    @Test
    void aStandbyNeverPromotesOntoAnotherNodesState(@TempDir Path root) throws Exception {
        // Expired an hour ago, so the other node is plainly gone -- and it is still not this node's
        // state. Taking it is CFG-13's corruption reached from the failover side instead of the
        // startup side, and it would be the more dangerous of the two because nobody typed anything.
        writeMarker(
                root,
                "node-b",
                "10.0.0.2",
                9090,
                4242L,
                System.currentTimeMillis() - Duration.ofHours(1).toMillis());

        CountDownLatch promoted = new CountDownLatch(1);
        try (StandbyWatch watch = new StandbyWatch(root, "node-a", LEASE, POLL, takeover -> promoted.countDown())) {
            watch.start();
            assertThat(promoted.await(3 * LEASE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("a standby for node-a must never take node-b's state, however dead node-b is")
                    .isFalse();
        }
    }

    @Test
    void anUnreadableMarkerIsWaitedOutRatherThanTreatedAsFree(@TempDir Path root) throws Exception {
        // The one case where guessing is worse than waiting: it may be a live primary with a
        // transient disk problem, and promoting into that is exactly the split brain the lease
        // exists to prevent.
        Files.createDirectories(root);
        Files.writeString(root.resolve(StateOwnership.MARKER), "# no node named here\n");

        CountDownLatch promoted = new CountDownLatch(1);
        try (StandbyWatch watch = new StandbyWatch(root, "node-a", LEASE, POLL, takeover -> promoted.countDown())) {
            watch.start();
            assertThat(promoted.await(3 * LEASE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("an unreadable marker is not an absent one")
                    .isFalse();
        }
    }

    @Test
    void anUnownedDirectoryIsTakenImmediately(@TempDir Path root) throws Exception {
        // No marker at all: nothing to wait for, and a standby that dithered here would leave a node
        // down for a lease over a primary that never started.
        Files.createDirectories(root);

        CountDownLatch promoted = new CountDownLatch(1);
        try (StandbyWatch watch = new StandbyWatch(root, "node-a", LEASE, POLL, takeover -> promoted.countDown())) {
            watch.start();
            assertThat(promoted.await(10, TimeUnit.SECONDS)).isTrue();
        }
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

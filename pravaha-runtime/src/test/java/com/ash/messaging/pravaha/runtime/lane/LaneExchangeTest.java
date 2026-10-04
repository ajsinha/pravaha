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
package com.ash.messaging.pravaha.runtime.lane;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rows crossing between lanes.
 *
 * <p>What an exchange has to get right is not that a row arrives -- that is easy -- but that
 * <em>every</em> row arrives, at the lane that owns its key, in the order its sender wrote it, and
 * without a full ring turning into a stall. Each of those is a separate test here, because each
 * fails in a different way and only one of them fails loudly.
 */
// Bounded so a regression fails rather than hangs. Found the hard way: seeding "never drain the
// inbound exchange rings" did not fail this suite, it stalled it -- the feeding loop waited for inbox
// space that a blocked lane would never free. A hanging test in CI is worse than a failing one,
// because it looks like an infrastructure problem and gets retried rather than read.
@Timeout(60)
class LaneExchangeTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(256, 32)
                .withBatchSize(32)
                .withExchangeCells(64)
                .withArena(64 * 1024, 2)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("test-lane", true);
    }

    /**
     * Feeds one long into a lane's inbox, waiting out backpressure but never forever.
     *
     * <p>The deadline is not decoration. {@code @Timeout} interrupts the test thread, and a thread
     * spinning on {@code onSpinWait} does not observe interrupts -- so without this, a regression
     * that stops the exchange draining stalls the build instead of failing it. That happened while
     * this suite was being written.
     */
    private static void offer(Lane lane, MemoryRegion scratch, long value) {
        scratch.putLong(0, value);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!lane.offer(scratch, 0, 8)) {
            lane.checkHealth();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("lane " + lane.laneId() + " stopped accepting rows: its inbox stayed full "
                        + "for 30 s, which means it is not draining -- check the exchange, not the inbox");
            }
            Thread.onSpinWait();
        }
    }

    /** Sends across the exchange, bounded for the same reason. */
    private static void send(LaneExchange.Sender sender, int target, MemoryRegion row, int length) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!sender.send(target, row, 0, length)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("lane " + sender.laneId() + " could not send to lane " + target
                        + " for 30 s: that lane is not draining its inbound ring");
            }
            // Safe to spin only because this exchange is one hop: a receiving lane never has to send
            // in order to receive, so somebody is always draining.
            Thread.onSpinWait();
        }
    }

    @Test
    void everyRowReachesTheLaneThatOwnsItsKey() {
        // A repartition: rows are fed to lane 0 regardless of key, and lane 0 forwards each one to
        // whichever lane the key belongs to. That is the shape of a GROUP BY on a column the source
        // was not partitioned by, which is the case the exchange exists for.
        MemoryAccess access = MemoryAccess.best();
        int lanes = 4;
        AtomicLongArray receivedPerLane = new AtomicLongArray(lanes);
        java.util.Set<Long> misrouted = ConcurrentHashMap.newKeySet();
        AtomicReference<LaneGroup> holder = new AtomicReference<>();

        try (MemoryRegion scratch = access.allocate(64);
                LaneGroup group = new LaneGroup(lanes, config(), access, context -> {
                    int laneId = context.laneId();
                    LaneExchange.Sender sender = context.exchange().orElseThrow();
                    MemoryRegion forwarding = access.allocate(64);
                    return (region, offsets, count) -> {
                        for (int i = 0; i < count; i++) {
                            long key = region.getLong((int) offsets[i]);
                            int owner = java.util.Objects.requireNonNull(holder.get())
                                    .laneFor(key);
                            if (owner == laneId) {
                                if (holder.get().laneFor(key) != laneId) {
                                    misrouted.add(key);
                                }
                                receivedPerLane.incrementAndGet(laneId);
                            } else {
                                forwarding.putLong(0, key);
                                send(sender, owner, forwarding, 8);
                            }
                        }
                        return count;
                    };
                })) {
            holder.set(group);
            group.start();
            for (long key = 0; key < 5_000; key++) {
                offer(group.lane(0), scratch, key);
            }
            assertThat(group.awaitQuiescent(PATIENCE)).isTrue();
            group.checkHealth();

            assertThat(misrouted).isEmpty();
            long delivered = 0;
            for (int i = 0; i < lanes; i++) {
                assertThat(receivedPerLane.get(i))
                        .as("lane %s received nothing; the exchange is not spreading", i)
                        .isPositive();
                delivered += receivedPerLane.get(i);
            }
            assertThat(delivered).as("no row may be lost crossing lanes").isEqualTo(5_000);
        }
    }

    @Test
    void aSenderSeesBackpressureRatherThanBlocking() {
        // A full ring must be a boolean, not a stall. A lane that blocks inside the exchange is not
        // doing the work that would drain it, which under load turns a slow peer into a stopped one.
        MemoryAccess access = MemoryAccess.best();
        try (LaneExchange exchange = new LaneExchange(2, access, 4, 32);
                MemoryRegion scratch = access.allocate(64)) {
            LaneExchange.Sender sender = exchange.senderFor(0);

            int sent = 0;
            while (sender.send(1, scratch, 0, 8)) {
                sent++;
                assertThat(sent).isLessThan(100);
            }
            assertThat(sent).isEqualTo(4);
            assertThat(exchange.inFlight()).isEqualTo(4);
        }
    }

    @Test
    void aLaneCannotRouteToItselfThroughTheExchange() {
        // Not pedantry: routing a lane's own row through a ring costs a copy and a round trip to
        // reach the processor it could have called directly, and it is the kind of thing a
        // hash-routing bug does silently at one row in N.
        MemoryAccess access = MemoryAccess.best();
        try (LaneExchange exchange = new LaneExchange(2, access, 4, 32);
                MemoryRegion scratch = access.allocate(64)) {
            assertThatThrownBy(() -> exchange.senderFor(1).send(1, scratch, 0, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("routed a row to itself");
        }
    }

    @Test
    void oneLaneNeedsNoExchangeAtAll() {
        MemoryAccess access = MemoryAccess.best();
        try (LaneGroup group = new LaneGroup(1, config(), access, context -> {
            assertThat(context.exchange()).isEmpty();
            return (region, offsets, count) -> count;
        })) {
            assertThat(group.exchange()).isEmpty();
        }
    }

    @Test
    void exchangedRowsAreCountedSeparatelyFromIngestedOnes() {
        // The ratio of the two is what says whether a repartition is earning its cost.
        MemoryAccess access = MemoryAccess.best();
        AtomicReference<LaneGroup> holder = new AtomicReference<>();

        try (MemoryRegion scratch = access.allocate(64);
                LaneGroup group = new LaneGroup(2, config(), access, context -> {
                    int laneId = context.laneId();
                    LaneExchange.Sender sender = context.exchange().orElseThrow();
                    MemoryRegion forwarding = access.allocate(64);
                    return (region, offsets, count) -> {
                        for (int i = 0; i < count; i++) {
                            long key = region.getLong((int) offsets[i]);
                            int owner = java.util.Objects.requireNonNull(holder.get())
                                    .laneFor(key);
                            if (owner != laneId) {
                                forwarding.putLong(0, key);
                                send(sender, owner, forwarding, 8);
                            }
                        }
                        return count;
                    };
                })) {
            holder.set(group);
            group.start();
            for (long key = 0; key < 1_000; key++) {
                offer(group.lane(0), scratch, key);
            }
            assertThat(group.awaitQuiescent(PATIENCE)).isTrue();
            group.checkHealth();

            List<LaneMetrics> metrics = group.metrics();
            assertThat(metrics.get(0).exchangedIn())
                    .as("lane 0 was fed directly and receives nothing through the exchange")
                    .isZero();
            assertThat(metrics.get(1).exchangedIn())
                    .as("everything lane 1 sees crossed the exchange")
                    .isPositive();
            assertThat(group.totals().exchangedIn()).isEqualTo(metrics.get(1).exchangedIn());
        }
    }

    @Test
    void aSendersRowsArriveInTheOrderItWroteThem() {
        // Across senders there is no order and none is promised. From one sender there is exactly
        // one, and losing it would reorder a key's history -- which for a keyed aggregate is a wrong
        // answer rather than a slow one.
        MemoryAccess access = MemoryAccess.best();
        try (LaneExchange exchange = new LaneExchange(2, access, 512, 32);
                MemoryRegion scratch = access.allocate(64)) {
            LaneExchange.Sender sender = exchange.senderFor(0);
            for (long i = 0; i < 400; i++) {
                scratch.putLong(0, i);
                assertThat(sender.send(1, scratch, 0, 8)).isTrue();
            }

            long[] batch = new long[512];
            var ring = exchange.ring(0, 1);
            int drained = ring.drain(batch, batch.length);
            assertThat(drained).isEqualTo(400);
            for (int i = 0; i < drained; i++) {
                assertThat(ring.region().getLong((int) batch[i])).isEqualTo((long) i);
            }
        }
    }
}

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
package com.ash.messaging.pravaha.common.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ingest-to-lane edge.
 *
 * <p>The test that matters most here is {@link #cellsAreReusableOnlyAfterTheLaneReleasesThem}. It is
 * the property that made this class necessary rather than reusing {@link MpscLongRing}, and it is
 * exactly the property whose absence would produce a corruption that is rare, load-dependent and
 * almost impossible to attribute after the fact.
 */
class RowInboxTest {

    private MemoryAccess access;
    private RowInbox inbox;
    private MemoryRegion scratch;

    @BeforeEach
    void setUp() {
        access = MemoryAccess.best();
        inbox = new RowInbox(access, 8, 64);
        scratch = access.allocate(256);
    }

    @AfterEach
    void tearDown() {
        inbox.close();
        scratch.close();
    }

    @Test
    void aRowSurvivesTheHandoff() {
        scratch.putLong(0, 0xCAFEBABEL);
        scratch.putLong(8, 42L);

        assertThat(inbox.offer(scratch, 0, 16)).isTrue();

        long[] offsets = new long[4];
        assertThat(inbox.drain(offsets, 4)).isEqualTo(1);
        assertThat(inbox.region().getLong((int) offsets[0])).isEqualTo(0xCAFEBABEL);
        assertThat(inbox.region().getLong((int) offsets[0] + 8)).isEqualTo(42L);
    }

    @Test
    void cellsAreReusableOnlyAfterTheLaneReleasesThem() {
        for (int i = 0; i < inbox.cellCount(); i++) {
            scratch.putLong(0, i);
            assertThat(inbox.offer(scratch, 0, 8)).isTrue();
        }
        assertThat(inbox.claim()).isEqualTo(RowInbox.NO_SPACE);

        long[] offsets = new long[inbox.cellCount()];
        assertThat(inbox.drain(offsets, offsets.length)).isEqualTo(inbox.cellCount());

        // Drained, but the lane is still reading these rows. Every row a producer wrote is a
        // flyweight the consumer holds, so the cells must stay untouchable until it says otherwise.
        assertThat(inbox.claim())
                .as("a drained-but-unreleased cell must not be reclaimed; the lane is still reading it")
                .isEqualTo(RowInbox.NO_SPACE);
        for (int i = 0; i < offsets.length; i++) {
            assertThat(inbox.region().getLong((int) offsets[i])).isEqualTo(i);
        }

        inbox.release();
        assertThat(inbox.claim()).isNotEqualTo(RowInbox.NO_SPACE);
    }

    @Test
    void aFullInboxRefusesRatherThanBlocking() {
        for (int i = 0; i < inbox.cellCount(); i++) {
            assertThat(inbox.offer(scratch, 0, 8)).isTrue();
        }
        assertThat(inbox.offer(scratch, 0, 8))
                .as("full is a backpressure signal, not an error and not a block")
                .isFalse();
        assertThat(inbox.fill()).isEqualTo(1.0);
    }

    @Test
    void drainStopsAtACellThatIsClaimedButNotYetPublished() {
        long first = inbox.claim();
        long second = inbox.claim();
        inbox.region().putLong(inbox.offsetOf(second), 2L);
        inbox.publish(second);

        // The first producer was descheduled mid-write. Skipping past it would reorder the stream,
        // which for a keyed query is a wrong answer rather than a slow one.
        long[] offsets = new long[4];
        assertThat(inbox.drain(offsets, 4)).isZero();

        inbox.region().putLong(inbox.offsetOf(first), 1L);
        inbox.publish(first);
        assertThat(inbox.drain(offsets, 4)).isEqualTo(2);
        assertThat(inbox.region().getLong((int) offsets[0])).isEqualTo(1L);
        assertThat(inbox.region().getLong((int) offsets[1])).isEqualTo(2L);
    }

    @Test
    void cellsDoNotOverlap() {
        int previous = -1;
        for (int i = 0; i < inbox.cellCount(); i++) {
            long sequence = inbox.claim();
            int offset = inbox.offsetOf(sequence);
            assertThat(offset % 8)
                    .as("cells start 8-byte aligned so no row header straddles a word")
                    .isZero();
            assertThat(offset).isGreaterThan(previous);
            previous = offset;
        }
    }

    @Test
    void aRowTooLargeForACellSaysWhatToChange() {
        assertThatThrownBy(() -> inbox.offer(scratch, 0, inbox.cellBytes() + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lane.inbox.cell.size");
    }

    @Test
    void sizingIsRoundedUpAndReported() {
        try (RowInbox odd = new RowInbox(access, 5, 13)) {
            assertThat(odd.cellCount()).isEqualTo(8);
            assertThat(odd.cellBytes()).isEqualTo(16);
        }
    }

    @Test
    void manyProducersLoseNothingAndKeepTheirOwnOrder() throws Exception {
        int producers = 4;
        int perProducer = 20_000;
        try (RowInbox big = new RowInbox(access, 256, 16)) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int producerId = p;
                Thread t = new Thread(
                        () -> {
                            try {
                                start.await();
                                for (int i = 0; i < perProducer; i++) {
                                    long sequence;
                                    while ((sequence = big.claim()) == RowInbox.NO_SPACE) {
                                        Thread.onSpinWait();
                                    }
                                    int offset = big.offsetOf(sequence);
                                    big.region().putLong(offset, producerId);
                                    big.region().putLong(offset + 8, i);
                                    big.publish(sequence);
                                }
                            } catch (Throwable e) {
                                failure.compareAndSet(null, e);
                            }
                        },
                        "producer-" + p);
                threads.add(t);
                t.start();
            }

            long[] expected = new long[producers];
            long[] batch = new long[64];
            int received = 0;
            int total = producers * perProducer;
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (received < total && System.nanoTime() < deadline) {
                int n = big.drain(batch, batch.length);
                for (int i = 0; i < n; i++) {
                    int offset = (int) batch[i];
                    int producer = (int) big.region().getLong(offset);
                    long value = big.region().getLong(offset + 8);
                    // Per producer, order must be exactly what that producer wrote. Across
                    // producers there is no ordering to check and none is promised.
                    assertThat(value).isEqualTo(expected[producer]);
                    expected[producer]++;
                }
                received += n;
                big.release();
            }
            for (Thread t : threads) {
                t.join(TimeUnit.SECONDS.toMillis(10));
            }
            assertThat(failure.get()).isNull();
            assertThat(received).as("nothing may be lost or duplicated").isEqualTo(total);
        }
    }
}

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

/** The lane-to-lane ring. Same guarantees as the inbox, one writer, no compare-and-set. */
class SpscRowRingTest {

    private MemoryAccess access;
    private SpscRowRing ring;
    private MemoryRegion scratch;

    @BeforeEach
    void setUp() {
        access = MemoryAccess.best();
        ring = new SpscRowRing(access, 8, 64);
        scratch = access.allocate(256);
    }

    @AfterEach
    void tearDown() {
        ring.close();
        scratch.close();
    }

    @Test
    void aRowSurvivesTheHandoff() {
        scratch.putLong(0, 0xFEEDL);
        assertThat(ring.offer(scratch, 0, 8)).isTrue();

        long[] offsets = new long[4];
        assertThat(ring.drain(offsets, 4)).isEqualTo(1);
        assertThat(ring.region().getLong((int) offsets[0])).isEqualTo(0xFEEDL);
    }

    @Test
    void cellsAreReusableOnlyAfterTheConsumerReleasesThem() {
        // The property that makes a flyweight row safe for the whole batch, exactly as in RowInbox:
        // the consuming lane is still reading these cells until it says otherwise.
        for (int i = 0; i < ring.cellCount(); i++) {
            scratch.putLong(0, i);
            assertThat(ring.offer(scratch, 0, 8)).isTrue();
        }
        assertThat(ring.claim()).isEqualTo(SpscRowRing.NO_SPACE);

        long[] offsets = new long[ring.cellCount()];
        assertThat(ring.drain(offsets, offsets.length)).isEqualTo(ring.cellCount());
        assertThat(ring.claim())
                .as("drained is not released: the consuming lane is still reading these rows")
                .isEqualTo(SpscRowRing.NO_SPACE);

        ring.release();
        assertThat(ring.claim()).isNotEqualTo(SpscRowRing.NO_SPACE);
    }

    @Test
    void aFullRingRefusesRatherThanBlocking() {
        for (int i = 0; i < ring.cellCount(); i++) {
            assertThat(ring.offer(scratch, 0, 8)).isTrue();
        }
        assertThat(ring.offer(scratch, 0, 8))
                .as("a lane that blocks on a full exchange ring is how a shuffle deadlocks")
                .isFalse();
    }

    @Test
    void aRowTooLargeForACellNamesTheSettingThatExists() {
        // PF-3/DOCX-20. It named `lane.exchange.cell.size`, which has never been a key. The ring
        // is built from LaneConfig.inboxCellBytes, so that is the key that widens it, and an
        // operator who follows the advice now sees the failure go away.
        assertThatThrownBy(() -> ring.offer(scratch, 0, ring.cellBytes() + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pravaha.lane.inbox.cell-bytes")
                .hasMessageNotContaining("lane.exchange.cell.size");
    }

    @Test
    void everythingArrivesInOrderUnderRealConcurrency() throws Exception {
        int total = 200_000;
        try (SpscRowRing big = new SpscRowRing(access, 128, 16)) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread producer = new Thread(
                    () -> {
                        try (MemoryRegion source = access.allocate(64)) {
                            start.await();
                            for (long i = 0; i < total; i++) {
                                source.putLong(0, i);
                                while (!big.offer(source, 0, 8)) {
                                    Thread.onSpinWait();
                                }
                            }
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                        }
                    },
                    "spsc-producer");
            producer.start();

            long[] batch = new long[64];
            long expected = 0;
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (expected < total && System.nanoTime() < deadline) {
                int n = big.drain(batch, batch.length);
                for (int i = 0; i < n; i++) {
                    // A single producer means a total order, and the consumer must see exactly it.
                    assertThat(big.region().getLong((int) batch[i])).isEqualTo(expected);
                    expected++;
                }
                big.release();
            }
            producer.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(failure.get()).isNull();
            assertThat(expected).isEqualTo(total);
        }
    }
}

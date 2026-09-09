/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.queue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpscLongRingTest {

    @Test
    void preservesFifoOrder() {
        MpscLongRing ring = new MpscLongRing(16);
        for (long i = 1; i <= 10; i++) {
            assertThat(ring.offer(i)).isTrue();
        }
        for (long i = 1; i <= 10; i++) {
            assertThat(ring.poll()).isEqualTo(i);
        }
        assertThat(ring.poll()).isEqualTo(MpscLongRing.EMPTY);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 16, 100, 1024})
    void capacityIsRoundedUpToAPowerOfTwo(int requested) {
        MpscLongRing ring = new MpscLongRing(requested);
        assertThat(Integer.bitCount(ring.capacity())).isOne();
        assertThat(ring.capacity()).isGreaterThanOrEqualTo(requested);
    }

    @Test
    void rejectsACapacityBelowTwo() {
        assertThatThrownBy(() -> new MpscLongRing(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MpscLongRing(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void offerReturnsFalseWhenFullRatherThanBlockingOrThrowing() {
        // Full is a backpressure signal, not an error: the caller pauses its source (design 13.5).
        MpscLongRing ring = new MpscLongRing(4);
        for (int i = 0; i < 4; i++) {
            assertThat(ring.offer(i + 1)).isTrue();
        }
        assertThat(ring.offer(99L)).isFalse();
        assertThat(ring.fill()).isEqualTo(1.0);

        assertThat(ring.poll()).isEqualTo(1L);
        assertThat(ring.offer(99L))
                .as("a slot freed by the consumer must become usable")
                .isTrue();
    }

    @Test
    void rejectsTheReservedEmptyMarker() {
        // Accepting it would make a published slot indistinguishable from an unpublished one.
        assertThatThrownBy(() -> new MpscLongRing(4).offer(MpscLongRing.EMPTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void theEmptyMarkerIsNotAValidArenaHandle() {
        assertThat(MpscLongRing.EMPTY).isNotEqualTo(com.ash.messaging.pravaha.common.arena.ArenaHandle.NULL);
    }

    @Test
    void wrapsAroundIndefinitely() {
        MpscLongRing ring = new MpscLongRing(4);
        for (long i = 1; i <= 1000; i++) {
            assertThat(ring.offer(i)).isTrue();
            assertThat(ring.poll()).isEqualTo(i);
        }
        assertThat(ring.isEmpty()).isTrue();
    }

    @Test
    void drainTakesABatchInOrder() {
        MpscLongRing ring = new MpscLongRing(64);
        for (long i = 1; i <= 20; i++) {
            ring.offer(i);
        }
        long[] batch = new long[8];

        assertThat(ring.drain(batch, 8)).isEqualTo(8);
        assertThat(batch).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(ring.drain(batch, 8)).isEqualTo(8);
        assertThat(batch).containsExactly(9, 10, 11, 12, 13, 14, 15, 16);
        assertThat(ring.drain(batch, 8)).isEqualTo(4);
        assertThat(ring.drain(batch, 8)).isZero();
    }

    @Test
    void drainRespectsBothTheLimitAndTheArrayLength() {
        MpscLongRing ring = new MpscLongRing(64);
        for (long i = 1; i <= 20; i++) {
            ring.offer(i);
        }
        assertThat(ring.drain(new long[4], 100)).isEqualTo(4);
        assertThat(ring.drain(new long[100], 3)).isEqualTo(3);
    }

    @Test
    void sizeAndFillTrackOccupancy() {
        MpscLongRing ring = new MpscLongRing(8);
        assertThat(ring.isEmpty()).isTrue();
        ring.offer(1);
        ring.offer(2);
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.fill()).isEqualTo(0.25);
        assertThat(ring.toString()).contains("2/8");
    }

    // ------------------------------------------------------------------ concurrency

    @RepeatedTest(5)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void everyValueFromEveryProducerArrivesExactlyOnce() throws Exception {
        // The test that matters. A lock-free queue that passes single-threaded and loses items
        // under contention is the classic way this goes wrong, and it goes wrong rarely enough
        // that only a real concurrent run finds it.
        int producers = 8;
        int perProducer = 20_000;
        int total = producers * perProducer;

        MpscLongRing ring = new MpscLongRing(1024);
        BitSet seen = new BitSet(total);
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong rejected = new AtomicLong();
        List<Thread> threads = new ArrayList<>();

        for (int p = 0; p < producers; p++) {
            int base = p * perProducer;
            Thread t = new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int i = 0; i < perProducer; i++) {
                            // Value 0 is unusable as a payload only in the sense that EMPTY is; +1 keeps
                            // every value distinct and non-sentinel.
                            long value = base + i + 1L;
                            while (!ring.offer(value)) {
                                rejected.incrementAndGet();
                                Thread.onSpinWait();
                            }
                        }
                    },
                    "producer-" + p);
            t.start();
            threads.add(t);
        }

        ready.await();
        go.countDown();

        long[] batch = new long[256];
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (received < total && System.nanoTime() < deadline) {
            int n = ring.drain(batch, batch.length);
            for (int i = 0; i < n; i++) {
                int index = (int) (batch[i] - 1);
                assertThat(seen.get(index))
                        .as("value %d delivered twice", batch[i])
                        .isFalse();
                seen.set(index);
            }
            received += n;
            if (n == 0) {
                Thread.onSpinWait();
            }
        }

        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(received).as("every offered value must be delivered").isEqualTo(total);
        assertThat(seen.cardinality()).as("no duplicates and no gaps").isEqualTo(total);
        // Contention on a 1024-slot ring with eight producers should genuinely have hit "full" at
        // least once; if it never did, the test was not exercising the path it claims to.
        assertThat(rejected.get())
                .as("the full-ring path should have been exercised")
                .isPositive();
    }

    @RepeatedTest(3)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aSingleProducerPreservesItsOwnOrderUnderConcurrentConsumption() throws Exception {
        // MPSC guarantees per-producer order, not a global order across producers. This pins the
        // guarantee we actually rely on.
        MpscLongRing ring = new MpscLongRing(256);
        int count = 100_000;
        AtomicBoolean outOfOrder = new AtomicBoolean();

        Thread producer = new Thread(() -> {
            for (long i = 1; i <= count; i++) {
                while (!ring.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();

        long previous = 0;
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (received < count && System.nanoTime() < deadline) {
            long v = ring.poll();
            if (v == MpscLongRing.EMPTY) {
                Thread.onSpinWait();
                continue;
            }
            if (v <= previous) {
                outOfOrder.set(true);
            }
            previous = v;
            received++;
        }
        producer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(received).isEqualTo(count);
        assertThat(outOfOrder).isFalse();
    }
}

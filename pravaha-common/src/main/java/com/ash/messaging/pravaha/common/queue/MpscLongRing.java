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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A bounded multi-producer, single-consumer ring of {@code long} values.
 *
 * <p>Carries primitives rather than objects because what it actually carries is arena handles
 * (design section 8.5), and boxing one per record would reintroduce exactly the allocation the row layout
 * exists to remove. A {@code Queue<Long>} at a million records a second is tens of megabytes a
 * second of garbage for no purpose.
 *
 * <p><strong>One consumer.</strong> Many threads may {@link #offer}; exactly one may {@link #poll}
 * or {@link #drain}. That is the ingest-to-lane edge in the design (section 13.3): several source
 * readers feed one lane, and the lane owns everything downstream alone.
 *
 * <p>Protocol: a producer claims a slot by advancing the producer index atomically, then publishes
 * its value with release semantics. The consumer reads with acquire and writes {@link #EMPTY} back
 * before advancing. A slot holding {@code EMPTY} means a producer has claimed it but not yet
 * published, so the consumer waits rather than skipping -- which is what keeps ordering intact when
 * producers are descheduled mid-publish.
 */
public final class MpscLongRing {

    /**
     * Marks an unpublished slot.
     *
     * <p>{@code Long.MIN_VALUE} rather than {@code -1}: {@code -1} is
     * {@link com.ash.messaging.pravaha.common.arena.ArenaHandle#NULL}, and a sentinel that collides
     * with a legitimate-looking value is a bug waiting for the one day it matters.
     */
    public static final long EMPTY = Long.MIN_VALUE;

    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle PRODUCER;
    private static final VarHandle CONSUMER;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PRODUCER = l.findVarHandle(MpscLongRing.class, "producerIndex", long.class);
            CONSUMER = l.findVarHandle(MpscLongRing.class, "consumerIndex", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long[] buffer;
    private final int mask;
    private final int capacity;

    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long producerIndex;

    // Padding either side of the consumer cursor. The producer and consumer cursors are written by
    // different threads; sharing a cache line between them costs a coherence miss on every single
    // operation, and it is the classic way a lock-free queue quietly runs at a third of its speed.
    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7;

    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long consumerIndex;

    @SuppressWarnings("unused")
    private long p8, p9, p10, p11, p12, p13, p14;

    /**
     * @param requestedCapacity rounded up to a power of two, so the index-to-slot mapping is a mask
     *     rather than a division
     */
    public MpscLongRing(int requestedCapacity) {
        if (requestedCapacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2, got " + requestedCapacity);
        }
        this.capacity = nextPowerOfTwo(requestedCapacity);
        this.mask = capacity - 1;
        this.buffer = new long[capacity];
        java.util.Arrays.fill(buffer, EMPTY);
    }

    private static int nextPowerOfTwo(int value) {
        int result = Integer.highestOneBit(value);
        return result == value ? value : result << 1;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Offers a value.
     *
     * @return {@code false} if the ring is full. Full is a backpressure signal, not an error: the
     *     caller pauses its source rather than retrying blindly (design section 13.5).
     * @throws IllegalArgumentException if {@code value} is {@link #EMPTY}, which would be
     *     indistinguishable from an unpublished slot
     */
    public boolean offer(long value) {
        if (value == EMPTY) {
            throw new IllegalArgumentException("EMPTY is reserved as the unpublished-slot marker");
        }
        long producer;
        do {
            producer = (long) PRODUCER.getVolatile(this);
            if (producer - (long) CONSUMER.getVolatile(this) >= capacity) {
                return false;
            }
        } while (!PRODUCER.compareAndSet(this, producer, producer + 1));

        // Release: everything the producer wrote before this is visible to the consumer that reads
        // the slot with acquire. Without it the consumer could see the handle before the row.
        SLOT.setRelease(buffer, (int) (producer & mask), value);
        return true;
    }

    /**
     * Takes one value.
     *
     * @return the value, or {@link #EMPTY} if the ring is empty or the next slot is claimed but not
     *     yet published
     */
    public long poll() {
        long consumer = consumerIndex;
        int slot = (int) (consumer & mask);
        long value = (long) SLOT.getAcquire(buffer, slot);
        if (value == EMPTY) {
            return EMPTY;
        }
        SLOT.setRelease(buffer, slot, EMPTY);
        CONSUMER.setRelease(this, consumer + 1);
        return value;
    }

    /**
     * Takes up to {@code limit} values into {@code into}.
     *
     * <p>Draining in batches is what makes the whole pipeline economical: it amortises the cursor
     * traffic over many records and hands the generated operator a counted loop the JIT can unroll
     * (design section 13.4).
     *
     * @return how many were written
     */
    public int drain(long[] into, int limit) {
        int max = Math.min(limit, into.length);
        long consumer = consumerIndex;
        int taken = 0;
        while (taken < max) {
            int slot = (int) ((consumer + taken) & mask);
            long value = (long) SLOT.getAcquire(buffer, slot);
            if (value == EMPTY) {
                break;
            }
            SLOT.setRelease(buffer, slot, EMPTY);
            into[taken++] = value;
        }
        if (taken > 0) {
            CONSUMER.setRelease(this, consumer + taken);
        }
        return taken;
    }

    /** Approximate occupancy. Exact when quiescent; a hint while producers are running. */
    public int size() {
        long size = (long) PRODUCER.getVolatile(this) - (long) CONSUMER.getVolatile(this);
        return (int) Math.max(0, Math.min(capacity, size));
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Occupancy as a fraction, which is what the backpressure watermarks are expressed in. */
    public double fill() {
        return (double) size() / capacity;
    }

    @Override
    public String toString() {
        return "MpscLongRing[" + size() + "/" + capacity + "]";
    }
}

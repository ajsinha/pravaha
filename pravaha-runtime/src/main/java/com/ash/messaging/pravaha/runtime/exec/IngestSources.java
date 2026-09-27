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
package com.ash.messaging.pravaha.runtime.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.ingest.PartitionedIngestPump;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

/**
 * The pumps feeding one execution, and the one thing that can be said about all of them at once:
 * where they are, read between rows.
 *
 * <p>Split out of {@link QueryExecution} because it is a responsibility rather than a list. Three
 * callers need the sources held still while something is read from them -- a checkpoint's aligned
 * cut, a durable checkpoint's confirmation back to each source, and a blue/green cutover comparing
 * two versions' positions -- and each needs the same guarantee: an offset read while a pump is
 * mid-poll names a record the lanes may not have been handed, which is silent loss in one
 * direction and a silent double count in the other.
 *
 * <p>Confinement is unchanged: a pump is still driven by one thread, and this only ever takes the
 * lock {@code pumpOnce} holds for the length of its poll.
 *
 * <p><strong>A pump can join while the query runs</strong>: a source partition added after
 * registration (a Kafka topic scaled out) gets a pump of its own from the feed's thread. The lists
 * are copy-on-write so a reader iterating them never sees one half-added, and {@link #membership}
 * keeps a pump from joining between a {@link #freeze} and its {@link #thaw}: a cut taken over some
 * of the sources is exactly what freezing exists to prevent, and a pump that joined inside the
 * window would be read without having been held.
 */
final class IngestSources {

    /** The key a shuffling pump's source offset travels under. Keeps "partition-N" for the plain ones. */
    static final String SHUFFLED_OFFSET_PREFIX = "shuffled-partition-";

    /**
     * The key under which a checkpoint records which source partition the pump at an index reads,
     * as {@code partition/stream}. Beside {@code partition-N}, so a restore matches each token to its
     * partition rather than trusting that the pumps will be created in the same order: a partition
     * added while the query ran was given the next index, which after a restart may belong to
     * another stream's partition.
     */
    static final String SOURCE_OF_PREFIX = "source-of-partition-";

    private final List<IngestPump> pumps = new CopyOnWriteArrayList<>();

    /**
     * Which stream each pump reads, in the order the pumps were created.
     *
     * <p>A checkpoint keys a pump's offset by its index alone, which is enough to resume the same
     * query and not enough to compare two: a blue/green replacement meets the running version at
     * the position it has reached <em>in each stream</em>, and two plans may read the same streams
     * in a different order.
     */
    private final List<String> pumpStreams = new CopyOnWriteArrayList<>();

    /** The source partition each pump reads, or -1 where its creator did not say. */
    private final List<Integer> pumpPartitions = new CopyOnWriteArrayList<>();

    private final List<PartitionedIngestPump> partitionedPumps = new CopyOnWriteArrayList<>();

    /** Held from a {@link #freeze} to its {@link #thaw}, and by a pump joining. */
    private final ReentrantLock membership = new ReentrantLock();

    void add(IngestPump pump, String streamName) {
        add(pump, streamName, -1);
    }

    /**
     * @param sourcePartition the index of the source partition the pump reads, or -1 when unknown;
     *     recorded in each checkpoint only when every pump has one
     */
    void add(IngestPump pump, String streamName, int sourcePartition) {
        membership.lock();
        try {
            pumps.add(pump);
            pumpStreams.add(streamName);
            pumpPartitions.add(sourcePartition);
        } finally {
            membership.unlock();
        }
    }

    void add(PartitionedIngestPump pump) {
        membership.lock();
        try {
            partitionedPumps.add(pump);
        } finally {
            membership.unlock();
        }
    }

    boolean isEmpty() {
        return pumps.isEmpty();
    }

    int count() {
        return pumps.size();
    }

    int partitionedCount() {
        return partitionedPumps.size();
    }

    /**
     * How often and how long these pumps found nowhere to put a row, as {@code {episodes, nanos}}.
     *
     * <p>The query's own writers rather than its lanes' (B6). On a lane a query owns the two agree;
     * on a shared lane they come apart, and the difference is the diagnosis -- a lane's counters
     * belong to every query on it, so only these can be held against this one.
     *
     * <p>Read without freezing: both are counters the pumps maintain, and a reading taken between
     * two of a pump's polls is exactly what it says it is.
     */
    long[] backpressure() {
        long episodes = 0;
        long nanos = 0;
        for (IngestPump pump : pumps) {
            episodes += pump.backpressureWaits();
            nanos += pump.backpressureWaitNanos();
        }
        for (PartitionedIngestPump pump : partitionedPumps) {
            episodes += pump.backpressureWaits();
            nanos += pump.backpressureWaitNanos();
        }
        return new long[] {episodes, nanos};
    }

    /** Each source's offset, keyed as a checkpoint records them. Call between {@link #freeze} and {@link #thaw}. */
    void offsetsInto(Map<String, String> offsets) {
        for (int index = 0; index < pumps.size(); index++) {
            offsets.put("partition-" + index, pumps.get(index).position().token());
        }
        if (!pumpPartitions.contains(-1)) {
            for (int index = 0; index < pumps.size(); index++) {
                offsets.put(SOURCE_OF_PREFIX + index, pumpPartitions.get(index) + "/" + pumpStreams.get(index));
            }
        }
        for (int index = 0; index < partitionedPumps.size(); index++) {
            // Recorded at all, which they were not: a shuffling pump's offset was left out of
            // every checkpoint it appeared in, so a multi-lane query restored its operator state
            // and then had nothing to rewind its source with. Every row since the checkpoint was
            // replayed on top of state that had already counted it.
            offsets.put(
                    SHUFFLED_OFFSET_PREFIX + index,
                    partitionedPumps.get(index).position().token());
        }
    }

    /**
     * Where each source has got to, by stream, in partition order.
     *
     * @param streams the query's inputs, so a pump created without a stream name -- the
     *     single-input form -- is still listed under the stream it reads
     */
    Map<String, List<String>> positions(List<String> streams) {
        Map<String, List<String>> positions = new LinkedHashMap<>();
        for (int index = 0; index < pumps.size(); index++) {
            String stream = index < pumpStreams.size() && pumpStreams.get(index) != null
                    ? pumpStreams.get(index)
                    : (streams.size() == 1 ? streams.get(0) : "partition-" + index);
            positions
                    .computeIfAbsent(stream, key -> new ArrayList<>())
                    .add(pumps.get(index).position().token());
        }
        for (int index = 0; index < partitionedPumps.size(); index++) {
            positions
                    .computeIfAbsent(SHUFFLED_OFFSET_PREFIX + index, key -> new ArrayList<>())
                    .add(partitionedPumps.get(index).position().token());
        }
        return positions;
    }

    /**
     * Holds every source between rows.
     *
     * <p>For a checkpoint that is the whole of what makes it aligned: with the producers stopped, a
     * lane's marker sits at the end of everything it has been handed, the offset says exactly which
     * rows those were, and no row can arrive at one lane while another is still being marked.
     *
     * <p>Fails rather than proceeding without a source, and unwinds what it has already taken: a
     * freeze over some of the sources is the partial cut this exists to prevent.
     *
     * @return how many pumps were frozen, to pass to {@link #thaw}
     */
    long freeze(Duration timeout) {
        // Released by thaw, which every caller runs in a finally -- and which the catch below runs
        // when this fails part-way.
        membership.lock();
        long deadline = System.nanoTime() + timeout.toNanos();
        int frozen = 0;
        try {
            for (IngestPump pump : pumps) {
                if (!pump.freezeIngest(remaining(deadline))) {
                    throw new IllegalStateException("source " + frozen + " was still inside a poll after " + timeout
                            + ", so its offset cannot be read at a point between rows. A checkpoint taken "
                            + "anyway would record an offset that does not match the state the lanes hold, "
                            + "which is silent loss in one direction and a silent double count in the other; "
                            + "this one is abandoned instead.");
                }
                frozen++;
            }
            for (PartitionedIngestPump pump : partitionedPumps) {
                if (!pump.freezeIngest(remaining(deadline))) {
                    throw new IllegalStateException("shuffling source " + (frozen - pumps.size())
                            + " was still inside a poll after " + timeout + ", so its offset cannot be read at "
                            + "a point between rows; this checkpoint is abandoned rather than stored with an "
                            + "offset the lanes' state does not match.");
                }
                frozen++;
            }
            return frozen;
        } catch (RuntimeException e) {
            thaw(frozen);
            throw e;
        }
    }

    void thaw(long frozen) {
        try {
            for (long i = frozen - 1; i >= 0; i--) {
                if (i < pumps.size()) {
                    pumps.get((int) i).thawIngest();
                } else {
                    partitionedPumps.get((int) (i - pumps.size())).thawIngest();
                }
            }
        } finally {
            if (membership.isHeldByCurrentThread()) {
                membership.unlock();
            }
        }
    }

    /**
     * Tells each source the offset a now-durable checkpoint recorded for it, keyed as {@link
     * #offsetsInto} wrote them. Only after the store has the checkpoint: a source that lets go of
     * history here (a replication slot) must never let go of what a restore could still ask for.
     */
    void checkpointed(Checkpoint checkpoint) {
        for (int index = 0; index < pumps.size(); index++) {
            String token = checkpoint.offsets().get("partition-" + index);
            if (token != null) {
                pumps.get(index).checkpointed(new SourceOffset(token));
            }
        }
        for (int index = 0; index < partitionedPumps.size(); index++) {
            String token = checkpoint.offsets().get(SHUFFLED_OFFSET_PREFIX + index);
            if (token != null) {
                partitionedPumps.get(index).checkpointed(new SourceOffset(token));
            }
        }
    }

    void close() {
        pumps.forEach(IngestPump::close);
        partitionedPumps.forEach(PartitionedIngestPump::close);
    }

    private static Duration remaining(long deadline) {
        long left = deadline - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }
}

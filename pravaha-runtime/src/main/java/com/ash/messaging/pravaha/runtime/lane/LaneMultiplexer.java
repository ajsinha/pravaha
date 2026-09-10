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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.RowLayout;

/**
 * Many queries on one lane.
 *
 * <p>At the density design section 13.7 asks for -- ten thousand queries on a node sized by cores --
 * roughly three hundred query pipelines share each lane. That ratio is not a detail; it decides the
 * shape of everything here.
 *
 * <p><strong>It is a processor, not a change to the lane.</strong> The lane still runs one
 * {@link LaneProcessor} and knows nothing about queries, which keeps the loop, the arena discipline
 * and the exchange exactly as they were. Multiplexing is what that one processor does.
 *
 * <p><strong>Dispatch is by stream, never a scan.</strong> A lane iterating three hundred pipelines
 * to ask each whether this batch is for it would spend its entire budget on the two hundred and
 * ninety-nine that it is not. Rows carry a schema id in their header (design section 8.3), so the
 * batch is grouped by that and handed only to the pipelines subscribed to it. An idle query -- one
 * whose stream has no rows in this batch -- is not consulted at all, and that is what makes a
 * thousand mostly-quiet queries cost a lane almost nothing.
 *
 * <p><strong>Fan-out is zero-copy.</strong> Every pipeline subscribed to a stream is handed the same
 * region and the same offsets. A row is copied into the lane once, by the ingest pump, and never
 * again -- copying per subscribed query would make ingest cost O(queries), which is the same mistake
 * as encoding per subscriber (section 20.3b) somewhere far less visible.
 *
 * <p><strong>On quotas, a correction.</strong> The story this implements asked that a hot query be
 * held to a quota of lane batches. It cannot be, not without either dropping its rows -- which is a
 * wrong answer, not a slow one -- or copying them into a per-query backlog, which reintroduces the
 * per-query buffer the density budget rules out. What is achievable, and what is here, is that no
 * pipeline is systematically served last: pipelines run in ascending order of the lane time they
 * have already consumed, so a heavy query yields its position to lighter ones rather than
 * accumulating an advantage. Combined with per-pipeline timing, a hot query becomes identifiable and
 * bounded in its effect on latency ordering, which is what the requirement was actually protecting
 * against. Enforcing a hard ceiling needs admission control at registration -- deciding which lane a
 * query lands on -- and that belongs with the query lifecycle.
 *
 * <p>Confined to the lane thread, like everything a lane owns, except {@link #register} and
 * {@link #drop}, which a control-plane thread calls and which are therefore synchronised. Those are
 * rare; the dispatch path takes no lock.
 */
public final class LaneMultiplexer implements LaneProcessor {

    /** One query's work on one lane. */
    public record Pipeline(String queryId, int schemaId, LaneProcessor processor) {}

    /** What one pipeline has cost this lane. */
    public record PipelineMetrics(String queryId, long rowsIn, long rowsOut, long batches, long nanos) {

        /** Nanoseconds per row, which is what identifies an expensive query rather than a busy one. */
        public double nanosPerRow() {
            return rowsIn == 0 ? 0 : (double) nanos / rowsIn;
        }
    }

    private static final class Entry {
        final Pipeline pipeline;
        long rowsIn;
        long rowsOut;
        long batches;
        long nanos;

        Entry(Pipeline pipeline) {
            this.pipeline = pipeline;
        }
    }

    /** Subscribers by schema id. The dispatch index, and the reason nothing is scanned. */
    private volatile Map<Integer, List<Entry>> byStream = Map.of();

    private final Map<String, Entry> byQuery = new HashMap<>();
    private final Object registrationLock = new Object();

    /** Scratch, reused across batches: grouping must not allocate per batch on the hot path. */
    private final Map<Integer, long[]> grouped = new HashMap<>();

    private final Map<Integer, Integer> groupSizes = new HashMap<>();

    /** Adds a query's pipeline to this lane. Called from the control plane, not the lane thread. */
    public void register(Pipeline pipeline) {
        synchronized (registrationLock) {
            Entry entry = new Entry(pipeline);
            byQuery.put(pipeline.queryId(), entry);
            rebuildIndex();
        }
    }

    /** Removes a query. Its pipeline stops being consulted on the next batch. */
    public void drop(String queryId) {
        synchronized (registrationLock) {
            if (byQuery.remove(queryId) != null) {
                rebuildIndex();
            }
        }
    }

    /**
     * Rebuilds the dispatch index and republishes it as a whole.
     *
     * <p>Copy-on-write rather than a concurrent map. Registration is rare and dispatch is the hot
     * path, so the cost belongs on the rare side; and the lane thread reading a single immutable
     * snapshot cannot observe a half-applied registration, which a mutable structure would allow.
     */
    private void rebuildIndex() {
        Map<Integer, List<Entry>> rebuilt = new HashMap<>();
        for (Entry entry : byQuery.values()) {
            rebuilt.computeIfAbsent(entry.pipeline.schemaId(), key -> new ArrayList<>())
                    .add(entry);
        }
        byStream = Map.copyOf(rebuilt);
    }

    public int pipelineCount() {
        synchronized (registrationLock) {
            return byQuery.size();
        }
    }

    @Override
    public int onBatch(MemoryRegion region, long[] rowOffsets, int count) {
        Map<Integer, List<Entry>> index = byStream;
        if (index.isEmpty()) {
            return 0;
        }

        int emitted = 0;
        // The common case by a wide margin: one batch is one stream's rows, because a lane's inbox
        // is fed per stream. Handling it without grouping keeps the usual path free of the map.
        int firstSchema = schemaIdOf(region, rowOffsets[0]);
        if (isUniform(region, rowOffsets, count, firstSchema)) {
            return dispatch(index.get(firstSchema), region, rowOffsets, count);
        }

        groupSizes.clear();
        for (int i = 0; i < count; i++) {
            int schemaId = schemaIdOf(region, rowOffsets[i]);
            long[] slot = grouped.computeIfAbsent(schemaId, key -> new long[rowOffsets.length]);
            if (slot.length < count) {
                slot = new long[count];
                grouped.put(schemaId, slot);
            }
            int size = groupSizes.getOrDefault(schemaId, 0);
            slot[size] = rowOffsets[i];
            groupSizes.put(schemaId, size + 1);
        }
        for (Map.Entry<Integer, Integer> group : groupSizes.entrySet()) {
            emitted += dispatch(index.get(group.getKey()), region, grouped.get(group.getKey()), group.getValue());
        }
        return emitted;
    }

    /**
     * Hands one stream's rows to every pipeline subscribed to it.
     *
     * <p>Ordered by lane time already consumed, so a heavy pipeline yields its position rather than
     * keeping it. Sorting a handful of entries per batch is cheaper than the alternative it prevents:
     * one expensive query permanently ahead of every other query on its lane.
     */
    private int dispatch(List<Entry> subscribers, MemoryRegion region, long[] offsets, int count) {
        if (subscribers == null || subscribers.isEmpty()) {
            // Rows for a stream nothing on this lane subscribes to. Not an error: a lane receives
            // what its partitions route to it, and a query may have been dropped a moment ago.
            return 0;
        }
        List<Entry> ordered = new ArrayList<>(subscribers);
        ordered.sort(Comparator.comparingLong(entry -> entry.nanos));

        int emitted = 0;
        for (Entry entry : ordered) {
            long start = System.nanoTime();
            // Every subscriber sees the same region and the same offsets: one copy into the lane,
            // never one per query.
            int produced = entry.pipeline.processor().onBatch(region, offsets, count);
            entry.nanos += System.nanoTime() - start;
            entry.rowsIn += count;
            entry.rowsOut += produced;
            entry.batches++;
            emitted += produced;
        }
        return emitted;
    }

    private static boolean isUniform(MemoryRegion region, long[] offsets, int count, int schemaId) {
        for (int i = 1; i < count; i++) {
            if (schemaIdOf(region, offsets[i]) != schemaId) {
                return false;
            }
        }
        return true;
    }

    private static int schemaIdOf(MemoryRegion region, long rowOffset) {
        return region.getInt((int) rowOffset + RowLayout.OFFSET_SCHEMA_ID);
    }

    /** What each query has cost this lane. The number that identifies a hot query by name. */
    public List<PipelineMetrics> metrics() {
        synchronized (registrationLock) {
            return byQuery.values().stream()
                    .map(entry -> new PipelineMetrics(
                            entry.pipeline.queryId(), entry.rowsIn, entry.rowsOut, entry.batches, entry.nanos))
                    .sorted(Comparator.comparingLong(PipelineMetrics::nanos).reversed())
                    .toList();
        }
    }

    @Override
    public void close() {
        synchronized (registrationLock) {
            for (Entry entry : byQuery.values()) {
                try {
                    entry.pipeline.processor().close();
                } catch (Exception e) {
                    // One query's teardown must not strand the others' resources.
                    continue;
                }
            }
            byQuery.clear();
            byStream = Map.of();
        }
    }

    @Override
    public String toString() {
        return "LaneMultiplexer[" + pipelineCount() + " pipelines over " + byStream.size() + " streams]";
    }
}

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
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

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
 * <p><strong>Dispatch is by route, never a scan.</strong> A lane iterating three hundred pipelines
 * to ask each whether this batch is for it would spend its entire budget on the two hundred and
 * ninety-nine that it is not. Every row carries an identity in its header (design section 8.3), and
 * the batch is grouped by that and handed only to the pipelines subscribed to it. An idle query --
 * one whose routes have no rows in this batch -- is not consulted at all, and that is what makes a
 * thousand mostly-quiet queries cost a lane almost nothing.
 *
 * <p><strong>A route is either one query's or a shared ingest's (LANE-2).</strong> The identity a
 * row carried was its stream's, which assumed one ingest per stream per lane -- and each registered
 * query was fed separately, so two queries over one stream on one lane each counted the other's rows
 * (LANE-1). Now a hosted query is given a private route per input by {@link #newRoute}, and what it
 * is fed on its own -- rows pushed by an embedder, a reader of its own, a catch-up read -- is stamped
 * with that route and reaches it alone. A reader shared by several queries on this lane writes each
 * row once, stamped with a route of its own that each of those queries {@link #subscribe}s to, and
 * the fan-out below hands that one copy to all of them. A pipeline registered by stream id, as
 * before, subscribes to that id and nothing else; nothing about that case changed.
 *
 * <p><strong>Fan-out is zero-copy.</strong> Every pipeline subscribed to a route is handed the same
 * region and the same offsets. A row is copied into the lane once and never again -- copying per
 * subscribed query would make ingest cost O(queries), which is the same mistake as encoding per
 * subscriber (section 20.3b) somewhere far less visible.
 *
 * <p><strong>On quotas, a correction.</strong> The story this implements asked that a hot query be
 * held to a quota of lane batches. It cannot be, not without either dropping its rows -- which is a
 * wrong answer, not a slow one -- or copying them into a per-query backlog, which reintroduces the
 * per-query buffer the density budget rules out. What is achievable, and what is here, is that no
 * pipeline is systematically served last: pipelines run in ascending order of the lane time they
 * have already consumed, so a heavy query yields its position to lighter ones rather than
 * accumulating an advantage. A hard ceiling is admission control at registration -- the registry's
 * {@code SharedLanes}, bounded by {@code pravaha.lane.multiplex.max-queries-per-lane} (W9-8).
 *
 * <p>Confined to the lane thread, like everything a lane owns, except the registration methods --
 * {@link #register}, {@link #subscribe}, {@link #unsubscribe}, {@link #drop} -- which may be called
 * from any thread and are therefore synchronised. A caller that needs a subscription to take effect
 * at an exact row calls it from a control task on the lane, which runs after every row claimed
 * before its marker and before every row claimed after it. The dispatch path takes no lock.
 */
public final class LaneMultiplexer implements LaneProcessor {

    /** One query's work on one lane, subscribed to rows carrying {@code schemaId} on its input 0. */
    public record Pipeline(String queryId, int schemaId, LaneProcessor processor) {}

    /** What one pipeline has cost this lane. */
    public record PipelineMetrics(String queryId, long rowsIn, long rowsOut, long batches, long nanos) {

        /** Nanoseconds per row, which is what identifies an expensive query rather than a busy one. */
        public double nanosPerRow() {
            return rowsIn == 0 ? 0 : (double) nanos / rowsIn;
        }
    }

    private static final class Entry {
        final String queryId;
        final LaneProcessor processor;

        /** Route to the input its rows arrive on. Guarded by the registration lock. */
        final Map<Integer, Integer> routes = new HashMap<>();

        long rowsIn;
        long rowsOut;
        long batches;
        long nanos;

        Entry(String queryId, LaneProcessor processor) {
            this.queryId = queryId;
            this.processor = processor;
        }
    }

    /** One subscriber of one route: which pipeline, and which of its inputs the rows arrive on. */
    private record Target(Entry entry, int input) {}

    /**
     * The next private route. Counts down from -1: stream ids count up from 1, zero means unassigned
     * and {@code Integer.MIN_VALUE} marks a pre-combined partial aggregate, so none of them is ever
     * handed out here. Process-wide rather than per lane, so a route cannot mean two things on one
     * lane however it was obtained.
     */
    private static final AtomicInteger NEXT_ROUTE = new AtomicInteger(-1);

    /** Subscribers by route. The dispatch index, and the reason nothing is scanned. */
    private volatile Map<Integer, List<Target>> byRoute = Map.of();

    private final Map<String, Entry> byQuery = new HashMap<>();
    private final Object registrationLock = new Object();

    /** Scratch, reused across batches: grouping must not allocate per batch on the hot path. */
    private final Map<Integer, long[]> grouped = new HashMap<>();

    private final Map<Integer, Integer> groupSizes = new HashMap<>();

    /**
     * A route no stream and no other caller will ever be given: what a hosted query stamps on the
     * rows fed to it alone, and what a reader shared between queries on one lane stamps on its own.
     */
    public static int newRoute() {
        int route = NEXT_ROUTE.getAndDecrement();
        if (route <= Integer.MIN_VALUE + 1) {
            throw new IllegalStateException("this process has handed out every lane route there is");
        }
        return route;
    }

    /** Adds a query's pipeline to this lane, by stream id. Called from the control plane. */
    public void register(Pipeline pipeline) {
        register(pipeline.queryId(), pipeline.processor(), new int[] {pipeline.schemaId()});
    }

    /**
     * Adds a query's pipeline to this lane, subscribed to one route per input.
     *
     * @param routeOfInput the route each input's rows carry: index {@code i} is input {@code i}
     */
    public void register(String queryId, LaneProcessor processor, int[] routeOfInput) {
        for (int route : routeOfInput) {
            if (route == com.ash.messaging.pravaha.api.data.StreamSchema.UNASSIGNED_STREAM_ID) {
                // Refused rather than accepted and mis-dispatched. Zero means no stream id was ever
                // assigned, and every row whose writer had none carries zero too -- so accepting
                // this would put one pipeline in a group with every stream that is equally
                // anonymous, and hand it their rows. That is the failure W9-9 found before it could
                // happen, and the whole reason this check is louder than a log line.
                throw new IllegalArgumentException("query '" + queryId
                        + "' has no stream id, so this lane cannot tell which rows are its own. A stream is "
                        + "given an id when it joins a registry's catalogue; a schema built by hand has none.");
            }
        }
        synchronized (registrationLock) {
            Entry entry = new Entry(queryId, processor);
            for (int input = 0; input < routeOfInput.length; input++) {
                entry.routes.put(routeOfInput[input], input);
            }
            byQuery.put(queryId, entry);
            rebuildIndex();
        }
    }

    /**
     * Hands a registered query the rows carrying {@code route} too, on {@code input}.
     *
     * <p>Takes effect for the next batch the lane dispatches. Where the exact row matters -- a query
     * joining a reader it now shares, or pausing on one -- call it from a control task on the lane.
     *
     * @return false when no such query is on this lane, which is not an error: it may have been
     *     dropped between the caller deciding to subscribe it and this running
     */
    public boolean subscribe(String queryId, int route, int input) {
        synchronized (registrationLock) {
            Entry entry = byQuery.get(queryId);
            if (entry == null) {
                return false;
            }
            entry.routes.put(route, input);
            rebuildIndex();
            return true;
        }
    }

    /** Stops handing a query the rows carrying {@code route}. Its other routes are untouched. */
    public void unsubscribe(String queryId, int route) {
        synchronized (registrationLock) {
            Entry entry = byQuery.get(queryId);
            if (entry != null && entry.routes.remove(route) != null) {
                rebuildIndex();
            }
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
        Map<Integer, List<Target>> rebuilt = new HashMap<>();
        for (Entry entry : byQuery.values()) {
            entry.routes.forEach((route, input) ->
                    rebuilt.computeIfAbsent(route, key -> new ArrayList<>()).add(new Target(entry, input)));
        }
        rebuilt.replaceAll((route, targets) -> List.copyOf(targets));
        byRoute = Map.copyOf(rebuilt);
    }

    public int pipelineCount() {
        synchronized (registrationLock) {
            return byQuery.size();
        }
    }

    /** How many pipelines are subscribed to {@code route}: a shared reader's fan-out on this lane. */
    public int subscribers(int route) {
        List<Target> targets = byRoute.get(route);
        return targets == null ? 0 : targets.size();
    }

    @Override
    public int onBatch(MemoryRegion region, long[] rowOffsets, int count) {
        Map<Integer, List<Target>> index = byRoute;
        if (index.isEmpty()) {
            return 0;
        }

        int emitted = 0;
        // The common case by a wide margin: one batch is one route's rows. Handling it without
        // grouping keeps the usual path free of the map.
        int firstRoute = routeOf(region, rowOffsets[0]);
        if (isUniform(region, rowOffsets, count, firstRoute)) {
            return dispatch(index.get(firstRoute), region, rowOffsets, count);
        }

        groupSizes.clear();
        for (int i = 0; i < count; i++) {
            int route = routeOf(region, rowOffsets[i]);
            long[] slot = grouped.computeIfAbsent(route, key -> new long[rowOffsets.length]);
            if (slot.length < count) {
                slot = new long[count];
                grouped.put(route, slot);
            }
            int size = groupSizes.getOrDefault(route, 0);
            slot[size] = rowOffsets[i];
            groupSizes.put(route, size + 1);
        }
        // Rows of one route keep their order. Across routes a pipeline sees each route's group in
        // turn, which reorders only rows it receives on two routes of one batch -- a shared reader's
        // and its own catch-up's, which SharedPartitionFeed already interleaves by design and offers
        // only to sources that promise no order.
        for (Map.Entry<Integer, Integer> group : groupSizes.entrySet()) {
            emitted += dispatch(
                    index.get(group.getKey()),
                    region,
                    java.util.Objects.requireNonNull(grouped.get(group.getKey()), "grouped with its sizes"),
                    group.getValue());
        }
        return emitted;
    }

    /**
     * Hands one route's rows to every pipeline subscribed to it.
     *
     * <p>Ordered by lane time already consumed, so a heavy pipeline yields its position rather than
     * keeping it. Sorting a handful of entries per batch is cheaper than the alternative it prevents:
     * one expensive query permanently ahead of every other query on its lane.
     */
    private int dispatch(@Nullable List<Target> subscribers, MemoryRegion region, long[] offsets, int count) {
        if (subscribers == null || subscribers.isEmpty()) {
            // Rows for a route nothing on this lane subscribes to. Not an error: a query may have
            // been dropped, or paused off a shared reader, a moment ago.
            return 0;
        }
        List<Target> ordered = subscribers;
        if (subscribers.size() > 1) {
            ordered = new ArrayList<>(subscribers);
            ordered.sort(Comparator.comparingLong(target -> target.entry().nanos));
        }

        int emitted = 0;
        for (Target target : ordered) {
            Entry entry = target.entry();
            long start = System.nanoTime();
            // Every subscriber sees the same region and the same offsets: one copy into the lane,
            // never one per query.
            int produced = entry.processor.onBatch(target.input(), region, offsets, count);
            entry.nanos += System.nanoTime() - start;
            entry.rowsIn += count;
            entry.rowsOut += produced;
            entry.batches++;
            emitted += produced;
        }
        return emitted;
    }

    private static boolean isUniform(MemoryRegion region, long[] offsets, int count, int route) {
        for (int i = 1; i < count; i++) {
            if (routeOf(region, offsets[i]) != route) {
                return false;
            }
        }
        return true;
    }

    private static int routeOf(MemoryRegion region, long rowOffset) {
        return region.getInt((int) rowOffset + RowLayout.OFFSET_SCHEMA_ID);
    }

    /** What each query has cost this lane. The number that identifies a hot query by name. */
    public List<PipelineMetrics> metrics() {
        synchronized (registrationLock) {
            return byQuery.values().stream()
                    .map(entry ->
                            new PipelineMetrics(entry.queryId, entry.rowsIn, entry.rowsOut, entry.batches, entry.nanos))
                    .sorted(Comparator.comparingLong(PipelineMetrics::nanos).reversed())
                    .toList();
        }
    }

    @Override
    public void close() {
        synchronized (registrationLock) {
            for (Entry entry : byQuery.values()) {
                try {
                    entry.processor.close();
                } catch (Exception e) {
                    // One query's teardown must not strand the others' resources.
                }
            }
            byQuery.clear();
            byRoute = Map.of();
        }
    }

    @Override
    public String toString() {
        return "LaneMultiplexer[" + pipelineCount() + " pipelines over " + byRoute.size() + " routes]";
    }
}

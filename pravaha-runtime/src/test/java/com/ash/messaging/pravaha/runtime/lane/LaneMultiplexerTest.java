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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many queries on one lane.
 *
 * <p>The two properties that make ten thousand queries on a node possible are both here, and both
 * fail silently if lost. An idle query must cost its lane nothing -- otherwise three hundred quiet
 * pipelines per lane become the dominant cost and nothing looks wrong, it is just slow. And fan-out
 * must be zero-copy -- otherwise ingest cost grows with query count, which is the same mistake as
 * encoding per subscriber, somewhere far less visible.
 */
@Timeout(60)
class LaneMultiplexerTest {

    /** Writes {@code count} rows carrying {@code schemaId} into a region, returning their offsets. */
    private static long[] rows(MemoryRegion region, int schemaId, int count, long firstValue) {
        long[] offsets = new long[count];
        int stride = 64;
        for (int i = 0; i < count; i++) {
            int offset = i * stride;
            offsets[i] = offset;
            region.setMemory(offset, stride, (byte) 0);
            region.putInt(offset + RowLayout.OFFSET_SCHEMA_ID, schemaId);
            region.putLong(offset + 32, firstValue + i);
        }
        return offsets;
    }

    private static LaneMultiplexer.Pipeline counting(String queryId, int schemaId, AtomicLong rows) {
        return new LaneMultiplexer.Pipeline(queryId, schemaId, (region, offsets, count) -> {
            rows.addAndGet(count);
            return count;
        });
    }

    @Test
    void onlyTheQueriesSubscribedToAStreamAreConsulted() {
        // The property that makes a thousand mostly-quiet queries affordable. A lane that asked all
        // of them whether this batch was theirs would spend its budget on the ones it was not.
        MemoryAccess access = MemoryAccess.best();
        AtomicLong onStreamOne = new AtomicLong();
        AtomicLong onStreamTwo = new AtomicLong();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(counting("q-1", 1, onStreamOne));
        multiplexer.register(counting("q-2", 2, onStreamTwo));

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = rows(region, 1, 10, 0);
            multiplexer.onBatch(region, offsets, 10);

            assertThat(onStreamOne.get()).isEqualTo(10);
            assertThat(onStreamTwo.get())
                    .as("a query on another stream is not consulted at all")
                    .isZero();
        }
    }

    @Test
    void anIdleQueryCostsItsLaneNothingMeasurable() {
        // The acceptance criterion, stated as an invariant rather than a timing: an idle pipeline is
        // never invoked, so it cannot cost anything. Asserting "no measurable time" directly would
        // be a timing test on a shared machine, which this project has already been burned by.
        MemoryAccess access = MemoryAccess.best();
        AtomicInteger invocations = new AtomicInteger();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(new LaneMultiplexer.Pipeline("busy", 1, (region, offsets, count) -> count));
        for (int i = 0; i < 999; i++) {
            multiplexer.register(new LaneMultiplexer.Pipeline("idle-" + i, 99, (region, offsets, count) -> {
                invocations.incrementAndGet();
                return count;
            }));
        }

        try (MemoryRegion region = access.allocate(8192)) {
            long[] offsets = rows(region, 1, 64, 0);
            for (int batch = 0; batch < 100; batch++) {
                multiplexer.onBatch(region, offsets, 64);
            }
        }

        assertThat(multiplexer.pipelineCount()).isEqualTo(1000);
        assertThat(invocations.get())
                .as("999 idle pipelines across 100 batches were not invoked once")
                .isZero();
    }

    @Test
    void everySubscriberSeesTheSameRowsWithoutACopy() {
        // Zero-copy fan-out. Ten queries on one stream must see one region, not ten copies of it.
        MemoryAccess access = MemoryAccess.best();
        List<MemoryRegion> regionsSeen = new ArrayList<>();
        List<long[]> offsetsSeen = new ArrayList<>();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        for (int i = 0; i < 10; i++) {
            multiplexer.register(new LaneMultiplexer.Pipeline("q-" + i, 1, (region, offsets, count) -> {
                regionsSeen.add(region);
                offsetsSeen.add(offsets);
                return count;
            }));
        }

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = rows(region, 1, 8, 100);
            multiplexer.onBatch(region, offsets, 8);

            assertThat(regionsSeen).hasSize(10);
            assertThat(regionsSeen).allMatch(seen -> seen == region, "the identical region, not a copy");
            assertThat(offsetsSeen).allMatch(seen -> seen == offsets, "the identical offsets");
        }
    }

    @Test
    void aBatchCarryingTwoStreamsGoesToTheRightSubscribersOnly() {
        MemoryAccess access = MemoryAccess.best();
        AtomicLong first = new AtomicLong();
        AtomicLong second = new AtomicLong();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(counting("q-1", 1, first));
        multiplexer.register(counting("q-2", 2, second));

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = new long[6];
            for (int i = 0; i < 6; i++) {
                int offset = i * 64;
                offsets[i] = offset;
                region.setMemory(offset, 64, (byte) 0);
                region.putInt(offset + RowLayout.OFFSET_SCHEMA_ID, i % 2 == 0 ? 1 : 2);
            }
            multiplexer.onBatch(region, offsets, 6);

            assertThat(first.get()).isEqualTo(3);
            assertThat(second.get()).isEqualTo(3);
        }
    }

    @Test
    void anExpensiveQueryDoesNotKeepItsPlaceAtTheFrontForever() {
        // What a quota can honestly provide. Rows cannot be withheld from a query without producing
        // a wrong answer, so what is bounded is position: the pipeline that has consumed the most
        // lane time goes last, rather than an arbitrary fixed order letting one query be first every
        // single batch.
        MemoryAccess access = MemoryAccess.best();
        List<String> order = new ArrayList<>();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(new LaneMultiplexer.Pipeline("hot", 1, (region, offsets, count) -> {
            order.add("hot");
            long until = System.nanoTime() + 200_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            return count;
        }));
        multiplexer.register(new LaneMultiplexer.Pipeline("cheap", 1, (region, offsets, count) -> {
            order.add("cheap");
            return count;
        }));

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = rows(region, 1, 4, 0);
            for (int batch = 0; batch < 5; batch++) {
                multiplexer.onBatch(region, offsets, 4);
            }
        }

        assertThat(order.subList(2, order.size()))
                .as("after the first batch the cheap query is served first every time")
                .containsOnly("cheap", "hot");
        assertThat(order.get(2)).isEqualTo("cheap");
        assertThat(order.get(4)).isEqualTo("cheap");
    }

    @Test
    void perQueryCostIsAttributedByName() {
        // A lane running slowly is useless information; "query q-hot is costing 40 microseconds a
        // row" is actionable. Without attribution the symptom is "the engine is slow".
        MemoryAccess access = MemoryAccess.best();
        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(new LaneMultiplexer.Pipeline("hot", 1, (region, offsets, count) -> {
            long until = System.nanoTime() + 500_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            return count;
        }));
        multiplexer.register(new LaneMultiplexer.Pipeline("cheap", 1, (region, offsets, count) -> count));

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = rows(region, 1, 4, 0);
            multiplexer.onBatch(region, offsets, 4);
        }

        List<LaneMultiplexer.PipelineMetrics> metrics = multiplexer.metrics();
        assertThat(metrics.get(0).queryId()).as("most expensive first").isEqualTo("hot");
        assertThat(metrics.get(0).nanosPerRow()).isGreaterThan(metrics.get(1).nanosPerRow());
        assertThat(metrics).allSatisfy(m -> assertThat(m.rowsIn()).isEqualTo(4));
    }

    @Test
    void aDroppedQueryStopsBeingConsulted() {
        MemoryAccess access = MemoryAccess.best();
        AtomicLong rows = new AtomicLong();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(counting("q-1", 1, rows));

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = rows(region, 1, 4, 0);
            multiplexer.onBatch(region, offsets, 4);
            assertThat(rows.get()).isEqualTo(4);

            multiplexer.drop("q-1");
            multiplexer.onBatch(region, offsets, 4);
            assertThat(rows.get()).as("a dropped query sees nothing more").isEqualTo(4);
            assertThat(multiplexer.pipelineCount()).isZero();
        }
    }

    @Test
    void rowsForAStreamNobodySubscribesToAreNotAnError() {
        // A lane receives whatever its partitions route to it, and a query may have been dropped a
        // moment ago. Throwing here would turn an ordinary race into an outage.
        MemoryAccess access = MemoryAccess.best();
        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register(new LaneMultiplexer.Pipeline("q-1", 1, (region, offsets, count) -> count));

        try (MemoryRegion region = access.allocate(4096)) {
            long[] offsets = rows(region, 42, 4, 0);
            assertThat(multiplexer.onBatch(region, offsets, 4)).isZero();
        }
    }

    /** A processor recording which input each row arrived on. */
    private static LaneProcessor recording(List<Integer> inputs) {
        return new LaneProcessor() {
            @Override
            public int onBatch(MemoryRegion region, long[] offsets, int count) {
                return onBatch(0, region, offsets, count);
            }

            @Override
            public int onBatch(int input, MemoryRegion region, long[] offsets, int count) {
                for (int i = 0; i < count; i++) {
                    inputs.add(input);
                }
                return count;
            }
        };
    }

    @Test
    void aPrivateRouteReachesItsOwnQueryAloneAndASharedRouteReachesEveryListener() {
        // LANE-2. Two queries over one stream on one lane: what each is fed on its own carries its
        // own route and reaches it alone, and the one copy a shared reader writes reaches both.
        MemoryAccess access = MemoryAccess.best();
        List<Integer> first = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        int firstOwn = LaneMultiplexer.newRoute();
        int secondOwn = LaneMultiplexer.newRoute();
        int shared = LaneMultiplexer.newRoute();

        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register("first", recording(first), new int[] {firstOwn});
        multiplexer.register("second", recording(second), new int[] {secondOwn});
        assertThat(multiplexer.subscribe("first", shared, 0)).isTrue();
        assertThat(multiplexer.subscribe("second", shared, 0)).isTrue();
        assertThat(multiplexer.subscribers(shared)).isEqualTo(2);

        try (MemoryRegion region = access.allocate(8192)) {
            multiplexer.onBatch(region, rows(region, firstOwn, 3, 0), 3);
            assertThat(first).hasSize(3);
            assertThat(second)
                    .as("the other query's own rows are not this one's")
                    .isEmpty();

            multiplexer.onBatch(region, rows(region, shared, 5, 0), 5);
            assertThat(first).hasSize(8);
            assertThat(second).as("one copy, handed to every listener").hasSize(5);

            multiplexer.unsubscribe("second", shared);
            multiplexer.onBatch(region, rows(region, shared, 2, 0), 2);
            assertThat(first).hasSize(10);
            assertThat(second)
                    .as("stopped listening, so no longer handed the shared copy")
                    .hasSize(5);
        }
        assertThat(multiplexer.subscribe("gone", shared, 0))
                .as("a query dropped before its subscription ran is not an error")
                .isFalse();
    }

    @Test
    void aJoinsTwoInputsArriveOnTheirOwnInputsThroughOneInbox() {
        MemoryAccess access = MemoryAccess.best();
        List<Integer> inputs = new ArrayList<>();
        int left = LaneMultiplexer.newRoute();
        int right = LaneMultiplexer.newRoute();
        LaneMultiplexer multiplexer = new LaneMultiplexer();
        multiplexer.register("join", recording(inputs), new int[] {left, right});

        try (MemoryRegion region = access.allocate(8192)) {
            multiplexer.onBatch(region, rows(region, right, 2, 0), 2);
            multiplexer.onBatch(region, rows(region, left, 1, 0), 1);
        }
        assertThat(inputs).containsExactly(1, 1, 0);
    }

    @Test
    void routesAreNeverAStreamIdNorTheUnassignedIdNorAPartialsMarker() {
        for (int i = 0; i < 100; i++) {
            int route = LaneMultiplexer.newRoute();
            assertThat(route).isNegative().isNotEqualTo(Integer.MIN_VALUE);
        }
    }
}

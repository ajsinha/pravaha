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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;

/**
 * LANE-2 as a property: whatever a script does to N queries over one source -- inserts,
 * retractions, pauses, resumes, drops, late joins and a checkpoint-and-restart -- the queries
 * hosted on shared lanes, sharing the lane's one copy of the source, answer exactly as the same
 * queries on lanes of their own do, and both answer what the store says.
 *
 * <p>Few tries, because each is two engines and several settles; the examples in {@link
 * SharedLaneIngestTest} pin the shapes and this varies their order.
 */
class SharedLaneIngestPropertyTest {

    /** One step of a script. Indices are into {@link LaneEquivalence#POOL}. */
    sealed interface Step {}

    record Insert(int rows) implements Step {}

    record Retract(int rows) implements Step {}

    record Pause(int query) implements Step {}

    record Resume(int query) implements Step {}

    record Drop(int query) implements Step {}

    record Join(int query) implements Step {}

    record Restart() implements Step {}

    @Provide
    Arbitrary<List<Step>> scripts() {
        int pool = LaneEquivalence.POOL.size();
        Arbitrary<Step> step = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, Arbitraries.integers().between(1, 25).map(Insert::new)),
                net.jqwik.api.Tuple.of(2, Arbitraries.integers().between(1, 6).map(Retract::new)),
                net.jqwik.api.Tuple.of(
                        2, Arbitraries.integers().between(0, pool - 1).map(Pause::new)),
                net.jqwik.api.Tuple.of(
                        2, Arbitraries.integers().between(0, pool - 1).map(Resume::new)),
                net.jqwik.api.Tuple.of(
                        1, Arbitraries.integers().between(0, pool - 1).map(Drop::new)),
                net.jqwik.api.Tuple.of(
                        2, Arbitraries.integers().between(0, pool - 1).map(Join::new)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(new Restart())));
        return step.list().ofMinSize(3).ofMaxSize(9);
    }

    @Property(tries = 40, shrinking = ShrinkingMode.OFF)
    void queriesSharingALanesIngestAnswerExactlyAsOnLanesOfTheirOwn(
            @ForAll("scripts") List<Step> script, @ForAll("laneCounts") int sharedLanes) {
        CountingScanPlugin.reset();
        try (LaneEquivalence engines = new LaneEquivalence(sharedLanes)) {
            // Three queries to start with, so every script has something to share.
            engines.register(LaneEquivalence.POOL.get(0));
            engines.register(LaneEquivalence.POOL.get(1));
            engines.register(LaneEquivalence.POOL.get(5));
            int nextId = 1;
            List<long[]> retractable = new ArrayList<>();
            for (Step step : script) {
                switch (step) {
                    case Insert insert -> {
                        for (int i = 0; i < insert.rows(); i++, nextId++) {
                            long amount = (nextId * 37L) % 400;
                            CountingScanPlugin.append(nextId, amount);
                            retractable.add(new long[] {nextId, amount});
                        }
                    }
                    case Retract retract -> {
                        for (int i = 0; i < retract.rows() && !retractable.isEmpty(); i++) {
                            long[] record = retractable.remove(retractable.size() / 2);
                            CountingScanPlugin.retract(record[0], record[1]);
                        }
                    }
                    case Pause pause -> {
                        String name = name(pause.query());
                        if (engines.registered.containsKey(name) && !engines.pausedAt.containsKey(name)) {
                            engines.pause(name);
                        }
                    }
                    case Resume resume -> {
                        String name = name(resume.query());
                        if (engines.pausedAt.containsKey(name)) {
                            engines.settle();
                            engines.resume(name);
                        }
                    }
                    case Drop drop -> {
                        String name = name(drop.query());
                        if (engines.registered.containsKey(name) && engines.registered.size() > 1) {
                            engines.drop(name);
                        }
                    }
                    case Join join -> {
                        String name = name(join.query());
                        if (!engines.registered.containsKey(name)) {
                            engines.settle();
                            engines.register(LaneEquivalence.POOL.get(join.query()));
                        }
                    }
                    case Restart restart -> engines.restart();
                }
            }
            engines.settle();
        }
    }

    @Provide
    Arbitrary<Integer> laneCounts() {
        return Arbitraries.integers().between(1, 3);
    }

    private static String name(int query) {
        return LaneEquivalence.POOL.get(query).name();
    }
}

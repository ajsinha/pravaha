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
package com.ash.messaging.pravaha.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs concurrent-looking work on one thread, in a seeded order.
 *
 * <p>This is what makes the whole suite reproducible. In production the engine has real threads
 * whose interleaving is decided by the OS; a bug that depends on one particular interleaving may
 * appear once in ten thousand runs and never on the machine debugging it. Here, interleaving is a
 * seed: the same seed always produces the same order, and varying the seed sweeps the space
 * deliberately rather than hoping.
 *
 * <p>A {@link Step} reports whether it made progress. The scheduler picks a runnable step at random
 * from those still able to progress, runs it once, and repeats until none can. That is enough to
 * shake out ordering assumptions without needing real concurrency, and it fails the same way every
 * time -- which is the entire point.
 */
public final class DeterministicScheduler {

    /** Guards against a step that always claims progress. */
    private static final int DEFAULT_MAX_ITERATIONS = 1_000_000;

    /** One unit of schedulable work. */
    @FunctionalInterface
    public interface Step {
        /**
         * Performs at most one unit of work.
         *
         * @return {@code true} if something happened. Returning {@code true} forever will trip the
         *     iteration guard rather than hang the suite.
         */
        boolean runOnce();
    }

    private final Map<String, Step> steps = new LinkedHashMap<>();
    private final long seed;
    private final int maxIterations;
    private final List<String> trace = new ArrayList<>();
    private boolean recordTrace;

    public DeterministicScheduler(long seed) {
        this(seed, DEFAULT_MAX_ITERATIONS);
    }

    public DeterministicScheduler(long seed, int maxIterations) {
        this.seed = seed;
        this.maxIterations = maxIterations;
    }

    /** Registers a named step. Names appear in the trace, so make them meaningful. */
    public DeterministicScheduler register(String name, Step step) {
        if (steps.putIfAbsent(name, step) != null) {
            throw new IllegalArgumentException("a step named '" + name + "' is already registered");
        }
        return this;
    }

    /** Records which step ran at each turn. Off by default; it allocates per iteration. */
    public DeterministicScheduler recordTrace() {
        this.recordTrace = true;
        return this;
    }

    /**
     * Runs until no step can make progress.
     *
     * @return how many steps were executed
     */
    public int runToCompletion() {
        Random random = new Random(seed);
        List<String> names = new ArrayList<>(steps.keySet());
        List<String> runnable = new ArrayList<>(names.size());
        int executed = 0;

        while (executed < maxIterations) {
            runnable.clear();
            // Try steps in a seeded order until one makes progress. Restarting the shuffle each
            // turn is what produces genuinely varied interleavings rather than a fixed rotation.
            shuffleInto(names, runnable, random);

            boolean progressed = false;
            for (String name : runnable) {
                if (steps.get(name).runOnce()) {
                    executed++;
                    progressed = true;
                    if (recordTrace) {
                        trace.add(name);
                    }
                    break;
                }
            }
            if (!progressed) {
                return executed;
            }
        }
        throw new IllegalStateException("scheduler exceeded " + maxIterations + " iterations with seed " + seed
                + "; a step is probably reporting progress forever");
    }

    private static void shuffleInto(List<String> source, List<String> target, Random random) {
        target.addAll(source);
        for (int i = target.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = target.get(i);
            target.set(i, target.get(j));
            target.set(j, tmp);
        }
    }

    /** The order steps ran in, when {@link #recordTrace()} was enabled. */
    public List<String> trace() {
        return List.copyOf(trace);
    }

    public long seed() {
        return seed;
    }

    @Override
    public String toString() {
        return "DeterministicScheduler[seed=" + seed + ", steps=" + steps.keySet() + "]";
    }
}

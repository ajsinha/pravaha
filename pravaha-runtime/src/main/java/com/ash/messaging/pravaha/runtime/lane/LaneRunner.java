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

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.ash.messaging.pravaha.common.queue.WaitStrategy;

/**
 * A fixed set of threads driving many lanes, so a node's thread count follows its cores rather than
 * its queries.
 *
 * <p>ADR-027, and the reason a node could hold tens of continuous queries and not thousands. A lane
 * owned its thread, so a thousand registrations were a thousand platform threads -- measured at
 * exactly 1.00 per query by {@code NodeScaleTest}. What made that look unavoidable is that thread
 * confinement is this engine's whole correctness model: a lane's arena, operator state and inbox
 * cursors have no locks because exactly one thread touches them.
 *
 * <p><strong>Confinement does not require a thread per lane. It requires one thread per lane at a
 * time.</strong> A runner owns a set of lanes and steps each in turn on its own thread, so every
 * lane still has a single driver and every ordering rule inside {@link Lane} still holds. What
 * changes is that one driver drives many, which is the event-loop shape -- the same reason Netty
 * carries thousands of sockets on a handful of threads.
 *
 * <p>A pool that <em>moved</em> a lane between threads would break this, and so would a pool that
 * ran two lanes of one runner concurrently. Neither happens here: a lane belongs to one runner
 * thread from the moment it is hosted until it is removed, and a runner thread steps its lanes
 * sequentially.
 *
 * <h2>One lane's failure is one query's failure</h2>
 *
 * <p>The property that matters most, and the one a shared thread most easily loses. When a lane
 * owned its thread, a throw killed that thread and that query. On a shared thread a throw that
 * escaped would kill every query the runner was carrying -- turning one bad row into an outage.
 *
 * <p>So a step that throws is caught here, recorded on the lane that threw it, and that lane alone
 * is dropped. The others keep running and never learn of it, which is what they would have done
 * before.
 */
public final class LaneRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LaneRunner.class.getName());

    /** Runner threads, when the caller does not say. One per core, which is what the work is. */
    public static int defaultThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private final Worker[] workers;
    private final AtomicInteger nextWorker = new AtomicInteger();
    private volatile boolean closed;

    public LaneRunner(int threads, String namePrefix, WaitStrategy.Kind idleStrategy) {
        if (threads < 1) {
            throw new IllegalArgumentException("a runner needs at least one thread, got " + threads);
        }
        this.workers = new Worker[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Worker(namePrefix + "-" + i, idleStrategy);
            workers[i].start();
        }
    }

    /**
     * Takes over driving {@code lane}, which must not have been started.
     *
     * <p>Assigned round-robin. Nothing here balances by load: a lane's cost is not knowable at
     * registration, and a scheme that guessed would move lanes between threads to correct itself,
     * which is the one thing confinement forbids. Rebalancing needs a way to hand a lane over
     * safely and that is not built.
     */
    public void host(Lane lane) {
        if (closed) {
            throw new IllegalStateException("this runner is closed and cannot take lane " + lane.laneId());
        }
        workers[Math.floorMod(nextWorker.getAndIncrement(), workers.length)].add(lane);
    }

    /** How many lanes each worker currently drives. For a node reporting how it is loaded. */
    public List<Integer> lanesPerThread() {
        List<Integer> counts = new java.util.ArrayList<>(workers.length);
        for (Worker worker : workers) {
            counts.add(worker.lanes.size());
        }
        return List.copyOf(counts);
    }

    /** Runner threads. Fixed at construction: this is the number that does not grow with queries. */
    public int threads() {
        return workers.length;
    }

    @Override
    public void close() {
        closed = true;
        for (Worker worker : workers) {
            worker.stopping = true;
            worker.thread.interrupt();
        }
        for (Worker worker : workers) {
            try {
                worker.thread.join(java.time.Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One thread, and the lanes it drives. */
    private static final class Worker {

        private final CopyOnWriteArrayList<Lane> lanes = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<Lane> arriving = new ConcurrentLinkedQueue<>();
        private final WaitStrategy waitStrategy;
        private final Thread thread;
        private volatile boolean stopping;

        Worker(String name, WaitStrategy.Kind idleStrategy) {
            this.waitStrategy = idleStrategy.strategy();
            this.thread = new Thread(this::run, name);
            // Daemon, as a lane's own thread was: a runner must never be why a JVM will not exit.
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void add(Lane lane) {
            // Queued rather than added directly: the runner thread owns its list, and a lane that
            // appeared mid-iteration would be stepped before whoever registered it had finished
            // wiring it up.
            arriving.add(lane);
        }

        private void run() {
            try {
                loop();
            } finally {
                // Every lane still hosted when this thread stops is finished here, on this thread.
                // A hosted lane's last step releases its arena and inbox, and only its runner may
                // take that step -- so a runner that exited leaving lanes un-stepped would leave
                // them permanently unable to close, and Lane.close() would time out blaming a stall
                // that had already happened. Found by a test that closed the runner first, which is
                // the order a caller will reach for.
                finishRemaining();
            }
        }

        private void loop() {
            int idle = 0;
            while (!stopping) {
                for (Lane arrival = arriving.poll(); arrival != null; arrival = arriving.poll()) {
                    lanes.add(arrival);
                }
                boolean worked = false;
                for (Lane lane : lanes) {
                    if (!step(lane)) {
                        lanes.remove(lane);
                        continue;
                    }
                    worked |= lane.didWorkLastStep();
                }
                if (worked) {
                    idle = 0;
                } else {
                    // Every lane had nothing. Park once for the whole runner rather than once per
                    // lane: a runner carrying a thousand idle queries must not wake a thousand
                    // times to discover that each of them is still idle.
                    waitStrategy.idle(++idle);
                }
            }
        }

        /** Steps each remaining lane to completion, so it can be closed after this thread is gone. */
        private void finishRemaining() {
            for (Lane lane : lanes) {
                try {
                    lane.stopForRunnerShutdown();
                    // Bounded: a lane whose processor will not return cannot be waited on for ever
                    // by a shutdown. It stays un-drained and Lane.close() reports the timeout, which
                    // is the honest outcome and names the right cause.
                    for (int step = 0; step < 1_000 && lane.pumpOnce(); step++) {
                        // Stepping until it reports it is done.
                    }
                } catch (Throwable failure) {
                    lane.recordFailure(failure);
                }
            }
            lanes.clear();
        }

        /**
         * One step of one lane, with its failure kept to itself.
         *
         * @return whether the lane wants another step; {@code false} means drop it
         */
        private boolean step(Lane lane) {
            try {
                return lane.pumpOnce();
            } catch (Throwable failure) {
                // Recorded on the lane, so checkHealth() and RegisteredQuery.state() find it exactly
                // as they did when the lane owned the thread that died. Then dropped, and the other
                // lanes on this thread carry on knowing nothing about it -- which is the difference
                // between one query failing and every query on this runner failing with it.
                lane.recordFailure(failure);
                LOG.log(
                        System.Logger.Level.WARNING,
                        "lane " + lane.laneId() + " failed and was dropped from this runner; the other lanes it "
                                + "shares a thread with are unaffected",
                        failure);
                return false;
            }
        }
    }
}

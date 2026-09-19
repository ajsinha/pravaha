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
package com.ash.messaging.pravaha.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;

/**
 * Publishes what each continuous query is doing, so an operator can see it without attaching a
 * debugger.
 *
 * <p>The actuator endpoints were configured to expose Prometheus from the start and had nothing of
 * Pravaha's to expose: a deployment could see JVM heap and HTTP latencies while the thing the
 * process exists to do was entirely dark.
 *
 * <p><strong>Meters are removed when their query is dropped.</strong> That is most of the work here
 * and the reason this is a component rather than three lines in a configuration class. A gauge
 * registered per query and never removed is a slow leak with a second failure behind it: the gauge
 * holds a reference to the query, so the query's state cannot be collected either, and a deployment
 * that registers and drops queries all day accumulates both. Micrometer will not notice, because as
 * far as it is concerned a meter that exists is a meter somebody wanted.
 *
 * <p>The gauges hold the query only weakly, so a drop that this has not yet noticed cannot keep a
 * view's state alive in the meantime.
 *
 * <p>What is published is deliberately small: rows in, view size, what retention has evicted, and how
 * far behind event time the query is. Between them they answer the three questions an operator
 * actually asks -- is it running, is it keeping up, and is it growing.
 */
@Component
public class PravahaMetrics implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PravahaMetrics.class);

    /** How often the set of meters is reconciled with the set of queries. */
    private static final long SYNC_SECONDS = 15;

    private final MeterRegistry meters;
    private final PravahaNode node;
    private final Map<String, List<Meter.Id>> published = new ConcurrentHashMap<>();
    private final List<Meter.Id> laneMeters = new ArrayList<>();
    private final ScheduledExecutorService scheduler;

    public PravahaMetrics(MeterRegistry meters, PravahaNode node) {
        this.meters = meters;
        this.node = node;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-metrics");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::syncQuietly, SYNC_SECONDS, SYNC_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Brings the published meters in line with the registered queries.
     *
     * <p>Both directions matter. New queries gain meters, and -- the part that is easy to forget --
     * dropped queries lose theirs.
     */
    public synchronized void sync() {
        QueryRegistry registry = node.registry().orElse(null);
        if (registry == null) {
            return;
        }
        publishLaneSharing(registry);
        Set<String> live = registry.names();

        for (String name : live) {
            if (published.containsKey(name)) {
                continue;
            }
            registry.find(name).ifPresent(query -> publish(name, query));
        }

        List<String> gone =
                published.keySet().stream().filter(name -> !live.contains(name)).toList();
        for (String name : gone) {
            List<Meter.Id> ids = published.remove(name);
            if (ids != null) {
                // Removing the meter is what releases the reference to the query behind it.
                ids.forEach(meters::remove);
            }
        }
        if (!gone.isEmpty()) {
            log.debug("removed meters for {} dropped queries", gone.size());
        }
    }

    private void publish(String name, RegisteredQuery query) {
        Tags tags = Tags.of("query", name);
        List<Meter.Id> ids = new ArrayList<>();

        ids.add(gauge("pravaha.query.rows.in", tags, query, RegisteredQuery::rowsIn));
        // The three that make a ceiling visible before it is reached. A query refused with PRV-4001
        // used to be the first anyone heard of its state, and the query at nine tenths of the way
        // there -- the one still worth acting on -- could not be seen at all (ADR-037).
        ids.add(gauge(
                "pravaha.query.state.held", tags, query, q -> q.stateUsage().held()));
        ids.add(gauge(
                "pravaha.query.state.ceiling", tags, query, q -> q.stateUsage().ceiling()));
        ids.add(gauge(
                "pravaha.query.state.fraction", tags, query, q -> q.stateUsage().fraction()));
        // The overflow tier (ADR-044): what the query's state holds on disk, how much of that is
        // live, and whether compaction is keeping the two close. All zero until it spills.
        ids.add(gauge(
                "pravaha.query.spill.bytes",
                tags,
                query,
                q -> q.spillStatistics().overflowBytesReserved()));
        ids.add(gauge(
                "pravaha.query.spill.live.bytes",
                tags,
                query,
                q -> q.spillStatistics().overflowBytesLive()));
        ids.add(gauge(
                "pravaha.query.spill.fragmentation",
                tags,
                query,
                q -> q.spillStatistics().fragmentation()));
        ids.add(gauge(
                "pravaha.query.spill.compactions",
                tags,
                query,
                q -> q.spillStatistics().compactions()));
        ids.add(gauge(
                "pravaha.query.spill.slabs.released",
                tags,
                query,
                q -> q.spillStatistics().slabsReleased()));
        ids.add(gauge("pravaha.query.view.size", tags, query, q -> q.view().size()));
        // Rising steadily is retention doing its job. Flat at zero on a long-running query means
        // either nothing is old enough yet or the retention is longer than anyone intended.
        ids.add(gauge("pravaha.query.view.evicted", tags, query, q -> q.view().evicted()));
        ids.add(gauge("pravaha.query.view.updates", tags, query, q -> q.view().updates()));
        ids.add(gauge("pravaha.query.view.removals", tags, query, q -> q.view().removals()));
        // Event-time lag, not processing latency. A query can be fast and still far behind, because
        // this measures the data rather than the engine.
        ids.add(gauge("pravaha.query.watermark.lag.seconds", tags, query, PravahaMetrics::lagSeconds));
        ids.add(gauge("pravaha.query.running", tags, query, q -> q.state().isTerminal() ? 0 : 1));
        // Subscribers attached to the computation this name answers to. A sink writing its changelog
        // listens on the same commit and is not counted: a query writing to a table has nobody
        // watching it. Two names on one computation report the same number, because they are one.
        ids.add(gauge("pravaha.query.subscribers", tags, query, RegisteredQuery::subscriberCount));
        // Checkpoint health. NaN, not zero, while the query is not checkpointing or has not yet stored
        // one: a last-success timestamp of zero reads as "1970", which an age alert would page on.
        ids.add(gauge(
                "pravaha.query.checkpoint.last.success.timestamp.seconds",
                tags,
                query,
                q -> q.lastCheckpoint().map(at -> at.toEpochMilli() / 1000d).orElse(Double.NaN)));
        ids.add(gauge(
                "pravaha.query.checkpoint.duration.seconds",
                tags,
                query,
                q -> q.lastCheckpointDuration()
                        .map(took -> took.toNanos() / 1e9)
                        .orElse(Double.NaN)));
        ids.add(FunctionCounter.builder(
                        "pravaha.query.checkpoint.failures", query, q -> (double) q.checkpointFailures())
                .tags(tags)
                .register(meters)
                .getId());
        // Commit latency: the time from a commit starting to apply its changes to the last subscriber
        // and sink having them. A count and a total, so the mean over any window is exact; the engine
        // does not keep each commit's duration, so it publishes no percentiles rather than invented ones.
        ids.add(FunctionTimer.builder(
                        "pravaha.query.commit.latency",
                        query,
                        q -> q.view().timedCommits(),
                        q -> (double) q.view().commitNanosTotal(),
                        TimeUnit.NANOSECONDS)
                .tags(tags)
                .register(meters)
                .getId());

        published.put(name, ids);
    }

    /**
     * The node's lane sharing, published once: queries on each shared lane, and queries holding a
     * lane of their own (W9-8).
     *
     * <p>Per node, not per query, so these are not in {@link #published} and are not removed by a
     * drop. The shared lane count is fixed when the registry is configured, so one gauge per lane is
     * registered the first time a registry is seen and read through the node thereafter.
     */
    private void publishLaneSharing(QueryRegistry registry) {
        if (!laneMeters.isEmpty()) {
            return;
        }
        laneMeters.add(Gauge.builder("pravaha.lane.own.queries", node, PravahaMetrics::queriesOnOwnLanes)
                .register(meters)
                .getId());
        // The node's spilled state against pravaha.state.spill.max-bytes (ADR-044): every query's
        // overflow slabs together, which is what the quota counts. Zero with spilling off.
        laneMeters.add(Gauge.builder(
                        "pravaha.state.spill.bytes.mapped",
                        node,
                        n -> com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.spillBytesMapped())
                .register(meters)
                .getId());
        // What the shared lanes hold off-heap between them, once, however many queries they carry:
        // the number that lane sharing exists to keep small (LANE-2).
        laneMeters.add(Gauge.builder(
                        "pravaha.lane.shared.bytes",
                        node,
                        n -> n.registry().map(QueryRegistry::sharedLaneBytes).orElse(0L))
                .baseUnit("bytes")
                .register(meters)
                .getId());
        int sharedLanes = registry.pipelinesPerSharedLane().size();
        for (int i = 0; i < sharedLanes; i++) {
            int lane = i;
            laneMeters.add(Gauge.builder("pravaha.lane.shared.queries", node, n -> queriesOnSharedLane(n, lane))
                    .tags(Tags.of("lane", Integer.toString(lane)))
                    .register(meters)
                    .getId());
        }
    }

    private static double queriesOnOwnLanes(PravahaNode node) {
        return node.registry().map(QueryRegistry::queriesOnOwnLanes).orElse(0);
    }

    private static double queriesOnSharedLane(PravahaNode node, int lane) {
        return node.registry()
                .map(QueryRegistry::pipelinesPerSharedLane)
                .filter(counts -> lane < counts.size())
                .map(counts -> counts.get(lane))
                .orElse(0);
    }

    private Meter.Id gauge(
            String name, Tags tags, RegisteredQuery query, java.util.function.ToDoubleFunction<RegisteredQuery> value) {
        // Micrometer holds the object weakly, so a query dropped between syncs cannot be kept alive
        // by its own metrics.
        return Gauge.builder(name, query, value).tags(tags).register(meters).getId();
    }

    private static double lagSeconds(RegisteredQuery query) {
        return query.watermarkNanos()
                .map(watermark -> (System.currentTimeMillis() * 1_000_000d - watermark) / 1_000_000_000d)
                // No watermark yet means nothing has arrived, which is not a lag of zero. Reporting
                // zero would show a query that has never seen a row as perfectly up to date.
                .orElse(Double.NaN);
    }

    /** What is currently published, for a status endpoint or a test. */
    public Map<String, Integer> published() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        published.forEach((name, ids) -> counts.put(name, ids.size()));
        return counts;
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (RuntimeException failure) {
            // Metrics must never be the reason a node stops working.
            log.warn("could not refresh query metrics: {}", failure.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        published.values().forEach(ids -> ids.forEach(meters::remove));
        published.clear();
        laneMeters.forEach(meters::remove);
        laneMeters.clear();
    }
}

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
public final class PravahaMetrics implements AutoCloseable {

    /**
     * Where this class used to synchronize on itself. A ReentrantLock rather than a monitor,
     * because it reads the registry under it, and a registration holds the registry's lock across
     * network I/O, and on JDK 21 a virtual thread blocked inside a monitor pins its carrier
     * (ADR-062).
     */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    private static final Logger log = LoggerFactory.getLogger(PravahaMetrics.class);

    /** How often the set of meters is reconciled with the set of queries. */
    private static final long SYNC_SECONDS = 15;

    private final MeterRegistry meters;
    private final PravahaNode node;
    private final Map<String, List<Meter.Id>> published = new ConcurrentHashMap<>();
    private final List<Meter.Id> laneMeters = new ArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final com.ash.messaging.pravaha.server.tenancy.TenancyMeters tenancy;
    private final com.ash.messaging.pravaha.server.observe.FeatureMeters features;

    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    public PravahaMetrics(MeterRegistry meters, PravahaNode node) {
        this.meters = meters;
        this.node = node;
        this.tenancy = new com.ash.messaging.pravaha.server.tenancy.TenancyMeters(meters, node::registry);
        this.features = new com.ash.messaging.pravaha.server.observe.FeatureMeters(meters, node::alerts);
        // RECOVERYHEALTH-1: journalled registrations the last restart refused and nobody has dropped
        // or registered again. Non-zero is a view some client expects and will not find.
        Gauge.builder(
                        "pravaha.registry.recovery.refused",
                        node,
                        n -> n.registry()
                                .map(registry ->
                                        registry.refusedAtRecovery().all().size())
                                .orElse(0))
                .description("Journalled registrations refused at recovery, listed FAILED until dropped")
                .register(meters);
        // READADMIT-1: the reads the node's admission refused, by which of the three limits, and the
        // reads running now. Zero while pravaha.serving.read.max-concurrent is 0 (every read admitted).
        java.util.Map.<String, java.util.function.ToDoubleFunction<PravahaNode>>of(
                        "rejected", n -> n.readAdmission().rejectedCount(),
                        "queue_timed_out", n -> n.readAdmission().queueTimedOutCount(),
                        "tenant_share", n -> n.readAdmission().tenantRejectedCount())
                .forEach((reason, count) -> io.micrometer.core.instrument.FunctionCounter.builder(
                                "pravaha.read.refused", node, count)
                        .description("Reads refused by admission (PRV-4026 rejected, 4027 queue_timed_out, "
                                + "4028 tenant_share)")
                        .tags("reason", reason)
                        .register(meters));
        Gauge.builder("pravaha.read.in_flight", node, n -> n.readAdmission().inFlight())
                .description("Reads holding an admission permit now")
                .register(meters);
        // AUDITROTATE-1: decisions the durable audit sink accepted and did not record, and whether it
        // is failing now. Non-zero / 1 is an audit trail with a gap in it.
        io.micrometer.core.instrument.FunctionCounter.builder(
                        "pravaha.audit.unrecorded",
                        node,
                        n -> n.auditTrail()
                                .map(com.ash.messaging.pravaha.security.AuditSink::unrecorded)
                                .orElse(0L))
                .description("Audit decisions the durable sink accepted and did not record (dropped or lost)")
                .register(meters);
        Gauge.builder(
                        "pravaha.audit.failing",
                        node,
                        n -> n.auditTrail()
                                        .flatMap(com.ash.messaging.pravaha.security.AuditSink::failure)
                                        .isPresent()
                                ? 1
                                : 0)
                .description("1 while the durable audit sink is not recording or cannot rotate; health is DEGRADED")
                .register(meters);
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
    public void sync() {
        lock.lock();
        try {
            QueryRegistry registry = node.registry().orElse(null);
            if (registry == null) {
                return;
            }
            publishLaneSharing(registry);
            tenancy.sync(registry);
            // Alerts (ADR-057) and the catalogue (ADR-059): their meters follow the alerts that exist.
            features.sync(registry);
            Set<String> live = registry.names();
            // Before the per-query loop below, so a lane whose only query was just registered has a
            // representative by the time its gauge is next read.
            rememberLaneRepresentatives(registry);

            for (String name : live) {
                if (published.containsKey(name)) {
                    continue;
                }
                registry.find(name).ifPresent(query -> publish(name, query));
            }

            List<String> gone = published.keySet().stream()
                    .filter(name -> !live.contains(name))
                    .toList();
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
        } finally {
            lock.unlock();
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
        // Backpressure, which was measured nowhere at all: rejectedOffers counted refusals with no
        // time in them, so a source held off for an hour and a source refused twice in an hour
        // reported the same. These three are the time.
        ids.add(FunctionCounter.builder(
                        "pravaha.query.backpressure.waits", query, q -> (double) q.pumpBackpressure()[0])
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(FunctionCounter.builder(
                        "pravaha.query.backpressure.wait.seconds", query, q -> q.pumpBackpressure()[1] / 1e9)
                .tags(tags)
                .baseUnit("seconds")
                .register(meters)
                .getId());
        // The lane's view rather than the query's: on a shared lane this counts every writer into
        // the lane, so a query blocked by a neighbour reads high here and low on the two above.
        // **The one to alert on** for "is this query the limit" -- near 1 means it is.
        ids.add(gauge(
                "pravaha.query.backpressure.blocked.fraction",
                tags,
                query,
                q -> q.backpressure().blockedFraction()));
        ids.add(gauge(
                "pravaha.query.inbox.depth", tags, query, q -> q.backpressure().inboxDepth()));
        ids.add(gauge(
                "pravaha.query.inbox.cells", tags, query, q -> q.backpressure().inboxCells()));
        ids.add(gauge("pravaha.query.view.size", tags, query, q -> q.view().size()));
        // Rising steadily is retention doing its job. Flat at zero on a long-running query means
        // either nothing is old enough yet or the retention is longer than anyone intended.
        ids.add(gauge("pravaha.query.view.evicted", tags, query, q -> q.view().evicted()));
        ids.add(gauge("pravaha.query.view.updates", tags, query, q -> q.view().updates()));
        ids.add(gauge("pravaha.query.view.removals", tags, query, q -> q.view().removals()));
        // Event-time lag, not processing latency. A query can be fast and still far behind, because
        // this measures the data rather than the engine.
        ids.add(gauge("pravaha.query.watermark.lag.seconds", tags, query, PravahaMetrics::lagSeconds));
        // TIME-8, and why the lag gauge alone was not enough. Lag says the watermark is behind; it
        // does not say whether that is one quiet partition holding everything back, a partition
        // that keeps going quiet, or a source reporting time that runs backwards -- and those need
        // different responses. WatermarkTracker has answered all three from the start
        // (`isIdle`, `idleExclusions()`, `regressions()`) and had no caller outside itself, so on a
        // live node a stalled query and a healthy one were indistinguishable on every shipped
        // surface: `pravaha queries` shows rows in, /actuator/prometheus had seven gauges and none
        // of these, /api/v1/status has none.
        //
        // Partitions is published beside them because the pair has to be read together: "one
        // excluded" means nothing without "of how many", and zero partitions is a query that
        // derives no watermarks at all rather than one with none idle.
        ids.add(gauge(
                "pravaha.query.watermark.partitions",
                tags,
                query,
                q -> q.watermarkDiagnostics().partitions()));
        ids.add(gauge(
                "pravaha.query.watermark.partitions.idle",
                tags,
                query,
                q -> q.watermarkDiagnostics().idleNow()));
        // Counters, because what matters is that it happened at all: a partition that was excluded
        // once and came back is invisible in the gauge above a second later, and it is the reason
        // a window fired early and a row arrived late.
        ids.add(FunctionCounter.builder("pravaha.query.watermark.idle.exclusions", query, q ->
                        (double) q.watermarkDiagnostics().idleExclusions())
                .tags(tags)
                .register(meters)
                .getId());
        // A source-side fault, counted and never silently applied: the watermark never moves
        // backwards, so a partition that regresses is one whose rows will arrive late from here on.
        ids.add(FunctionCounter.builder("pravaha.query.watermark.regressions", query, q ->
                        (double) q.watermarkDiagnostics().regressions())
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(gauge("pravaha.query.running", tags, query, q -> q.state().isTerminal() ? 0 : 1));
        // FEED-1. A query whose source failed mid-read stays RUNNING -- so `running` above says 1 --
        // and its view stops moving. This is the number to alert on: 1 while any of its sources has
        // stopped, which is not retried, so it stays 1 until the query is re-registered.
        ids.add(gauge(
                "pravaha.query.feed.stopped", tags, query, q -> q.feedStatus().stopped() ? 1 : 0));
        // Distinct failures that stopped it, not stopped partitions: one failure on a thread reading
        // four partitions stops four and is counted once. A counter, because a stop is never undone.
        ids.add(FunctionCounter.builder("pravaha.query.feed.failures", query, q ->
                        (double) q.feedStatus().failures())
                .tags(tags)
                .register(meters)
                .getId());
        // B5. DeadLetterRate existed in the runtime and was wired to nothing: a node could reject
        // a third of a feed's records and publish not one number about it, so a dashboard's only
        // clue was rows_in being lower than somebody expected.
        //
        // Read from the writer's own counters rather than from the file. A gauge is scraped every
        // fifteen seconds, and one that answered by walking a queue of up to a quarter of a
        // gigabyte would make watching the queue more expensive than filling it.
        //
        // Depth first, because it is the one to alert on: what is sitting there now, after
        // retention and after whatever has been replayed. A rising total with a flat depth is
        // somebody keeping on top of it; a rising depth is the finding.
        ids.add(gauge(
                "pravaha.query.dead.letters",
                tags,
                query,
                q -> deadLetters(name).depth()));
        ids.add(gauge(
                "pravaha.query.dead.letters.bytes",
                tags,
                query,
                q -> deadLetters(name).bytes()));
        // What retention took and will not give back. Beside the depth because a depth without it
        // cannot be read: a queue steady at two thousand is either one bad afternoon or a bound
        // throwing two thousand a minute away, and those need opposite responses.
        ids.add(FunctionCounter.builder("pravaha.query.dead.letters.evicted", query, q ->
                        (double) deadLetters(name).evicted())
                .tags(tags)
                .register(meters)
                .getId());
        // Entries the queue itself could not write. Non-zero means the DLQ needs attention before
        // the records in it do -- these records are gone and nothing else says so.
        ids.add(FunctionCounter.builder("pravaha.query.dead.letters.write.failures", query, q ->
                        (double) deadLetters(name).writeFailures())
                .tags(tags)
                .register(meters)
                .getId());
        // The rate, which is what DeadLetterRate is for: a steady trickle from one partner is
        // Tuesday, and the same feed rejecting a third of its records is a schema change nobody
        // announced. Zero until the window has seen enough records for a share to mean anything.
        ids.add(gauge(
                "pravaha.query.dead.letters.fraction",
                tags,
                query,
                q -> deadLetters(name).rejectedFraction()));
        ids.add(gauge(
                "pravaha.query.dead.letters.degraded",
                tags,
                query,
                q -> deadLetters(name).degraded() ? 1 : 0));
        // Subscribers attached to the computation this name answers to. A sink writing its changelog
        // listens on the same commit and is not counted: a query writing to a table has nobody
        // watching it. Two names on one computation report the same number, because they are one.
        ids.add(gauge("pravaha.query.subscribers", tags, query, RegisteredQuery::subscriberCount));
        // How reads of the view found their rows (IDXVIS-1): one series per access path.
        for (var path : java.util.Map.<String, java.util.function.ToDoubleFunction<RegisteredQuery>>of(
                        "point", q -> q.view().pointLookups(),
                        "range", q -> q.view().rangeLookups(),
                        "index", q -> q.view().indexLookups(),
                        "scan", q -> q.view().scans())
                .entrySet()) {
            ids.add(FunctionCounter.builder("pravaha.query.view.reads", query, path.getValue())
                    .tags(tags.and("path", path.getKey()))
                    .register(meters)
                    .getId());
        }
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

        // A blue/green replacement of this name, when there is one (ADR-046). Published for every
        // query rather than only for the ones being replaced, because a gauge that appears when an
        // operation starts is a gauge nothing was alerting on when it did: these read zero while
        // nothing is happening, and a dashboard's panel exists before the cutover it is watching.
        ids.add(Gauge.builder("pravaha.query.replacement.state", node, n -> replacementState(n, name))
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.history.rows",
                        node,
                        n -> replacementValue(
                                n, name, status -> status.progress().historyRows()))
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.rows.per.second",
                        node,
                        n -> replacementValue(
                                n, name, status -> status.progress().rowsPerSecond()))
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.rate.limit",
                        node,
                        n -> replacementValue(
                                n, name, status -> status.progress().rateLimit()))
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.partitions.live",
                        node,
                        n -> replacementValue(
                                n, name, status -> status.progress().partitionsLive()))
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.partitions",
                        node,
                        n -> replacementValue(
                                n, name, status -> status.progress().partitions()))
                .tags(tags)
                .register(meters)
                .getId());
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.paused",
                        node,
                        n -> replacementValue(
                                n, name, status -> status.progress().paused() ? 1 : 0))
                .tags(tags)
                .register(meters)
                .getId());
        // How far behind the running version the candidate's event time is, in seconds. What an
        // operator watches to decide whether a cutover is close; zero when there is no replacement.
        ids.add(Gauge.builder(
                        "pravaha.query.backfill.lag.seconds",
                        node,
                        n -> replacementValue(n, name, status -> status.lagNanos() / 1_000_000_000d))
                .tags(tags)
                .register(meters)
                .getId());

        published.put(name, ids);
    }

    /**
     * A replacement's state as a number, so it can be alerted on: 0 none, 1 backfilling, 2 caught
     * up, 3 cut over and retaining, 4 rolled back, 5 abandoned, 6 failed, 7 finished.
     *
     * <p>An ordinal rather than a tag per state, because a gauge whose tag changes is a new series:
     * a panel following the replacement of one query would lose its history at every transition,
     * which is exactly when somebody is looking at it.
     */
    private static double replacementState(PravahaNode node, String name) {
        return node.registry()
                .flatMap(registry -> registry.replacements().of(name))
                .map(status -> switch (status.state()) {
                    case BACKFILLING -> 1d;
                    case CAUGHT_UP -> 2d;
                    case CUT_OVER -> 3d;
                    case ROLLED_BACK -> 4d;
                    case ABANDONED -> 5d;
                    case FAILED -> 6d;
                    case FINISHED -> 7d;
                })
                .orElse(0d);
    }

    private static double replacementValue(
            PravahaNode node,
            String name,
            java.util.function.ToDoubleFunction<com.ash.messaging.pravaha.registry.QueryReplacement.Status> value) {
        return node.registry()
                .flatMap(registry -> registry.replacements().of(name))
                .map(value::applyAsDouble)
                .orElse(0d);
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
        // Debug sessions open on this node (ADR-048). A gauge rather than a counter, and published
        // whether or not anybody has ever opened one, because a node quietly holding four forks of
        // a large query has four extra copies of its state and nothing else would say so. It reads
        // zero the rest of the time, which is what makes an alert on it possible.
        laneMeters.add(Gauge.builder(
                        "pravaha.debug.sessions.open",
                        node,
                        n -> n.registry()
                                .map(open -> (double) open.debugSessions().open())
                                .orElse(0.0))
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
        // Whether per-operator counters exist on this node at all, so a dashboard that finds none
        // can say "switched off" rather than "zero" (pravaha.metrics.operators).
        laneMeters.add(Gauge.builder(
                        "pravaha.metrics.operators.enabled",
                        node,
                        n -> com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.measuringOperators() ? 1 : 0)
                .register(meters)
                .getId());
        int sharedLanes = registry.pipelinesPerSharedLane().size();
        for (int i = 0; i < sharedLanes; i++) {
            int lane = i;
            laneMeters.add(Gauge.builder("pravaha.lane.shared.queries", node, n -> queriesOnSharedLane(n, lane))
                    .tags(Tags.of("lane", Integer.toString(lane)))
                    .register(meters)
                    .getId());
            // The shared lane's own backpressure. Per lane rather than per query because the lane
            // is the thing being waited on: every query hosted on it queues behind one inbox, and
            // reporting the same fraction under each of their names would read as though each of
            // them were the cause.
            laneMeters.add(Gauge.builder("pravaha.lane.blocked.fraction", node, n -> sharedLaneBlocked(n, lane))
                    .tags(Tags.of("lane", Integer.toString(lane)))
                    .register(meters)
                    .getId());
            laneMeters.add(Gauge.builder("pravaha.lane.inbox.depth", node, n -> sharedLaneDepth(n, lane))
                    .tags(Tags.of("lane", Integer.toString(lane)))
                    .register(meters)
                    .getId());
        }
    }

    /**
     * One query on each shared lane, by lane index, refreshed with the rest of the meters.
     *
     * <p>A shared lane's backpressure is read through a query hosted on it, because a hosted
     * query's execution runs on that lane's group -- {@code RegisteredQuery.backpressure()} is
     * therefore the lane's own snapshot, breakdown and all, whichever of its queries is asked.
     *
     * <p>Through a representative rather than by asking the registry for the lane, so that {@code
     * QueryRegistry} gains nothing: it is four lines under this project's file-size ceiling and
     * the rule is to extract rather than grow. Recorded once per {@link #sync()} rather than per
     * scrape, because finding it means walking the names under the registry's own lock, and that
     * is the lock registration takes.
     */
    private final Map<Integer, String> laneRepresentative = new ConcurrentHashMap<>();

    /**
     * Which query each shared lane is read through, for a test that has to pin the indirection.
     *
     * <p>An empty entry and a lane that is genuinely idle both make the gauges read 0, so a test
     * comparing the two numbers would pass with this broken. This is what it asserts instead.
     */
    Map<Integer, String> laneRepresentatives() {
        return Map.copyOf(laneRepresentative);
    }

    private void rememberLaneRepresentatives(QueryRegistry registry) {
        laneRepresentative.clear();
        for (String name : registry.names()) {
            registry.sharedLaneOf(name).ifPresent(lane -> laneRepresentative.putIfAbsent(lane, name));
        }
    }

    /**
     * That lane's backpressure, or empty when no query has been placed on it yet -- which is not
     * the same as a lane that is never blocked, and is why the gauges read 0 rather than NaN: a
     * lane carrying nothing genuinely has nobody waiting on it.
     */
    private java.util.Optional<com.ash.messaging.pravaha.runtime.lane.LaneBackpressure.Snapshot> sharedLane(
            PravahaNode node, int lane) {
        String name = laneRepresentative.get(lane);
        return name == null
                ? java.util.Optional.empty()
                : node.registry().flatMap(registry -> registry.find(name)).map(RegisteredQuery::backpressure);
    }

    private double sharedLaneBlocked(PravahaNode node, int lane) {
        return sharedLane(node, lane)
                .map(com.ash.messaging.pravaha.runtime.lane.LaneBackpressure.Snapshot::blockedFraction)
                .orElse(0d);
    }

    private double sharedLaneDepth(PravahaNode node, int lane) {
        return sharedLane(node, lane)
                .map(snapshot -> (double) snapshot.inboxDepth())
                .orElse(0d);
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

    /**
     * One query's dead-letter queue as its writer sees it, or all zeroes when it has none.
     *
     * <p>By name and not through the {@link RegisteredQuery}, because the file is named for the
     * name that opened the feed: two names on one computation share the computation and not the
     * queue.
     */
    private com.ash.messaging.pravaha.runtime.dlq.DeadLetterHealth deadLetters(String name) {
        return node.sources()
                .map(sources -> sources.deadLetterHealth(name))
                .orElseGet(com.ash.messaging.pravaha.runtime.dlq.DeadLetterHealth::none);
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
        tenancy.close();
    }
}

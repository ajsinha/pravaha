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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * Watches one stream's source for partitions added while a query reads it, and opens each one.
 *
 * <p>A source that answers a {@link StreamSourcePlugin#partitionRefreshInterval} above zero is asked
 * for its partitions again that often, on the feed's own thread -- so a new pump joins the feed
 * that will poll it, and no second thread ever touches a pump. A partition not seen before is
 * opened through {@link StreamSourcePlugin#createReaderForNewPartition}: it did not exist when the
 * query began, so it has no history the query chose to skip, and it is read from its first record.
 * Its pump names its partition to the execution, so its offset enters the next checkpoint like any
 * other and a restore matches it back by partition (see {@code QueryExecution#pumpInto}).
 *
 * <p><strong>A refresh that fails is retried, and said so.</strong> Asking for the partition list
 * is metadata, not data: a broker unreachable for one refresh has lost nothing, and the readers
 * already open report a real outage themselves. The failure is logged once per distinct cause and
 * shown in the feed's description until a refresh succeeds. Opening a found partition that then
 * fails -- its log already truncated past where it must start -- is a real failure and is thrown,
 * which stops the feed by name like any read failure.
 */
final class PartitionGrowth {

    private static final System.Logger LOG = System.getLogger(PartitionGrowth.class.getName());

    /** Turns a reader into the pump that feeds the execution; the feed adds what it opened. */
    @FunctionalInterface
    interface PumpFactory {
        IngestPump pump(int partition, PartitionReader reader, List<AutoCloseable> opened);
    }

    /** One partition opened by a refresh: its pump, where it reads, and what to close with the feed. */
    record Opened(IngestPump pump, FeedInput input, List<AutoCloseable> resources) {}

    private final String stream;
    private final StreamSourcePlugin plugin;
    private final ReadRequest request;
    private final long intervalNanos;
    private final PumpFactory factory;
    private final Set<Integer> known = new TreeSet<>();
    private final List<Integer> added = new java.util.concurrent.CopyOnWriteArrayList<>();

    private long nextRefreshNanos;
    private volatile String problem = "";

    PartitionGrowth(
            String stream,
            StreamSourcePlugin plugin,
            ReadRequest request,
            List<Integer> known,
            Duration interval,
            PumpFactory factory,
            long nowNanos) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("a source whose partitions never change is not watched");
        }
        this.stream = stream;
        this.plugin = plugin;
        this.request = request;
        this.intervalNanos = interval.toNanos();
        this.factory = factory;
        this.known.addAll(known);
        this.nextRefreshNanos = nowNanos + intervalNanos;
    }

    /**
     * Asks the source for its partitions when the interval has passed, and opens any new one.
     *
     * @return what was opened, in partition order; empty when it is not yet time or nothing is new
     */
    List<Opened> poll(long nowNanos) {
        if (nowNanos - nextRefreshNanos < 0) {
            return List.of();
        }
        nextRefreshNanos = nowNanos + intervalNanos;
        List<SourcePartition> partitions;
        try {
            partitions = plugin.partitions(stream);
        } catch (RuntimeException e) {
            String cause = String.valueOf(e.getMessage());
            if (!cause.equals(problem)) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "could not refresh the partitions of '" + stream + "'; retrying in "
                                + Duration.ofNanos(intervalNanos).toSeconds() + "s: " + cause);
            }
            problem = cause;
            return List.of();
        }
        problem = "";
        List<Opened> opened = new ArrayList<>();
        for (SourcePartition partition : partitions) {
            if (known.contains(partition.index())) {
                continue;
            }
            List<AutoCloseable> resources = new ArrayList<>();
            PartitionReader reader = plugin.createReaderForNewPartition(partition, request);
            resources.add(reader);
            IngestPump pump;
            try {
                pump = factory.pump(partition.index(), reader, resources);
            } catch (RuntimeException e) {
                closeQuietly(reader);
                throw e;
            }
            known.add(partition.index());
            added.add(partition.index());
            opened.add(new Opened(pump, new FeedInput(stream, partition.index()), resources));
            LOG.log(
                    System.Logger.Level.INFO,
                    "'" + stream + "' gained partition " + partition.index()
                            + " while it was being read; reading it from its first record");
        }
        return opened;
    }

    /** What a status line says about this stream's growth, or empty when there is nothing to say. */
    String describe() {
        StringBuilder text = new StringBuilder();
        if (!added.isEmpty()) {
            text.append(stream)
                    .append(" gained ")
                    .append(added.size())
                    .append(added.size() == 1 ? " partition" : " partitions")
                    .append(" while running ")
                    .append(added);
        }
        String now = problem;
        if (!now.isEmpty()) {
            text.append(text.isEmpty() ? "" : "; ")
                    .append("refreshing the partitions of ")
                    .append(stream)
                    .append(" failed, retrying: ")
                    .append(now);
        }
        return text.toString();
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // Already failing; the original cause is what matters.
        }
    }
}

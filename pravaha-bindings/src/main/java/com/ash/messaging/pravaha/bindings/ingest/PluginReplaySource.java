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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.registry.ReplaySource;

/**
 * Reads a query's bound sources one row at a time, from a checkpoint's positions (ADR-048).
 *
 * <p>The pull side of the debugger. A {@link com.ash.messaging.pravaha.registry.SourceFeed} is a
 * set of pumps on threads, pushing rows as fast as backpressure allows; this polls one reader for
 * one record when the caller asks for one, and hands it back as values.
 *
 * <p><strong>No pushdown.</strong> A live query lets the source evaluate what it can, which means
 * the rows that reach the engine may be a projection of what the source holds. A debugger has to
 * show the row as the source has it -- that is most of what somebody opens one for -- so this
 * reads whole rows and pays for it. A debug session is one person stepping, not a throughput path.
 *
 * <p><strong>Round-robin across partitions, in a fixed order.</strong> See {@link ReplaySource}:
 * this is what makes a session reproducible, and it is not what the live query does.
 */
final class PluginReplaySource implements ReplaySource {

    /** One partition, its reader, and the schema its rows are decoded with. */
    private record Partition(String stream, int index, StreamSchema schema, PartitionReader reader) {}

    private final List<Partition> partitions;
    private final List<String> streams;

    /** Rows polled from a partition and not yet handed out, in the order the reader wrote them. */
    private final Map<Integer, java.util.ArrayDeque<ReplayRow>> buffered = new LinkedHashMap<>();

    /** Which partition {@link #next} will try first, so the round-robin makes progress. */
    private int cursor;

    PluginReplaySource(List<String> streams, List<PartitionSpec> specs) {
        this.streams = List.copyOf(streams);
        List<Partition> open = new ArrayList<>(specs.size());
        for (int index = 0; index < specs.size(); index++) {
            PartitionSpec spec = specs.get(index);
            open.add(new Partition(spec.stream(), spec.index(), spec.schema(), spec.reader()));
            buffered.put(index, new java.util.ArrayDeque<>());
        }
        this.partitions = List.copyOf(open);
    }

    /** What {@link PluginSourceFeeds} hands the constructor: one opened reader and its context. */
    record PartitionSpec(String stream, int index, StreamSchema schema, PartitionReader reader) {}

    @Override
    public Optional<ReplayRow> next() {
        for (int attempt = 0; attempt < partitions.size(); attempt++) {
            int index = (cursor + attempt) % partitions.size();
            ReplayRow row = take(index);
            if (row != null) {
                cursor = (index + 1) % partitions.size();
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /**
     * One row from partition {@code index}, polling the reader if nothing is buffered.
     *
     * <p>Buffered because a plugin's {@code poll} decides for itself how many records to write --
     * a file reader writes a block, a Kafka reader a fetch -- and the caller asked for one row.
     * Throwing the rest away would skip them; asking for one at a time would be a poll per row
     * against sources that cannot do that cheaply. So the whole poll is kept and drained.
     */
    private ReplayRow take(int index) {
        java.util.ArrayDeque<ReplayRow> queue = buffered.get(index);
        if (!queue.isEmpty()) {
            return queue.poll();
        }
        Partition partition = partitions.get(index);
        int written = partition.reader().poll(new CollectingSink(partition, queue), 64);
        if (written <= 0 || queue.isEmpty()) {
            return null;
        }
        return queue.poll();
    }

    @Override
    public Map<String, String> positions() {
        Map<String, String> positions = new LinkedHashMap<>();
        for (int index = 0; index < partitions.size(); index++) {
            SourceOffset at = partitions.get(index).reader().position();
            positions.put("partition-" + index, at == null ? "" : at.token());
        }
        return positions;
    }

    @Override
    public List<String> streams() {
        return streams;
    }

    @Override
    public void close() {
        for (Partition partition : partitions) {
            try {
                partition.reader().close();
            } catch (RuntimeException e) {
                // One reader that will not close must not strand the others.
            }
        }
    }

    /** Collects a poll's records as values, with the position each was read at. */
    private final class CollectingSink implements PartitionReader.RecordSink {

        private final Partition partition;
        private final java.util.ArrayDeque<ReplayRow> queue;

        CollectingSink(Partition partition, java.util.ArrayDeque<ReplayRow> queue) {
            this.partition = partition;
            this.queue = queue;
        }

        @Override
        public RowWriter beginRow() {
            SourceOffset at = partition.reader().position();
            return new ValueCapturingWriter(
                    partition.schema(),
                    (values, weight, eventTime, sequence) -> queue.add(new ReplayRow(
                            partition.stream(),
                            partition.index(),
                            at == null ? "" : at.token(),
                            weight,
                            eventTime,
                            sequence,
                            values)));
        }

        @Override
        public boolean reject(byte[] raw, String sourceOffset, String reason) {
            // A record this node cannot decode is not a row a debug session can step onto, and a
            // dead letter written from a debugger would be a second copy of one production has
            // already written. Skipped, and the step simply moves to the next partition.
            return true;
        }
    }
}

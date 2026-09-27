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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * A log-shaped source whose topics gain partitions, as a Kafka topic scaled out does.
 *
 * <p>Each binding names a {@code topic}; a topic is a list of partitions, each an append-only list of
 * ids. A position is {@code topic/partition@next}, and a reader handed another partition's position
 * refuses it -- as the Kafka source does -- so a restore that matched offsets to the wrong partition
 * fails here instead of quietly re-reading. {@code start.from: latest} makes a plain reader start at
 * the partition's end, so a test can tell a reader opened for a new partition (from its first
 * record) from one opened by the binding's start position.
 */
public class GrowingPartitionsPlugin implements StreamSourcePlugin {

    /** Static because {@link java.util.ServiceLoader} constructs the instance, not the test. */
    static final Map<String, List<List<Long>>> TOPICS = new ConcurrentHashMap<>();

    /** How many readers were opened as new partitions, per topic. */
    static final Map<String, Integer> OPENED_AS_NEW = new ConcurrentHashMap<>();

    static final StreamSchema SCHEMA = StreamSchema.builder("grown")
            .field("id", Types.int64())
            .field("part", Types.int64())
            .build();

    private String topic;
    private boolean latest;
    private Duration refresh = Duration.ofMillis(50);

    /** A topic of {@code partitions} empty partitions, replacing any of that name. */
    static void create(String topic, int partitions) {
        List<List<Long>> created = new CopyOnWriteArrayList<>();
        for (int p = 0; p < partitions; p++) {
            created.add(new CopyOnWriteArrayList<>());
        }
        TOPICS.put(topic, created);
        OPENED_AS_NEW.remove(topic);
    }

    static void append(String topic, int partition, long id) {
        TOPICS.get(topic).get(partition).add(id);
    }

    /** Adds one partition to {@code topic}; returns its index. */
    static int grow(String topic) {
        List<List<Long>> partitions = TOPICS.get(topic);
        partitions.add(new CopyOnWriteArrayList<>());
        return partitions.size() - 1;
    }

    @Override
    public String name() {
        return "growing-partitions";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.topic = context.require("topic");
        this.latest = context.get("start.from", "earliest").equals("latest");
        String every = context.get("refresh.millis", "");
        if (!every.isEmpty()) {
            this.refresh = Duration.ofMillis(Long.parseLong(every));
        }
    }

    @Override
    public void open() {}

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                true,
                true,
                false,
                false,
                DeliveryGuarantee.EXACTLY_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofMillis(10));
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(SCHEMA);
    }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        List<SourcePartition> partitions = new ArrayList<>();
        for (int p = 0; p < TOPICS.get(topic).size(); p++) {
            partitions.add(new SourcePartition(streamName, p, Map.of()));
        }
        return partitions;
    }

    @Override
    public Duration partitionRefreshInterval() {
        return refresh;
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        int start;
        if (resumeFrom == null || resumeFrom.isBeginning()) {
            start = latest ? TOPICS.get(topic).get(partition.index()).size() : 0;
        } else {
            String expected = topic + "/" + partition.index() + "@";
            if (!resumeFrom.token().startsWith(expected)) {
                throw new PravahaException(
                        IngestErrors.FEED_FAILED,
                        "the checkpoint holds " + resumeFrom.token() + " for a reader of " + expected);
            }
            start = Integer.parseInt(resumeFrom.token().substring(expected.length()));
        }
        return new Reader(partition.index(), start);
    }

    @Override
    public PartitionReader createReaderForNewPartition(SourcePartition partition, ReadRequest request) {
        OPENED_AS_NEW.merge(topic, 1, Integer::sum);
        return new Reader(partition.index(), 0);
    }

    private final class Reader implements PartitionReader {

        private final int partition;
        private int next;
        private boolean paused;

        Reader(int partition, int next) {
            this.partition = partition;
            this.next = next;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            if (paused) {
                return 0;
            }
            List<Long> log = TOPICS.get(topic).get(partition);
            int written = 0;
            while (written < maxRecords && next < log.size()) {
                RowWriter writer = sink.beginRow();
                writer.setLong(0, log.get(next));
                writer.setLong(1, partition);
                writer.weight(1)
                        .eventTimestampNanos(System.currentTimeMillis() * 1_000_000L)
                        .sequence(next)
                        .commit();
                next++;
                written++;
            }
            return written;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(topic + "/" + partition + "@" + next);
        }

        @Override
        public void pause() {
            paused = true;
        }

        @Override
        public void resume() {
            paused = false;
        }

        @Override
        public void close() {}
    }
}

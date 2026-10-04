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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.jspecify.annotations.Nullable;

/**
 * A topic in memory, and consumers that read it the way the real client does: assigned, seeked, and
 * polled from their position, with an offset below the log's start refused as out of range.
 *
 * <p>Kafka's {@code MockConsumer} holds only the records it was handed and forgets them once polled,
 * so a reader created after the records were written -- which is every reader the engine creates --
 * would see nothing. Each consumer here is fed from the shared log on every poll instead.
 */
final class FakeTopic implements KafkaClients {

    final String name;
    private volatile int partitionCount;
    private final Map<Integer, List<ConsumerRecord<byte[], byte[]>>> log = new HashMap<>();
    private final Map<Integer, Long> logStart = new HashMap<>();
    private final Map<Integer, Long> next = new HashMap<>();
    final List<FakeConsumer> consumers = new CopyOnWriteArrayList<>();
    private volatile boolean exists = true;
    private volatile @Nullable RuntimeException unreachable;
    private volatile @Nullable Error pollError;

    FakeTopic(String name, int partitions) {
        this.name = name;
        this.partitionCount = partitions;
        for (int p = 0; p < partitions; p++) {
            log.put(p, new ArrayList<>());
            logStart.put(p, 0L);
            next.put(p, 0L);
        }
    }

    /** Appends a record, its timestamp {@code 1000 * (offset + 1)} milliseconds; returns its offset. */
    synchronized long append(int partition, String key, @Nullable String value) {
        long offset = at(next, partition);
        next.put(partition, offset + 1);
        at(log, partition)
                .add(new ConsumerRecord<>(
                        name,
                        partition,
                        offset,
                        1000L * (offset + 1),
                        TimestampType.CREATE_TIME,
                        key == null ? -1 : key.length(),
                        value == null ? -1 : value.length(),
                        key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                        value == null ? null : value.getBytes(StandardCharsets.UTF_8),
                        new RecordHeaders(),
                        Optional.empty()));
        return offset;
    }

    /** Leaves {@code count} offsets with no record a consumer is given, as transaction markers do. */
    synchronized void gap(int partition, int count) {
        next.put(partition, at(next, partition) + count);
    }

    /** Retention: every record before {@code offset} is deleted. */
    synchronized void deleteBefore(int partition, long offset) {
        at(log, partition).removeIf(record -> record.offset() < offset);
        logStart.put(partition, offset);
        next.put(partition, Math.max(at(next, partition), offset));
    }

    void drop() {
        exists = false;
    }

    void unreachable(@Nullable RuntimeException failure) {
        unreachable = failure;
    }

    /** Adds a partition, as {@code kafka-topics --alter --partitions} does; returns its index. */
    synchronized int addPartition() {
        int added = partitionCount;
        log.put(added, new ArrayList<>());
        logStart.put(added, 0L);
        next.put(added, 0L);
        partitionCount = added + 1;
        return added;
    }

    /** Every poll throws {@code error}, as the client does when a batch's codec cannot load. */
    void pollThrows(Error error) {
        pollError = error;
    }

    synchronized long end(int partition) {
        return at(next, partition);
    }

    private synchronized List<ConsumerRecord<byte[], byte[]>> from(int partition, long position) {
        List<ConsumerRecord<byte[], byte[]>> found = new ArrayList<>();
        for (ConsumerRecord<byte[], byte[]> record : at(log, partition)) {
            if (record.offset() >= position) {
                found.add(record);
            }
        }
        return found;
    }

    @Override
    public Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
        FakeConsumer consumer = new FakeConsumer(config);
        consumers.add(consumer);
        return consumer;
    }

    @Override
    public Producer<byte[], byte[]> producer(Map<String, Object> config) {
        throw new UnsupportedOperationException("a source test makes no producer");
    }

    @Override
    public void prepareTopics(KafkaSinkOptions options) {
        throw new UnsupportedOperationException("a source test prepares no topics");
    }

    /** One client, as the plugin would have made it. */
    final class FakeConsumer extends MockConsumer<byte[], byte[]> {

        final Map<String, Object> config;

        FakeConsumer(Map<String, Object> config) {
            super(OffsetResetStrategy.NONE);
            this.config = Map.copyOf(config);
        }

        @SuppressWarnings("WaitNotInLoop") // a bounded poll: a spurious wakeup returns early and the caller polls again
        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            if (unreachable != null) {
                throw unreachable;
            }
            if (pollError != null) {
                throw pollError;
            }
            for (TopicPartition partition : assignment()) {
                if (paused().contains(partition)) {
                    continue;
                }
                long position = position(partition);
                if (position < logStartOf(partition.partition())) {
                    throw new OffsetOutOfRangeException(
                            "Fetch position " + position + " is out of range", Map.of(partition, position));
                }
                for (ConsumerRecord<byte[], byte[]> record : from(partition.partition(), position)) {
                    addRecord(record);
                }
            }
            ConsumerRecords<byte[], byte[]> records = super.poll(Duration.ZERO);
            for (TopicPartition partition : assignment()) {
                // Step over a gap at the end the way a consumer steps over a trailing marker.
                long end = end(partition.partition());
                if (!paused().contains(partition)
                        && position(partition) < end
                        && from(partition.partition(), position(partition)).isEmpty()) {
                    seek(partition, end);
                }
            }
            if (records.isEmpty()) {
                try {
                    wait(Math.max(1, Math.min(timeout.toMillis(), 5)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return records;
        }

        @Override
        public synchronized List<PartitionInfo> partitionsFor(String topic) {
            if (unreachable != null) {
                throw unreachable;
            }
            if (!exists || !topic.equals(name)) {
                return List.of();
            }
            List<PartitionInfo> infos = new ArrayList<>();
            // Deliberately out of order: the plugin must sort them.
            for (int p = partitionCount - 1; p >= 0; p--) {
                infos.add(new PartitionInfo(name, p, null, null, null));
            }
            return infos;
        }

        @Override
        public List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
            return partitionsFor(topic);
        }

        @Override
        public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
            if (unreachable != null) {
                throw unreachable;
            }
            Map<TopicPartition, Long> offsets = new HashMap<>();
            for (TopicPartition partition : partitions) {
                offsets.put(partition, logStartOf(partition.partition()));
            }
            return offsets;
        }

        @Override
        public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            return beginningOffsets(partitions);
        }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
            if (unreachable != null) {
                throw unreachable;
            }
            Map<TopicPartition, Long> offsets = new HashMap<>();
            for (TopicPartition partition : partitions) {
                offsets.put(partition, end(partition.partition()));
            }
            return offsets;
        }

        @Override
        public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            return endOffsets(partitions);
        }
    }

    private synchronized long logStartOf(int partition) {
        return at(logStart, partition);
    }

    /** A partition's entry: every partition has one from the constructor on. */
    private static <V> V at(java.util.Map<Integer, V> byPartition, int partition) {
        return Objects.requireNonNull(byPartition.get(partition), "no partition " + partition);
    }
}

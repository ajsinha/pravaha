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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
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
 * {@code kafka}: a topic read as a stream, one reader per partition, exactly once with checkpoints.
 *
 * <p><strong>The partitions are the topic's.</strong> {@link #partitions} returns one per Kafka
 * partition, in partition order, and {@link #createReader} opens a {@link KafkaPartitionReader} on
 * exactly that partition -- assigned, never subscribed, so no consumer group moves partitions between
 * readers behind the engine's back. The list is read again every {@code partitions.refresh} (30s by
 * default, {@link #partitionRefreshInterval}) while a query runs, and a partition added to the topic
 * meanwhile is opened by the engine through {@link #createReaderForNewPartition}: from its earliest
 * offset whatever {@code start.from} says, because a partition that did not exist has no history the
 * query chose to skip. Its offset enters the next checkpoint like any other.
 *
 * <p><strong>The guarantee is {@code EXACTLY_ONCE}, and the checkpoint is the only position.</strong>
 * A reader's position is a Kafka offset, and a restore seeks to the offset the checkpoint recorded:
 * the log replays deterministically from there, so the engine sees exactly the records the checkpoint
 * does not hold (ADR-008's recovery, with no store-side state to reconcile). A consumer group's
 * committed offset is never read. {@code monitoring.group}, when set, receives each durable
 * checkpoint's offsets through {@link PartitionReader#checkpointed} so lag monitoring works; it is a
 * report, not a position. Two things end the guarantee, and both are refused rather than skipped past
 * ({@code PRV-5106}): retention deleting records the checkpoint has not yet read, and a checkpoint
 * whose offset is past the end of the partition, which is what a deleted and recreated topic looks
 * like. With {@code isolation.level: read_committed} (the default) the records of an aborted Kafka
 * transaction are never delivered -- so a topic {@code kafka-sink} writes transactionally reads back
 * exactly once too.
 *
 * <p><strong>Deletes only in changelog form.</strong> {@code format: json} reads each value as a row at
 * {@code +1}; a tombstone -- a key with a null value -- says a key was deleted without saying what it
 * held, so it is refused (a dead letter, or {@code PRV-5105}) unless {@code tombstone: skip} reads the
 * topic as insertions only. {@code format: changelog} reads {@code kafka-sink}'s changelog envelope,
 * whose rows carry their own weight, so a retraction in one query's sink is a retraction in the next
 * query's source; it declares {@code emitsDeletes} and {@code emitsBeforeImage}. See {@link
 * KafkaRecordDecoder} for both formats.
 *
 * <p>Not shared between queries: an {@code EXACTLY_ONCE} source never is, so each registration reading
 * the binding has a consumer, and a fetch thread, per partition.
 */
public final class KafkaSourcePlugin implements StreamSourcePlugin {

    /** How long {@link #health} waits for the brokers before calling them unreachable. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private static final long HEALTH_CACHE_NANOS = 2_000_000_000L;

    private final KafkaClients clients;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private KafkaSourceOptions options;

    /** Null until open() and after close(). */
    private @Nullable Consumer<byte[], byte[]> metadata;

    // A ReentrantLock rather than a monitor: the metadata consumer asks the brokers under it, and on
    // JDK 21 a virtual thread blocked inside a monitor pins its carrier.
    private final java.util.concurrent.locks.ReentrantLock metadataLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final List<KafkaPartitionReader> readers = new CopyOnWriteArrayList<>();
    private volatile @Nullable HealthStatus cachedHealth;
    private volatile long cachedAt;

    /** What {@code ServiceLoader} constructs. */
    public KafkaSourcePlugin() {
        this(KafkaClients.REAL);
    }

    KafkaSourcePlugin(KafkaClients clients) {
        this.clients = clients;
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.options = new KafkaSourceOptions(context);
    }

    /** Checks the brokers are reachable and the topic exists; never creates it. */
    @Override
    public void open() {
        requireConfigured();
        if (metadata != null) {
            return;
        }
        Consumer<byte[], byte[]> consumer = clients.consumer(options.metadataConsumer());
        try {
            partitionsOf(consumer, options.startTimeout);
        } catch (RuntimeException e) {
            closeQuietly(consumer);
            throw e;
        }
        metadata = consumer;
    }

    @Override
    public SourceCapabilities capabilities() {
        requireConfigured();
        boolean changelog = options.format == KafkaSourceOptions.Format.CHANGELOG;
        return new SourceCapabilities(
                // A Kafka offset is a position the log can be read from again, for as long as retention
                // keeps it -- and a position it no longer keeps is refused, not skipped.
                true,
                // One partition is one ordered log, and one reader reads it in order.
                true,
                // Only the changelog form carries a retraction; a JSON row is an insertion.
                changelog,
                // kafka-sink's changelog writes the whole retracted row, so an update arrives as the old
                // row at -1 and the new at +1.
                changelog,
                // Offsets are exact and replay is deterministic; the reader resumes at the recorded one.
                DeliveryGuarantee.EXACTLY_ONCE,
                // Kafka has no server-side filter or projection; every record is fetched whole.
                EnumSet.noneOf(PushdownKind.class),
                // A record is fetched within a fetch wait of being committed: tens of milliseconds.
                Duration.ofMillis(100),
                // Never repeats: each record is read once, at its offset. A json topic whose producer
                // re-publishes a key is a log of events by declaration -- format: changelog is how a
                // topic that revises its rows says so.
                false);
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        requireConfigured();
        return List.of(options.schema);
    }

    /** The declared schema, named for the stream this binding feeds. */
    public StreamSchema schema() {
        requireConfigured();
        return options.schema;
    }

    @Override
    public List<SourcePartition> partitions(String stream) {
        requireOpen();
        List<SourcePartition> partitions = new ArrayList<>();
        // A consumer of its own for each listing. A consumer answers partitionsFor from the metadata
        // it already holds, and the long-lived one never polls, so it would go on listing the
        // partitions the topic had when it first asked: a partition added later was never found
        // (the engine asks again every partitions.refresh). A new client has no metadata to reuse.
        Consumer<byte[], byte[]> fresh = clients.consumer(options.metadataConsumer());
        try {
            for (PartitionInfo info : partitionsOf(fresh, options.startTimeout)) {
                partitions.add(new SourcePartition(
                        stream,
                        info.partition(),
                        Map.of("topic", options.topic, "partition", Integer.toString(info.partition()))));
            }
        } finally {
            closeQuietly(fresh);
        }
        return partitions;
    }

    @Override
    public Duration partitionRefreshInterval() {
        requireConfigured();
        return options.partitionsRefresh;
    }

    /**
     * A reader for a partition added to the topic after the query began reading it: from the
     * partition's earliest offset, whatever {@code start.from} says. {@code start.from: latest} means
     * "not the history the topic had when I registered", and a partition that did not exist then has
     * none -- every record in it arrived while the query was running.
     */
    @Override
    public PartitionReader createReaderForNewPartition(SourcePartition partition, ReadRequest request) {
        return open(partition, null, true);
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, @Nullable SourceOffset resumeFrom) {
        return open(partition, resumeFrom, false);
    }

    private PartitionReader open(SourcePartition partition, @Nullable SourceOffset resumeFrom, boolean fromEarliest) {
        requireOpen();
        TopicPartition topicPartition = new TopicPartition(options.topic, partition.index());
        KafkaSourceOffset resume = KafkaSourceOffset.parse(resumeFrom, topicPartition);
        long beginning;
        long end;
        metadataLock.lock();
        try {
            try {
                Consumer<byte[], byte[]> open = Objects.requireNonNull(metadata, "requireOpen()");
                beginning = Objects.requireNonNull(
                        open.beginningOffsets(List.of(topicPartition), options.startTimeout)
                                .get(topicPartition),
                        "the partition asked about");
                end = Objects.requireNonNull(
                        open.endOffsets(List.of(topicPartition), options.startTimeout)
                                .get(topicPartition),
                        "the partition asked about");
            } catch (KafkaException e) {
                throw new PravahaException(
                        KafkaErrors.CONNECT_FAILED,
                        "source '" + options.instanceName + "' cannot read the offsets of " + topicPartition + " at "
                                + options.bootstrapServers + ": " + e.getMessage(),
                        e);
            }
        } finally {
            metadataLock.unlock();
        }
        long start;
        if (resume == null) {
            start = options.startAtLatest && !fromEarliest ? end : beginning;
        } else {
            start = resume.next();
            if (start < beginning) {
                throw new PravahaException(
                        KafkaErrors.RESUME_POINT_GONE,
                        "the checkpoint resumes " + topicPartition + " at offset " + start + ", and the log now "
                                + "starts at " + beginning + ": retention deleted " + (beginning - start)
                                + " records the checkpoint had not seen. Resuming at " + beginning + " would lose "
                                + "them silently, so this is refused. Raise the topic's retention.ms beyond the "
                                + "longest outage, and re-register the query to start again.");
            }
            if (start > end) {
                throw new PravahaException(
                        KafkaErrors.RESUME_POINT_GONE,
                        "the checkpoint resumes " + topicPartition + " at offset " + start + ", past the end of the "
                                + "partition (" + end + "). The topic was deleted and recreated, or the "
                                + "checkpoint belongs to another cluster; either way these offsets mean other "
                                + "records now. Re-register the query to start again.");
            }
        }
        KafkaPartitionReader reader = new KafkaPartitionReader(
                options, topicPartition, clients.consumer(options.readerConsumer(topicPartition)), start);
        try {
            reader.awaitCaughtUp(end, options.startTimeout);
        } catch (RuntimeException e) {
            reader.close();
            throw e;
        }
        readers.removeIf(KafkaPartitionReader::isClosed);
        readers.add(reader);
        // What health last measured did not include this reader.
        cachedHealth = null;
        return reader;
    }

    /**
     * Healthy while the brokers answer and every reader keeps up.
     *
     * <p>Asks the brokers for each read partition's end offset -- a real round trip, so an unreachable
     * cluster shows here within {@code HEALTH_TIMEOUT} -- and reports lag as the records between it and
     * what the engine has been handed. {@code DEGRADED} past {@code lag.warn.records} on any partition,
     * or when committing to {@code monitoring.group} fails; {@code UNHEALTHY} when the brokers do not
     * answer or a reader has stopped. Cached for two seconds; the engine polls this often.
     */
    @Override
    public HealthStatus health() {
        if (metadata == null) {
            return HealthStatus.unhealthy("not open");
        }
        // A stopped reader is known without asking anybody, so it is never hidden behind the cache.
        readers.removeIf(KafkaPartitionReader::isClosed);
        for (KafkaPartitionReader reader : readers) {
            if (reader.failure() != null) {
                return HealthStatus.unhealthy("the reader of " + reader.partition() + " has stopped: "
                        + reader.failure().getMessage());
            }
        }
        long now = System.nanoTime();
        HealthStatus cached = cachedHealth;
        if (cached != null && now - cachedAt < HEALTH_CACHE_NANOS) {
            return cached;
        }
        HealthStatus fresh = measure();
        cachedHealth = fresh;
        cachedAt = now;
        return fresh;
    }

    private HealthStatus measure() {
        List<TopicPartition> read = readers.stream()
                .map(KafkaPartitionReader::partition)
                .distinct()
                .sorted(Comparator.comparingInt(TopicPartition::partition))
                .toList();
        Map<TopicPartition, Long> ends;
        metadataLock.lock();
        try {
            if (metadata == null) {
                return HealthStatus.unhealthy("not open");
            }
            try {
                if (read.isEmpty()) {
                    int count = partitionsOf(metadata, HEALTH_TIMEOUT).size();
                    return new HealthStatus(
                            HealthStatus.State.HEALTHY,
                            "topic " + options.topic + " reachable, " + count + " partitions, none being read");
                }
                ends = metadata.endOffsets(read, HEALTH_TIMEOUT);
            } catch (RuntimeException e) {
                return HealthStatus.unhealthy("the brokers at " + options.bootstrapServers + " did not answer within "
                        + HEALTH_TIMEOUT.toSeconds() + "s: " + e.getMessage());
            }
        } finally {
            metadataLock.unlock();
        }
        StringBuilder lags = new StringBuilder();
        long worst = 0;
        String commitProblem = "";
        for (KafkaPartitionReader reader : readers) {
            Long end = ends.get(reader.partition());
            long lag = end == null ? 0 : Math.max(0, end - reader.nextOffset());
            worst = Math.max(worst, lag);
            lags.append(lags.isEmpty() ? "" : ", ")
                    .append('p')
                    .append(reader.partition().partition())
                    .append(' ')
                    .append(lag);
            if (commitProblem.isEmpty()) {
                commitProblem = reader.commitProblem();
            }
        }
        String detail = "topic " + options.topic + ", " + readers.size() + " partitions read, lag in records: " + lags;
        if (worst >= options.lagWarnRecords) {
            return HealthStatus.degraded(detail + " -- past lag.warn.records=" + options.lagWarnRecords
                    + ". The engine is not keeping up with the topic, or is paused behind a slow downstream");
        }
        if (!commitProblem.isEmpty()) {
            return HealthStatus.degraded(detail + ". " + commitProblem);
        }
        return new HealthStatus(HealthStatus.State.HEALTHY, detail);
    }

    @Override
    public void close() {
        for (KafkaPartitionReader reader : readers) {
            reader.close();
        }
        readers.clear();
        metadataLock.lock();
        try {
            if (metadata != null) {
                closeQuietly(metadata);
                metadata = null;
            }
        } finally {
            metadataLock.unlock();
        }
        if (options != null) {
            // The schema registry's HTTP client, when the binding has one.
            options.close();
        }
    }

    /** The topic's partitions in partition order, refusing a topic that does not exist. */
    private List<PartitionInfo> partitionsOf(Consumer<byte[], byte[]> consumer, Duration timeout) {
        List<PartitionInfo> infos;
        try {
            infos = consumer.partitionsFor(options.topic, timeout);
        } catch (KafkaException e) {
            throw new PravahaException(
                    KafkaErrors.CONNECT_FAILED,
                    "source '" + options.instanceName + "' cannot read topic '" + options.topic + "' at "
                            + options.bootstrapServers + ": " + e.getMessage()
                            + ". Check the brokers are reachable, and the credentials and ACLs allow Describe and "
                            + "Read on the topic.",
                    e);
        }
        if (infos == null || infos.isEmpty()) {
            throw new PravahaException(
                    KafkaErrors.CONNECT_FAILED,
                    "source '" + options.instanceName + "': topic '" + options.topic + "' does not exist at "
                            + options.bootstrapServers + ". The source never creates the topic it reads.");
        }
        List<PartitionInfo> sorted = new ArrayList<>(infos);
        sorted.sort(Comparator.comparingInt(PartitionInfo::partition));
        return sorted;
    }

    private static void closeQuietly(Consumer<byte[], byte[]> consumer) {
        try {
            consumer.close(Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // Closing; nothing is owed to a consumer that is already gone.
        }
    }

    private void requireConfigured() {
        if (options == null) {
            throw new ConfigurationException(KafkaErrors.BAD_CONFIGURATION, "the kafka source is not configured");
        }
    }

    private void requireOpen() {
        requireConfigured();
        if (metadata == null) {
            throw new IllegalStateException("kafka source '" + options.instanceName + "' is not open");
        }
    }

    /**
     * Offsets within a partition are totally ordered, so one reader can serve every query on a partition
     * at an exact seam (ADR-054). {@link SourceOffset#BEGINNING} comes first.
     */
    @Override
    public com.ash.messaging.pravaha.api.plugin.OrderedPositions orderedPositions() {
        return (a, b) -> Long.compare(nextOf(a), nextOf(b));
    }

    private static long nextOf(SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return -1;
        }
        String token = offset.token();
        return Long.parseLong(token.substring(token.lastIndexOf('@') + 1));
    }
}

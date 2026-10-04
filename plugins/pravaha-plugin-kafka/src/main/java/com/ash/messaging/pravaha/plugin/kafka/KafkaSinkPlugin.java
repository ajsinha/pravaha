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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * A continuous query's changes, written to a Kafka topic.
 *
 * <p><strong>Two modes.</strong> {@code mode: upsert} (the default) makes the topic a table keyed by
 * {@code key.columns}: each change is a record whose key is the key columns and whose value is the
 * row, and a retraction is a tombstone -- the key with a null value. On a compacted topic that is the
 * query's answer, one record per key. {@code mode: changelog} writes every change as it is, {@code
 * {"op":"insert"|"delete","weight":n,"row":{...}}}, for a consumer that applies weights itself.
 * {@link KafkaRecords} has the encoding, JSON with the schema's column names.
 *
 * <p><strong>Exactly once, and why it is not a Kafka transaction held open across a checkpoint.</strong>
 * The obvious mapping of the SPI's protocol onto Kafka -- write into an open transaction, {@code
 * prepare} flushes it, {@code commit} calls {@code commitTransaction} -- does not hold, for two
 * reasons that are properties of Kafka's producer rather than of this code:
 *
 * <ol>
 *   <li><em>Kafka has no prepare.</em> A transaction is open or it is committed; there is no durable
 *       "prepared" state a new process can pick up. When the process dies after a checkpoint is durable
 *       and before its commit is sent, the restarted producer's {@code initTransactions} -- the only way
 *       to use the {@code transactional.id} again -- <em>aborts</em> that transaction. The SPI's restore
 *       then calls {@code commit} with the handle the checkpoint recorded, and there is nothing left to
 *       commit. Those rows came before the checkpoint's cut, so the replay does not write them again
 *       ({@code SinkDelivery.recover} commits the restored handles and replays only what follows the
 *       cut; {@code TransactionalSinkDeliveryTest} holds it). They would be lost: not even at least once.
 *       KIP-939 adds a real prepare to Kafka ({@code transaction.two.phase.commit.enable}); it needs a
 *       broker and client this plugin cannot assume, and the reflection that resumes another
 *       process's producer epoch is not a thing to build a guarantee on.
 *   <li><em>One open transaction per producer.</em> The engine begins the next transaction the moment it
 *       prepares one, and the prepared one stays uncommitted until its checkpoint is durable. A single
 *       producer would put both into one Kafka transaction.
 * </ol>
 *
 * <p>So this sink does what {@code jdbc-sink} does with its staging table, with a <em>staging topic</em>:
 *
 * <ol>
 *   <li>{@link #write} encodes each change into the exact key and value the target will receive and
 *       sends it to the staging topic -- one partition, {@code cleanup.policy=delete} -- through an
 *       idempotent producer, headed with this process's run id and the open transaction's label. Nobody
 *       reading the target topic sees it.
 *   <li>{@link #prepare} flushes, and names the transaction by the staging offsets it occupies: {@code
 *       kafka-sink:v1:<label>:<run>:<from>:<to>:<transactional.id>}. The range is durable on the broker,
 *       so the handle means the same thing to a process started tomorrow.
 *   <li>{@link #commit} reads the range back and, in <em>one Kafka transaction</em> under {@code
 *       transactional.id}, writes those records to the target topic and commits offset {@code to} for the
 *       staging partition to the consumer group {@code commit.group}. The offset is the commit's receipt:
 *       it becomes visible exactly when the records do, or not at all. Before starting, commit reads it
 *       back, and a range whose end it has already passed is skipped -- which is what makes commit
 *       idempotent, as the SPI requires, across a crash at any point.
 *   <li>{@link #abortAfter} has nothing to do. A target transaction the dead process left open was
 *       aborted by {@code initTransactions} when this one opened (which also fences the dead process,
 *       should it still be alive); staged changes no recorded handle names are never read, and age out
 *       with the staging topic's retention.
 * </ol>
 *
 * <p><strong>The guarantee, stated precisely.</strong> On a checkpointed node, every change the engine
 * commits reaches the target topic exactly once <em>as seen by a {@code read_committed} consumer</em>,
 * and each checkpoint's changes become visible together. A {@code read_uncommitted} consumer can also
 * see the records of a commit that was aborted by a crash and then redone: at least once for it. The
 * guarantee assumes one writer per {@code transactional.id} (default: the binding's name) -- a second
 * one fences the first, which then fails loudly -- and that staged records outlive the gap between a
 * checkpoint and its commit, a restart included ({@code staging.retention.ms}, a week by default; a
 * commit that finds its range already deleted refuses with {@code PRV-5103} rather than skipping it).
 * What it costs: every change is written twice, and the topic lags the view by one checkpoint
 * interval. With {@code transactional: false} each change goes straight to the target through an
 * idempotent producer: effectively once in upsert mode on a compacted topic (a replay rewrites keys
 * with the values they hold), at least once in changelog mode.
 *
 * <p>Configuration: {@code bootstrap.servers}, {@code topic}, {@code schema} (all required), {@code
 * mode} ({@code upsert} | {@code changelog}), {@code key.columns} (required in upsert mode), {@code
 * format} ({@code json}, or in upsert mode {@code avro} with {@code schema.file} and an optional
 * {@code schema.id}, or {@code protobuf} with {@code schema.descriptor} and {@code schema.message}),
 * {@code transactional} (default {@code true}), {@code
 * transactional.id} (default the binding's name), {@code staging.topic} (default {@code
 * pravaha-staging.<transactional.id>}), {@code staging.retention.ms}, {@code commit.group} (default
 * {@code pravaha-sink.<transactional.id>}), {@code user}, {@code password}, {@code sasl.mechanism}
 * ({@code PLAIN} | {@code SCRAM-SHA-256} | {@code SCRAM-SHA-512}), the shared {@code tls.*} options, and
 * {@code kafka.<property>} for any other client property -- see {@link KafkaSinkOptions} for the ones
 * refused and why.
 */
public final class KafkaSinkPlugin implements StreamSinkPlugin {

    private static final String HANDLE_PREFIX = "kafka-sink:v1:";
    private static final String RUN_HEADER = "pravaha.run";
    private static final String LABEL_HEADER = "pravaha.label";
    private static final int MAX_BATCH_ROWS = 1000;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STAGING_READ_TIMEOUT = Duration.ofMinutes(2);

    private final KafkaClients clients;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private KafkaSinkOptions options;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private KafkaRecords records;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private TopicPartition staging;

    @SuppressWarnings("NullAway.Init") // set by open(); closeQuietly() clears it and requireOpen() guards every use
    private Producer<byte[], byte[]> stagingProducer;

    @SuppressWarnings("NullAway.Init") // set by open(); closeQuietly() clears it and requireOpen() guards every use
    private Producer<byte[], byte[]> targetProducer;

    @SuppressWarnings("NullAway.Init") // set by open(); closeQuietly() clears it and requireOpen() guards every use
    private Consumer<byte[], byte[]> reader;

    /** Names this process's staged records, so a range never includes another process's. */
    @SuppressWarnings("NullAway.Init") // set by open(), which the engine calls before any write
    private String runId;

    /** The open transaction's label, or -1 when none is open. */
    private long openLabel = -1;

    private final AtomicLong firstStaged = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong lastStaged = new AtomicLong(-1);
    private final AtomicReference<Exception> sendFailure = new AtomicReference<>();
    private final AtomicLong rowsSent = new AtomicLong();
    private final AtomicLong rowsCommitted = new AtomicLong();
    private volatile @Nullable PravahaException fatal;

    /** What {@code ServiceLoader} constructs. */
    public KafkaSinkPlugin() {
        this(KafkaClients.REAL);
    }

    KafkaSinkPlugin(KafkaClients clients) {
        this.clients = clients;
    }

    @Override
    public String name() {
        return "kafka-sink";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.options = new KafkaSinkOptions(context);
        this.records = new KafkaRecords(
                options.schema, options.keyOrdinals, options.changelog, options.valueEncoder, options.keyEncoder);
        this.staging = new TopicPartition(options.stagingTopic, 0);
    }

    @Override
    public Optional<StreamSchema> schema() {
        return Optional.ofNullable(options == null ? null : options.schema);
    }

    @Override
    public List<String> keyColumns() {
        return options == null ? List.of() : options.keyNames;
    }

    /**
     * Upsert mode takes a revising changelog keyed by the view's key and is idempotent; changelog mode
     * takes anything, retractions as {@code "op":"delete"}. Either is transactional when configured so.
     */
    @Override
    public SinkCapabilities capabilities() {
        boolean changelog = options != null && options.changelog;
        return new SinkCapabilities(
                changelog
                        ? EnumSet.of(EmitMode.APPEND, EmitMode.RETRACT)
                        : EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT),
                options == null || options.transactional,
                !changelog,
                MAX_BATCH_ROWS);
    }

    @Override
    public void open() {
        if (options == null) {
            throw new PravahaException(KafkaErrors.BAD_CONFIGURATION, "kafka-sink opened before it was configured");
        }
        try {
            clients.prepareTopics(options);
            targetProducer = clients.producer(options.targetProducer());
            if (options.transactional) {
                stagingProducer = clients.producer(options.stagingProducer());
                reader = clients.consumer(options.stagingConsumer());
                reader.assign(List.of(staging));
                // Fences any earlier producer with this transactional.id and aborts whatever
                // transaction it left open: the half of abortAfter that Kafka does for us.
                targetProducer.initTransactions();
                refuseARecreatedStagingTopic();
            }
        } catch (ConfigException e) {
            closeQuietly();
            throw new ConfigurationException(
                    KafkaErrors.BAD_CONFIGURATION,
                    "sink '" + options.instanceName + "': Kafka refused its client configuration: " + e.getMessage(),
                    e);
        } catch (PravahaException e) {
            closeQuietly();
            throw e;
        } catch (RuntimeException e) {
            closeQuietly();
            throw new PravahaException(
                    KafkaErrors.CONNECT_FAILED,
                    "sink '" + options.instanceName + "' cannot open against " + options.bootstrapServers + ": "
                            + e.getMessage(),
                    e);
        }
        runId = UUID.randomUUID().toString();
        fatal = null;
    }

    /**
     * Stages the batch in the open transaction or, not transactional, sends it to the target topic.
     * Sends are asynchronous; {@link #flush} is where a failed one is reported.
     */
    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    @Override
    public int write(List<RowView> batch) {
        requireOpen();
        rethrowSendFailure();
        if (options.transactional && openLabel < 0) {
            throw new PravahaException(
                    KafkaErrors.WRITE_FAILED,
                    "sink '" + options.instanceName + "' was written with no transaction begun; a transactional "
                            + "sink stages every change under the transaction it belongs to");
        }
        try {
            for (RowView row : batch) {
                KafkaRecords.Encoded encoded = records.encode(row);
                if (options.transactional) {
                    List<Header> headers = List.of(
                            new RecordHeader(RUN_HEADER, runId.getBytes(StandardCharsets.UTF_8)),
                            new RecordHeader(
                                    LABEL_HEADER, Long.toString(openLabel).getBytes(StandardCharsets.UTF_8)));
                    stagingProducer.send(
                            new ProducerRecord<>(
                                    staging.topic(),
                                    staging.partition(),
                                    null,
                                    encoded.key(),
                                    encoded.value(),
                                    headers),
                            staged);
                } else {
                    targetProducer.send(new ProducerRecord<>(options.topic, encoded.key(), encoded.value()), sent);
                }
            }
        } catch (KafkaException e) {
            throw failed("send to " + (options.transactional ? "staging topic " + staging.topic() : options.topic), e);
        }
        rowsSent.addAndGet(batch.size());
        return batch.size();
    }

    private final Callback staged = (metadata, exception) -> {
        if (exception != null) {
            sendFailure.compareAndSet(null, exception);
            return;
        }
        firstStaged.accumulateAndGet(metadata.offset(), Math::min);
        lastStaged.accumulateAndGet(metadata.offset(), Math::max);
    };

    private final Callback sent = (metadata, exception) -> {
        if (exception != null) {
            sendFailure.compareAndSet(null, exception);
        }
    };

    /** Waits for every send so far to be acknowledged by all in-sync replicas, and reports a failure. */
    @Override
    public void flush() {
        requireOpen();
        try {
            (options.transactional ? stagingProducer : targetProducer).flush();
        } catch (KafkaException e) {
            throw failed("flush", e);
        }
        rethrowSendFailure();
    }

    @Override
    public void beginTransaction(long checkpointId) {
        if (options == null || !options.transactional) {
            return;
        }
        requireOpen();
        openLabel = checkpointId;
        firstStaged.set(Long.MAX_VALUE);
        lastStaged.set(-1);
    }

    /**
     * Flushes the staged changes and names them by their offsets. Nothing reaches the target topic
     * here: that is the commit's job, once the checkpoint recording this handle is durable.
     */
    @Override
    public String prepare(long checkpointId) {
        if (options == null || !options.transactional) {
            return "";
        }
        if (openLabel < 0) {
            throw new PravahaException(
                    KafkaErrors.WRITE_FAILED,
                    "prepare(" + checkpointId + ") for sink '" + options.instanceName + "' with no transaction begun");
        }
        flush();
        long first = firstStaged.get();
        long last = lastStaged.get();
        long from = last < 0 ? -1 : first;
        long to = last < 0 ? -1 : last + 1;
        String handle = HANDLE_PREFIX + openLabel + ":" + runId + ":" + from + ":" + to + ":" + options.transactionalId;
        openLabel = -1;
        return handle;
    }

    /**
     * Writes what the handle's range staged to the target topic and records the range's end as
     * committed, in one Kafka transaction. Idempotent: a range the recorded end has already passed was
     * committed before -- by this process, or by one that died after committing it -- and is skipped.
     */
    @Override
    public void commit(String handle) {
        if (options == null || !options.transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        Staged range = parse(handle);
        if (range.to() <= range.from()) {
            return; // nothing was written in that transaction
        }
        long committed = committedMarker();
        if (committed >= range.to()) {
            return;
        }
        try {
            targetProducer.beginTransaction();
        } catch (KafkaException e) {
            throw failed("begin a transaction for " + handle, e);
        }
        try {
            long replayed = replay(range);
            targetProducer.sendOffsetsToTransaction(
                    Map.of(staging, new OffsetAndMetadata(range.to(), "label=" + range.label())),
                    new ConsumerGroupMetadata(options.commitGroup));
            targetProducer.commitTransaction();
            rowsCommitted.addAndGet(replayed);
        } catch (RuntimeException e) {
            if (!isFatal(e)) {
                try {
                    targetProducer.abortTransaction();
                } catch (RuntimeException ignored) {
                    // The original failure is what matters; an abort that fails leaves the
                    // transaction to time out, and nothing reads it in the meantime.
                }
            }
            if (e instanceof PravahaException coded) {
                throw coded;
            }
            throw failed("commit " + handle, e);
        }
    }

    /**
     * Refuses a commit marker beyond the staging topic's end.
     *
     * <p>That happens when the staging topic was deleted and created again: its offsets start from
     * zero while {@code commit.group} still holds the old end, and every new range would look
     * committed already and be skipped -- lost without a word. The fix is the operator's, since only
     * they know the topic was replaced.
     */
    private void refuseARecreatedStagingTopic() {
        long marker = committedMarker();
        Long end = reader.endOffsets(List.of(staging)).get(staging);
        if (end != null && marker > end) {
            throw new PravahaException(
                    KafkaErrors.STAGING_UNUSABLE,
                    "sink '" + options.instanceName + "': group '" + options.commitGroup + "' records offset " + marker
                            + " of staging topic " + staging.topic() + " as committed, but the topic ends at " + end
                            + ". The staging topic was deleted and created again, and every new change would be "
                            + "taken for one already committed. Delete the group (kafka-consumer-groups --delete "
                            + "--group " + options.commitGroup + ") or set a new commit.group, then restart.");
        }
    }

    /** The end of the last range committed to the target, or -1 when none ever was. */
    private long committedMarker() {
        try {
            OffsetAndMetadata marker = reader.committed(Set.of(staging)).get(staging);
            return marker == null ? -1 : marker.offset();
        } catch (KafkaException e) {
            throw failed("read the committed offset of group '" + options.commitGroup + "'", e);
        }
    }

    /** Sends every record of the range this handle's run and label staged to the target topic. */
    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    private long replay(Staged range) {
        Long beginning = reader.beginningOffsets(List.of(staging)).get(staging);
        if (beginning != null && range.from() < beginning) {
            throw new PravahaException(
                    KafkaErrors.STAGING_UNUSABLE,
                    "sink '" + options.instanceName + "' cannot commit label " + range.label()
                            + ": its staged changes, "
                            + "offsets " + range.from() + " to " + range.to() + " of " + staging.topic() + ", were "
                            + "deleted by retention (the topic now starts at " + beginning + "). They are lost to the "
                            + "target topic; raise staging.retention.ms above the longest outage a node may have, and "
                            + "re-register the query to write the view again.");
        }
        reader.seek(staging, range.from());
        long deadline = System.nanoTime() + STAGING_READ_TIMEOUT.toNanos();
        long count = 0;
        AtomicReference<Exception> replayFailure = new AtomicReference<>();
        byte[] run = range.run().getBytes(StandardCharsets.UTF_8);
        byte[] label = Long.toString(range.label()).getBytes(StandardCharsets.UTF_8);
        while (reader.position(staging) < range.to()) {
            if (System.nanoTime() > deadline) {
                throw new PravahaException(
                        KafkaErrors.WRITE_FAILED,
                        "sink '" + options.instanceName + "' read offsets " + range.from() + " to " + range.to()
                                + " of "
                                + staging.topic() + " for " + STAGING_READ_TIMEOUT.toSeconds() + "s and did not reach "
                                + "the end");
            }
            for (ConsumerRecord<byte[], byte[]> record : reader.poll(Duration.ofMillis(200))) {
                if (record.offset() < range.from() || record.offset() >= range.to()) {
                    continue;
                }
                if (!headerIs(record, RUN_HEADER, run) || !headerIs(record, LABEL_HEADER, label)) {
                    continue; // a record another process wrote into the same stretch of the log
                }
                targetProducer.send(new ProducerRecord<>(options.topic, record.key(), record.value()), (m, e) -> {
                    if (e != null) {
                        replayFailure.compareAndSet(null, e);
                    }
                });
                count++;
            }
        }
        // Waited for here rather than left to the commit: a producer that was fenced learns it from
        // these sends, and the offset commit that would follow then waits out max.block.ms instead
        // of failing.
        targetProducer.flush();
        Exception failure = replayFailure.get();
        if (failure != null) {
            throw failed("write staged changes to " + options.topic, failure);
        }
        return count;
    }

    private static boolean headerIs(ConsumerRecord<byte[], byte[]> record, String name, byte[] expected) {
        Header header = record.headers().lastHeader(name);
        return header != null && java.util.Arrays.equals(header.value(), expected);
    }

    /**
     * Nothing to undo: the staged changes are never read unless a handle names them, and the target
     * topic has not been touched.
     */
    @Override
    public void abort(String handle) {
        if (options == null || !options.transactional || handle == null || handle.isEmpty()) {
            return;
        }
        if (parse(handle).label() == openLabel) {
            openLabel = -1;
        }
    }

    /**
     * Nothing to do, for the reasons the class comment gives: opening this sink already aborted the
     * target transaction a dead process left, and what was staged after the restored checkpoint is
     * named by no recorded handle, so it is never committed.
     */
    @Override
    public void abortAfter(long checkpointId) {}

    private Staged parse(String handle) {
        if (handle.startsWith(HANDLE_PREFIX)) {
            String[] parts = handle.substring(HANDLE_PREFIX.length()).split(":", 5);
            if (parts.length == 5) {
                if (!parts[4].equals(options.transactionalId)) {
                    throw new PravahaException(
                            KafkaErrors.WRITE_FAILED,
                            "handle '" + handle + "' belongs to transactional.id '" + parts[4] + "', and this sink is '"
                                    + options.transactionalId + "'. Committing it here would write another sink's "
                                    + "changes to this topic; keep transactional.id the same across restarts of one "
                                    + "binding.");
                }
                try {
                    return new Staged(
                            Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]));
                } catch (NumberFormatException ignored) {
                    // Falls through to the refusal.
                }
            }
        }
        throw new PravahaException(KafkaErrors.WRITE_FAILED, "'" + handle + "' is not a handle this sink wrote");
    }

    /** A prepared transaction: its label, the run that staged it, and its staging offsets. */
    private record Staged(long label, String run, long from, long to) {}

    private void rethrowSendFailure() {
        Exception failure = sendFailure.get();
        if (failure != null) {
            throw failed("send", failure);
        }
    }

    /** A failure after which this producer can do nothing more, wherever it is in the cause chain. */
    private static boolean isFatal(Throwable e) {
        return fenced(e) || causedBy(e, OutOfOrderSequenceException.class) || causedBy(e, AuthorizationException.class);
    }

    /** Fenced by a newer producer with the same transactional.id; the broker says so in two ways. */
    private static boolean fenced(Throwable e) {
        return causedBy(e, ProducerFencedException.class) || causedBy(e, InvalidProducerEpochException.class);
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private PravahaException failed(String what, Exception cause) {
        if (cause instanceof PravahaException coded) {
            return coded;
        }
        String why = cause.getMessage();
        if (fenced(cause)) {
            why = "another producer opened with transactional.id '" + options.transactionalId + "' and fenced this "
                    + "one. Two sinks -- two nodes, or two registrations naming one binding -- must not share a "
                    + "transactional.id; the one that opened last carries on. (The broker also fences a "
                    + "transaction that outlived kafka.transaction.timeout.ms.)";
        }
        PravahaException failure = new PravahaException(
                KafkaErrors.WRITE_FAILED, "sink '" + options.instanceName + "' could not " + what + ": " + why, cause);
        if (isFatal(cause)) {
            fatal = failure;
        }
        return failure;
    }

    private void requireOpen() {
        if (targetProducer == null) {
            throw new PravahaException(
                    KafkaErrors.WRITE_FAILED,
                    "sink '" + (options == null ? "kafka-sink" : options.instanceName)
                            + "' is not open; call open() before writing");
        }
    }

    /** Changes handed to this sink by {@link #write}, staged or sent. */
    public long rowsSent() {
        return rowsSent.get();
    }

    /** Changes this instance has written to the target topic by committing a staged range. */
    public long rowsCommitted() {
        return rowsCommitted.get();
    }

    @Override
    public HealthStatus health() {
        if (targetProducer == null) {
            return HealthStatus.unhealthy("the sink is not open");
        }
        PravahaException failure = fatal;
        return failure == null ? HealthStatus.healthy() : HealthStatus.unhealthy(failure.getMessage());
    }

    /**
     * Closes the clients. Nothing is lost: the staged changes of an open transaction are on the broker,
     * where a restore's commit finds them if a checkpoint recorded them, and are otherwise never read.
     */
    @Override
    public void close() {
        closeQuietly();
    }

    @SuppressWarnings("NullAway") // lets go of the clients; requireOpen() stands before every later use
    private void closeQuietly() {
        for (AutoCloseable client : new AutoCloseable[] {stagingProducer, targetProducer, reader}) {
            if (client == null) {
                continue;
            }
            try {
                if (client instanceof Producer<?, ?> producer) {
                    producer.close(CLOSE_TIMEOUT);
                } else if (client instanceof Consumer<?, ?> consumer) {
                    consumer.close(CLOSE_TIMEOUT);
                }
            } catch (RuntimeException ignored) {
                // Closing anyway; a client that will not close has nothing more to say.
            }
        }
        stagingProducer = null;
        targetProducer = null;
        reader = null;
        openLabel = -1;
    }
}

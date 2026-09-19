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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.WakeupException;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * Reads one Kafka partition, from an exact offset, for one query.
 *
 * <p><strong>{@link #poll} never touches the network.</strong> A fetch thread of this reader's own
 * owns the consumer -- which is not thread-safe, and whose {@code poll} waits for data -- and decodes
 * each record into a bounded queue ({@code buffer.records}); the lane's {@code poll} takes from the
 * queue and returns zero when it is empty. When the queue is full the fetch thread waits, and while
 * the engine has {@link #pause paused} the reader the consumer's partition is paused too, so
 * backpressure reaches the broker rather than the heap.
 *
 * <p><strong>The position is the next offset to read, and it is the only position there is.</strong>
 * It advances past each record as {@link #poll} hands it over (or sets it aside as a dead letter),
 * and past offsets the consumer skipped -- transaction markers, and under {@code read_committed} the
 * records of aborted transactions -- once everything before them has been handed over. Resuming seeks
 * to it, and replay from an offset is deterministic: the same records, the same aborted ones left out.
 * So a restore re-delivers exactly what the checkpoint does not hold. A consumer group's committed
 * offset is never read; with {@code monitoring.group} set, the offset a durable checkpoint recorded is
 * committed there from {@link #checkpointed}, for lag dashboards, and nothing reads it back.
 */
final class KafkaPartitionReader implements PartitionReader {

    private static final Duration FETCH_WAIT = Duration.ofMillis(100);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration COMMIT_RETRY = Duration.ofSeconds(1);

    /** What the fetch thread hands the lane, in offset order. */
    private sealed interface Item permits Decoded, Rejected, Skipped {}

    private record Decoded(long offset, KafkaRecordDecoder.Row row) implements Item {}

    private record Rejected(long offset, byte[] raw, String reason) implements Item {}

    /** Offsets up to {@code next} hold nothing to hand over: a skipped tombstone, a marker, an aborted record. */
    private record Skipped(long next) implements Item {}

    private final TopicPartition partition;
    private final StreamSchema schema;
    private final KafkaRecordDecoder decoder;
    private final boolean skipTombstones;
    private final boolean commitsForMonitoring;
    private final Consumer<byte[], byte[]> consumer;
    private final ArrayBlockingQueue<Item> queue;
    private final Thread fetcher;

    /** The next offset the engine has not been given. Written by the polling thread. */
    private volatile long position;

    /** The consumer's position after the last batch it queued. Written by the fetch thread. */
    private volatile long fetched;

    private volatile boolean paused;
    private volatile boolean closed;
    private volatile PravahaException failure;
    private volatile String commitProblem = "";

    private final AtomicLong commitRequested = new AtomicLong(-1);
    private final AtomicLong committed = new AtomicLong(-1);

    /** Written and read by the fetch thread alone: commit callbacks run inside its poll. */
    private long retryCommitAt;

    KafkaPartitionReader(
            KafkaSourceOptions options, TopicPartition partition, Consumer<byte[], byte[]> consumer, long start) {
        this.partition = partition;
        this.schema = options.schema;
        this.decoder = new KafkaRecordDecoder(
                options.schema, options.format == KafkaSourceOptions.Format.CHANGELOG, options.eventTimeOrdinal);
        this.skipTombstones = options.skipTombstones;
        this.commitsForMonitoring = !options.monitoringGroup.isEmpty();
        this.consumer = consumer;
        this.queue = new ArrayBlockingQueue<>(options.bufferRecords);
        this.position = start;
        this.fetched = start;
        consumer.assign(List.of(partition));
        consumer.seek(partition, start);
        this.fetcher = new Thread(this::fetchLoop, "pravaha-kafka-" + partition.topic() + "-" + partition.partition());
        fetcher.setDaemon(true);
        fetcher.start();
    }

    /**
     * Waits, at most {@code timeout}, until everything up to {@code end} is queued, the queue is full,
     * or the fetch has failed -- so a reader opened over records that already exist has them ready for
     * its first poll.
     *
     * @throws PravahaException if the fetch failed
     */
    void awaitCaughtUp(long end, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (fetched < end && queue.remainingCapacity() > 0 && failure == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (failure != null && queue.isEmpty()) {
            throw failure;
        }
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused || closed || maxRecords <= 0) {
            return 0;
        }
        int written = 0;
        while (written < maxRecords) {
            Item item = queue.peek();
            if (item == null) {
                break;
            }
            switch (item) {
                case Decoded decoded -> {
                    write(sink, decoded);
                    written++;
                    position = decoded.offset() + 1;
                }
                case Rejected rejected -> {
                    String at = partition.topic() + "/" + partition.partition() + "@" + rejected.offset();
                    if (!sink.reject(rejected.raw(), at, rejected.reason())) {
                        // Left at the head of the queue, and the position before it: a restart replays it
                        // and refuses it again, rather than skipping a record nobody set aside.
                        throw new PravahaException(
                                KafkaErrors.UNDECODABLE_RECORD,
                                "the record at " + at + " cannot be read: " + rejected.reason() + ". Configure a "
                                        + "dead-letter queue to set such records aside, or fix the producer.");
                    }
                    position = rejected.offset() + 1;
                }
                case Skipped skipped -> position = Math.max(position, skipped.next());
            }
            queue.poll();
        }
        if (written == 0 && queue.isEmpty() && failure != null) {
            throw failure;
        }
        return written;
    }

    private void write(RecordSink sink, Decoded decoded) {
        KafkaRecordDecoder.Row row = decoded.row();
        RowWriter writer = sink.beginRow();
        try {
            Object[] values = row.values();
            for (int ordinal = 0; ordinal < values.length; ordinal++) {
                set(writer, ordinal, values[ordinal]);
            }
            writer.weight(row.weight())
                    .eventTimestampNanos(row.eventTimeNanos())
                    .sequence(decoded.offset())
                    .commit();
        } catch (RuntimeException e) {
            writer.abort();
            throw e;
        }
    }

    private void set(RowWriter writer, int ordinal, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
        }
        switch (value) {
            case Boolean v -> writer.setBoolean(ordinal, v);
            case Byte v -> writer.setByte(ordinal, v);
            case Short v -> writer.setShort(ordinal, v);
            case Integer v -> writer.setInt(ordinal, v);
            case Long v -> writer.setLong(ordinal, v);
            case Float v -> writer.setFloat(ordinal, v);
            case Double v -> writer.setDouble(ordinal, v);
            case BigDecimal v -> {
                int scale = v.scale();
                writer.setDecimal(ordinal, Decimals.high(v, scale), Decimals.low(v, scale));
            }
            case byte[] v -> writer.setBytes(ordinal, v);
            case String v -> writer.setString(ordinal, v);
            default ->
                throw new IllegalStateException("no writer for " + value.getClass() + " in column "
                        + schema.field(ordinal).name());
        }
    }

    // ---- the fetch thread -------------------------------------------------------------------

    private void fetchLoop() {
        boolean consumerPaused = false;
        long queuedTo = position;
        try {
            while (!closed) {
                if (paused != consumerPaused) {
                    consumerPaused = paused;
                    if (consumerPaused) {
                        consumer.pause(List.of(partition));
                    } else {
                        consumer.resume(List.of(partition));
                    }
                }
                commitIfAsked();
                ConsumerRecords<byte[], byte[]> records;
                try {
                    records = consumer.poll(FETCH_WAIT);
                } catch (WakeupException woken) {
                    continue;
                } catch (RetriableException retrying) {
                    // The client retries these itself; one that surfaces is worth a pause, not a failure.
                    Thread.sleep(FETCH_WAIT.toMillis());
                    continue;
                }
                for (ConsumerRecord<byte[], byte[]> record : records.records(partition)) {
                    if (!offer(decode(record))) {
                        return;
                    }
                    queuedTo = record.offset() + 1;
                }
                long next = consumer.position(partition);
                if (next > queuedTo) {
                    // The consumer stepped over offsets with no record for us -- a commit marker, an
                    // aborted transaction's records. Queued, not applied here, so the position passes
                    // them only once everything before them has been handed over.
                    if (!offer(new Skipped(next))) {
                        return;
                    }
                    queuedTo = next;
                }
                fetched = next;
                if (records.isEmpty() && consumerPaused) {
                    Thread.sleep(FETCH_WAIT.toMillis());
                }
            }
        } catch (OffsetOutOfRangeException gone) {
            failure = new PravahaException(
                    KafkaErrors.RESUME_POINT_GONE,
                    "offset " + queuedTo + " of " + partition + " is no longer in the log: retention deleted records "
                            + "this source had not yet read (" + gone.getMessage() + "). They are lost to every "
                            + "reader. Reading on from the log's start would hide that, so the source stops; "
                            + "raise the topic's retention.ms, and re-register the query to start again.",
                    gone);
        } catch (WakeupException closing) {
            // close() woke a poll or position call; nothing is owed.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (KafkaException e) {
            if (!closed) {
                failure = new PravahaException(
                        KafkaErrors.READ_FAILED, "reading " + partition + " failed: " + e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            if (!closed) {
                failure = new PravahaException(KafkaErrors.READ_FAILED, "reading " + partition + " failed: " + e, e);
            }
        } finally {
            try {
                consumer.close(CLOSE_TIMEOUT);
            } catch (RuntimeException ignored) {
                // Closing; the reader is finished either way.
            }
        }
    }

    private Item decode(ConsumerRecord<byte[], byte[]> record) {
        byte[] value = record.value();
        if (value == null) {
            if (skipTombstones) {
                return new Skipped(record.offset() + 1);
            }
            return new Rejected(
                    record.offset(),
                    record.key() == null ? new byte[0] : record.key(),
                    "the record is a tombstone (a null value), which says a key was deleted without saying "
                            + "what row it held, so there is nothing to retract. Set tombstone: skip to read "
                            + "an upsert topic's values as insertions only, or read kafka-sink's changelog mode");
        }
        try {
            return new Decoded(record.offset(), decoder.decode(value, record.timestamp()));
        } catch (KafkaRecordDecoder.Undecodable e) {
            return new Rejected(record.offset(), value, e.getMessage());
        }
    }

    /** Queues an item, waiting while the queue is full; false once the reader is closed. */
    private boolean offer(Item item) throws InterruptedException {
        while (!closed) {
            if (queue.offer(item, FETCH_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            commitIfAsked();
        }
        return false;
    }

    /** Commits the newest checkpointed offset to {@code monitoring.group}, never waiting for the answer. */
    private void commitIfAsked() {
        long wanted = commitRequested.get();
        if (!commitsForMonitoring || wanted <= committed.get() || System.nanoTime() < retryCommitAt) {
            return;
        }
        committed.set(wanted);
        consumer.commitAsync(Map.of(partition, new OffsetAndMetadata(wanted, "pravaha checkpoint")), (offsets, e) -> {
            if (e == null) {
                commitProblem = "";
                return;
            }
            // Not given up on: the group coordinator is often not ready on a first commit, and the
            // next checkpoint may be an interval away. Tried again after a pause, unless a newer
            // checkpoint's offset has been asked for meanwhile.
            commitProblem = "committing offset " + wanted + " for monitoring failed: " + e;
            committed.compareAndSet(wanted, -1);
            retryCommitAt = System.nanoTime() + COMMIT_RETRY.toNanos();
        });
    }

    // ---- what the engine and the plugin ask ------------------------------------------------

    @Override
    public SourceOffset position() {
        return KafkaSourceOffset.at(partition, position).toSourceOffset();
    }

    /** The next offset the engine has not been given. */
    long nextOffset() {
        return position;
    }

    /**
     * A checkpoint holding {@code offset} is durable. With {@code monitoring.group} set, that offset is
     * committed to the group by the fetch thread -- this returns at once -- so lag monitoring sees
     * what a restore would resume from. Never backwards.
     */
    @Override
    public void checkpointed(SourceOffset offset) {
        KafkaSourceOffset durable = KafkaSourceOffset.parse(offset, partition);
        if (durable != null && commitsForMonitoring) {
            commitRequested.accumulateAndGet(durable.next(), Math::max);
        }
    }

    TopicPartition partition() {
        return partition;
    }

    PravahaException failure() {
        return failure;
    }

    String commitProblem() {
        return commitProblem;
    }

    boolean isClosed() {
        return closed;
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
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        consumer.wakeup();
        try {
            fetcher.join(CLOSE_TIMEOUT.toMillis() * 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
    }
}

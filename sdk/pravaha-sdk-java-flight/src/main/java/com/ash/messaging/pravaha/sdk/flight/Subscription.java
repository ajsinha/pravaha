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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * A client's handle on a running subscription.
 *
 * <p>{@link #run()} parks the calling thread and delivers batches until the subscription is closed
 * or the server ends it. That is deliberate rather than a missing feature: a subscription is a call
 * that does not return, and hiding the thread inside the SDK would mean choosing an execution model
 * for an application that has its own.
 */
public final class Subscription implements AutoCloseable {

    /** The Arrow field metadata marking the weight column. Matches the server's ArrowSchemas. */
    private static final String WEIGHT_METADATA_KEY = "pravaha.weight";

    private final FlightStream stream;
    private final Consumer<ChangeBatch> onBatch;
    private final Consumer<Subscription> onClosed;

    /**
     * What a Flight failure on this stream means in Pravaha's own vocabulary.
     *
     * <p>API-F7. Every other call in this SDK goes through {@code PravahaFlightClient.failureOf},
     * which recovers the server's own code from the wire and turns an {@code UNAVAILABLE} that
     * carries none into {@code PRV-1040} naming the endpoint. A subscription did not: {@link #run}
     * rethrew Arrow's exception as it arrived, so {@code pravaha subscribe} against a node that was
     * not running printed the bare words {@code io exception} -- no code, no address, nothing to
     * act on -- while {@code pravaha queries} against the same dead node named it.
     */
    private final Function<FlightRuntimeException, PravahaClientException> failureOf;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean streamClosed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong rows = new AtomicLong();

    /** Whole commits the server has dropped for this subscriber, as the last batch reported it. */
    private final AtomicLong dropped = new AtomicLong();

    /** Snapshot rows received so far, until the part that ends the snapshot. Run's thread only. */
    private final List<Row> snapshot = new ArrayList<>();

    Subscription(
            FlightStream stream,
            Consumer<ChangeBatch> onBatch,
            Consumer<Subscription> onClosed,
            Function<FlightRuntimeException, PravahaClientException> failureOf) {
        this.stream = stream;
        this.onBatch = onBatch;
        this.onClosed = onClosed;
        this.failureOf = failureOf;
    }

    /**
     * Waits until the server has accepted this subscription, and raises its refusal if it did not.
     *
     * <p>API-F7, the other half. {@code subscribe} builds a lazy stream: nothing is sent until a
     * batch is pulled, so the call returns whether or not there is a server, whether or not the
     * view exists, and whether or not this principal may read it. {@code pravaha subscribe} printed
     * {@code subscribed to x; changes print as they are committed} on the strength of that return
     * -- to stdout, ahead of the failure on stderr -- so a pipeline reading stdout saw a
     * confirmation from a command that was about to exit 1.
     *
     * <p>The schema is what makes it knowable: Flight sends it before any data, and the server's
     * subscription path starts the listener as soon as it has authorized the reader and resolved
     * the view. Waiting for it costs nothing on a working subscription -- the message is already in
     * flight -- and turns "the call returned" into "the server said yes".
     */
    public void awaitOpen() {
        try {
            stream.getSchema();
        } catch (FlightRuntimeException e) {
            throw failureOf.apply(e);
        }
    }

    /**
     * Delivers batches until closed. Blocks.
     *
     * <p>Each batch is one commit. Rows in it are flyweights over the Arrow buffer that carried
     * them and are reused for the next commit, so anything kept past the callback must be copied.
     */
    public void run() {
        running.set(true);
        try {
            while (!closed.get() && stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                List<org.apache.arrow.vector.types.pojo.Field> fields =
                        root.getSchema().getFields();
                // The weight column is found by its metadata mark, not its name. A view is allowed
                // to select a column called whatever the engine's happens to be called, and a
                // subscriber that guessed by name would read that column's values as weights.
                int weightOrdinal = -1;
                for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
                    Map<String, String> metadata = fields.get(ordinal).getMetadata();
                    if (metadata != null && "true".equals(metadata.get(WEIGHT_METADATA_KEY))) {
                        weightOrdinal = ordinal;
                    }
                }
                List<String> columns = new ArrayList<>(fields.size());
                for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
                    if (ordinal != weightOrdinal) {
                        columns.add(fields.get(ordinal).getName());
                    }
                }
                List<Row> batch = new ArrayList<>(root.getRowCount());
                for (int i = 0; i < root.getRowCount(); i++) {
                    batch.add(new Row(root, columns, weightOrdinal).at(i));
                }
                ControlWire.BatchMark mark = markOf(stream.getLatestMetadata());
                if (mark != null && mark.isSnapshot()) {
                    // The snapshot may span several batches and is handed over as one, so its rows
                    // are copied out of each batch before the stream moves past it.
                    for (Row row : batch) {
                        snapshot.add(row.detach());
                    }
                    if (ControlWire.BatchMark.SNAPSHOT_END.equals(mark.kind())) {
                        List<Row> whole = List.copyOf(snapshot);
                        snapshot.clear();
                        batches.incrementAndGet();
                        rows.addAndGet(whole.size());
                        onBatch.accept(new ChangeBatch(whole, true, mark.frontier()));
                    }
                } else if (!batch.isEmpty()) {
                    batches.incrementAndGet();
                    rows.addAndGet(batch.size());
                    long lost = mark == null ? 0L : mark.dropped();
                    dropped.set(lost);
                    onBatch.accept(
                            new ChangeBatch(batch, false, mark == null ? Long.MIN_VALUE : mark.frontier(), lost));
                }
            }
        } catch (RuntimeException e) {
            if (!closed.get()) {
                // Under the same code any other call on this connection would answer with, and
                // naming the same node. A subscriber losing its stream half way through and a
                // caller failing to start one are the same event from two moments, and a client
                // should not need two handlers for it.
                throw e instanceof FlightRuntimeException flight ? failureOf.apply(flight) : e;
            }
            // Closing mid-stream surfaces as a cancellation. Expected, not a failure.
        } finally {
            closeStream();
        }
    }

    /** The batch's mark, when the server sent one: only a snapshot subscription's batches carry it. */
    private static ControlWire.@Nullable BatchMark markOf(org.apache.arrow.memory.@Nullable ArrowBuf metadata) {
        if (metadata == null || metadata.readableBytes() == 0) {
            return null;
        }
        byte[] bytes = new byte[(int) metadata.readableBytes()];
        metadata.getBytes(metadata.readerIndex(), bytes);
        return ControlWire.BatchMark.decode(bytes);
    }

    /** Commits delivered, counting a snapshot as one. */
    public long batches() {
        return batches.get();
    }

    /** Rows delivered across all commits. */
    public long rows() {
        return rows.get();
    }

    /**
     * Whole commits the server dropped for this subscriber, as of the last batch it delivered.
     *
     * <p>STRM-10. A plain subscription drops whole commits rather than slowing the query, which is
     * the right choice and is done cleanly -- every delivered batch is a whole commit, never a
     * fragment. What was missing is telling the client: the count reached an {@code AuditSink}
     * once, when the subscription ended, and only with audit configured, and the only observables
     * this class exposed were {@code rows()}, {@code batches()} and {@code isClosed()}. A dashboard
     * that had lost 58 400 of 59 700 rows looked exactly like one that had received all of them.
     *
     * <p>Zero on a snapshot subscription, which is ended with {@code PRV-6105} rather than skipped
     * past a commit, and zero until a batch arrives after the first drop -- the count rides on the
     * next batch, because that is the next time the server speaks.
     */
    public long dropped() {
        return dropped.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Cancel *and* close, and both are needed. Cancelling tells the server nobody is
            // listening, so it stops assembling batches; closing releases the Arrow buffers this
            // side is holding. Cancelling alone leaks them, which Arrow reports at allocator
            // shutdown -- correctly, because at that point they genuinely are still held.
            try {
                stream.cancel("client closed the subscription", null);
            } catch (RuntimeException e) {
                // Already gone. Cancelling something twice is not a failure.
            }
            onClosed.accept(this);
            if (!running.get()) {
                // Nobody is reading, so nobody else will close it. When run() *is* active the
                // cancellation unblocks it and it closes the stream on its own way out -- on the
                // thread that owns it. Closing a stream from a second thread while the first is
                // inside next() leaves a buffer unreleased, which Arrow reports as a leak when the
                // allocator shuts down, and it is right to.
                closeStream();
            }
        }
    }

    /** Idempotent: {@link #run()} closes on its way out and {@link #close()} may get there first. */
    private void closeStream() {
        if (streamClosed.compareAndSet(false, true)) {
            try {
                stream.close();
            } catch (Exception e) {
                // Nothing useful to do while shutting down, and masking the reason we are
                // shutting down would be worse than a buffer we are about to drop anyway.
            }
        }
    }
}

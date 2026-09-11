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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * A client's handle on a running subscription.
 *
 * <p>{@link #run()} parks the calling thread and delivers batches until the subscription is closed
 * or the server ends it. That is deliberate rather than a missing feature: a subscription is a call
 * that does not return, and hiding the thread inside the SDK would mean choosing an execution model
 * for an application that has its own.
 */
public final class Subscription implements AutoCloseable {

    private final FlightStream stream;
    private final Consumer<ChangeBatch> onBatch;
    private final Consumer<Subscription> onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean streamClosed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong rows = new AtomicLong();

    Subscription(FlightStream stream, Consumer<ChangeBatch> onBatch, Consumer<Subscription> onClosed) {
        this.stream = stream;
        this.onBatch = onBatch;
        this.onClosed = onClosed;
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
                List<String> columns = root.getSchema().getFields().stream()
                        .map(field -> field.getName())
                        .toList();
                List<Row> batch = new ArrayList<>(root.getRowCount());
                for (int i = 0; i < root.getRowCount(); i++) {
                    batch.add(new Row(root, columns).at(i));
                }
                if (!batch.isEmpty()) {
                    batches.incrementAndGet();
                    rows.addAndGet(batch.size());
                    onBatch.accept(new ChangeBatch(batch));
                }
            }
        } catch (RuntimeException e) {
            if (!closed.get()) {
                throw e;
            }
            // Closing mid-stream surfaces as a cancellation. Expected, not a failure.
        } finally {
            closeStream();
        }
    }

    /** Commits delivered. */
    public long batches() {
        return batches.get();
    }

    /** Rows delivered across all commits. */
    public long rows() {
        return rows.get();
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

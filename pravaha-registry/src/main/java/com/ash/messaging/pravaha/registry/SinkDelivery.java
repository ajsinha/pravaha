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
package com.ash.messaging.pravaha.registry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.serving.ViewChange;
import com.ash.messaging.pravaha.serving.ViewChangeListener;

/**
 * Where a registered query's committed changes reach the sink its registration named (ADR-043).
 *
 * <p><strong>A listener on the view's commit, not a second output beside it.</strong> The lane
 * already writes every row into a {@code ViewSink}, and the view already hands each committed batch
 * -- inserts and retractions, in the order they were applied -- to whoever is listening. A sink is
 * one more listener. Three things follow from that and are the reason for the shape:
 *
 * <ul>
 *   <li>A sink receives <em>whole commits</em>, never a half-applied window, which is the same
 *       promise a subscriber gets (STRM-11) and the one a sink needs more: a partial window written
 *       to a table is a wrong total somebody else will read.
 *   <li>Rows are written on the commit's cadence rather than a batch size of this class's
 *       choosing. A size-triggered writer holds the tail of a quiet query's output indefinitely; a
 *       commit-triggered one has nothing left pending when a commit ends.
 *   <li>ADR-043's fan-out needs no machinery. Two registrations sharing a computation are two
 *       listeners on one view, each with its own sink, and a sink that fails detaches itself
 *       without touching the other name or the query.
 * </ul>
 *
 * <p><strong>What delivery this gives, read before assuming more.</strong> A sink never misses a
 * committed change: attached before the feed opens, it sees every commit, and attached to a
 * computation that is already running, its first batch is the view's whole committed contents
 * rather than whatever happened to change next. That first batch is sent at the next commit that
 * carries a change -- the view only calls a listener when something changed -- so a sink joining a
 * computation that never changes again is sent nothing. But it can see a change <em>twice</em>. A restart
 * resumes from the last checkpoint and replays what came after it, and a sink joining a running
 * computation is seeded with rows it may already hold. So this is at-least-once. It is
 * effectively-once for a sink declaring {@code idempotentUpsert}, which is the case the SPI
 * documents; the transactional half of the SPI ({@code beginTransaction}/{@code prepare}/{@code
 * commit}) is not called here, because tying it to a checkpoint is a change to what a checkpoint
 * commits, not to what a commit delivers.
 *
 * <p><strong>A write that fails stops this sink, not the query.</strong> After one failed batch
 * every later batch would be written over a gap, and a sink that is missing a retraction holds a
 * total that is wrong for ever with nothing to say so -- design section 15.5's failure, arrived at
 * by a different road. So the first failure is recorded ({@link #failure()}, {@code PRV-8009}),
 * logged, and the sink is detached. The view, its subscribers, and any other sink on the same
 * computation carry on.
 *
 * <p>Runs on the thread that commits the view, which is the query's own feed. A slow sink is
 * therefore backpressure on the query that feeds it, and on nothing else.
 */
final class SinkDelivery implements ViewChangeListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SinkDelivery.class.getName());

    /** Used when the sink declares no preference. */
    private static final int DEFAULT_MAX_BATCH_ROWS = 1024;

    private final String queryName;
    private final String sinkName;
    private final StreamSinkPlugin plugin;
    private final StreamSchema schema;
    private final RowLayout layout;
    private final int maxBatchRows;
    private final MemoryAccess access;
    private final java.util.function.Consumer<StreamSinkPlugin> release;

    private final AtomicLong rowsWritten = new AtomicLong();
    private final AtomicLong batchesWritten = new AtomicLong();

    /**
     * What to write first, when this sink joined a computation already running: the view's
     * committed contents, read at the first commit this listener hears. Null once used, or when
     * the sink was attached before anything could commit.
     */
    private volatile Supplier<List<Object[]>> seed;

    private volatile AutoCloseable detach;
    private volatile PravahaException failure;
    private volatile boolean closed;

    SinkDelivery(
            String queryName,
            String sinkName,
            StreamSinkPlugin plugin,
            StreamSchema schema,
            MemoryAccess access,
            java.util.function.Consumer<StreamSinkPlugin> release) {
        this.queryName = queryName;
        this.sinkName = sinkName;
        this.plugin = plugin;
        this.schema = schema;
        this.layout = RowLayout.of(schema);
        int declared = plugin.capabilities().maxBatchRows();
        this.maxBatchRows = declared > 0 ? declared : DEFAULT_MAX_BATCH_ROWS;
        this.access = access;
        this.release = release;
    }

    /**
     * Starts listening to a query's commits.
     *
     * @param query the computation whose view this sink follows
     * @param seedFromView true when the computation is already running, so rows committed before
     *     this listener existed must be written first; false when nothing can have committed yet
     */
    void attachTo(RegisteredQuery query, boolean seedFromView) {
        if (seedFromView) {
            seed = query.view()::scan;
        }
        detach = query.attachSink(this);
    }

    @Override
    public void onCommit(List<ViewChange> changes, long frontier) {
        if (closed || failure != null) {
            return;
        }
        try {
            Supplier<List<Object[]>> first = seed;
            if (first != null) {
                // The first commit this listener hears, and the view is committed through it --
                // so its contents are everything this sink missed, this batch included. Writing
                // them replaces this batch rather than preceding it; writing both would put this
                // commit's changes into the sink twice.
                seed = null;
                List<ViewChange> contents = new ArrayList<>();
                for (Object[] row : first.get()) {
                    contents.add(new ViewChange(row, 1));
                }
                write(contents);
            } else {
                write(changes);
            }
            plugin.flush();
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void write(List<ViewChange> changes) {
        for (int from = 0; from < changes.size(); from += maxBatchRows) {
            List<ViewChange> slice = changes.subList(from, Math.min(changes.size(), from + maxBatchRows));
            writeBatch(slice);
        }
    }

    /**
     * Encodes one batch into a single off-heap region, hands it to the plugin, and frees it.
     *
     * <p>The region is released as soon as {@code write} returns: the SPI does not let a plugin
     * keep a row past the call, which is what lets the lanes reuse their own arenas, and this
     * relies on the same contract.
     */
    private void writeBatch(List<ViewChange> batch) {
        if (batch.isEmpty()) {
            return;
        }
        int[] sizes = new int[batch.size()];
        long total = 0;
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = layout.rowSize(payloadBytes(batch.get(i).values()));
            total += sizes[i];
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("a batch of " + batch.size() + " rows for sink '" + sinkName + "' needs "
                    + total + " bytes, more than one region can hold; lower the sink's maxBatchRows");
        }
        try (MemoryRegion region = access.allocate((int) total)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            List<RowView> rows = new ArrayList<>(sizes.length);
            int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                ViewChange change = batch.get(i);
                writer.begin(region, offset, sizes[i]);
                encode(writer, change.values());
                writer.weight(change.weight());
                writer.commit();
                rows.add(new BinaryRowView(layout).wrap(region, offset));
                offset += sizes[i];
            }
            int written = plugin.write(rows);
            rowsWritten.addAndGet(written);
            batchesWritten.incrementAndGet();
        }
    }

    private int payloadBytes(Object[] values) {
        int bytes = 0;
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (!layout.isVariableWidth(ordinal) || values[ordinal] == null) {
                continue;
            }
            bytes += switch (values[ordinal]) {
                case String text -> text.getBytes(StandardCharsets.UTF_8).length;
                case byte[] raw -> raw.length;
                default -> 0;
            };
        }
        return bytes;
    }

    /** The inverse of how a view materialises a row's values; see {@code ServedView.value}. */
    private void encode(BinaryRowWriter writer, Object[] values) {
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            Object value = values[ordinal];
            if (value == null) {
                writer.setNull(ordinal);
                continue;
            }
            switch (value) {
                case Boolean v -> writer.setBoolean(ordinal, v);
                case Byte v -> writer.setByte(ordinal, v);
                case Short v -> writer.setShort(ordinal, v);
                case Integer v -> writer.setInt(ordinal, v);
                case Long v -> writer.setLong(ordinal, v);
                case Float v -> writer.setFloat(ordinal, v);
                case Double v -> writer.setDouble(ordinal, v);
                case String v -> writer.setString(ordinal, v);
                case byte[] v -> writer.setBytes(ordinal, v);
                case BigDecimal v -> {
                    int scale = ((DecimalType) schema.field(ordinal).type()).scale();
                    writer.setDecimal(ordinal, Decimals.high(v, scale), Decimals.low(v, scale));
                }
                default ->
                    throw new IllegalStateException(
                            "column '" + schema.field(ordinal).name() + "' holds a "
                                    + value.getClass().getSimpleName() + ", which cannot be written to a sink row");
            }
        }
    }

    private void fail(RuntimeException cause) {
        failure = new PravahaException(
                RegistryErrors.SINK_WRITE_FAILED,
                "sink '" + sinkName + "' for query '" + queryName + "' failed and has been detached: "
                        + cause.getMessage() + ". Writing later batches over the one that failed would leave "
                        + "the sink missing changes with nothing to say so; the query and its view carry on, "
                        + "and re-registering the query against the sink starts it again from the view's "
                        + "contents.",
                cause);
        LOG.log(System.Logger.Level.ERROR, failure.getMessage(), cause);
        close();
    }

    /** Why this sink stopped, or empty while it is writing. */
    Optional<PravahaException> failure() {
        return Optional.ofNullable(failure);
    }

    String sinkName() {
        return sinkName;
    }

    long rowsWritten() {
        return rowsWritten.get();
    }

    long batchesWritten() {
        return batchesWritten.get();
    }

    /** Stops listening and lets go of the sink. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        AutoCloseable listening = detach;
        if (listening != null) {
            try {
                listening.close();
            } catch (Exception ignored) {
                // Removing a listener from a list; nothing to report that would help anybody.
            }
        }
        release.accept(plugin);
    }
}

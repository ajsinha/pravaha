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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
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
 * <p><strong>What delivery this gives</strong> depends on what the sink declares, and {@link
 * #guarantee()} says which, in the words an operator reads at registration:
 *
 * <ul>
 *   <li><strong>Transactional, on a checkpointed query: exactly once.</strong> Writes between
 *       checkpoints go into a transaction; at a checkpoint's cut the sink is prepared and its handle
 *       recorded in the checkpoint; once the checkpoint is durable the handle is committed; a restore
 *       commits what the restored checkpoint recorded and abandons the rest, which the replay writes
 *       again. {@link RegisteredQuery#cutOutput} holds the ordering argument.
 *   <li><strong>Transactional, on a query that takes no checkpoints: at least once.</strong> There is
 *       nothing to tie a transaction to, so each view commit is prepared and committed as its own;
 *       atomic per commit, repeated after a restart.
 *   <li><strong>Idempotent upsert: effectively once.</strong> A replay writes records with the values
 *       they already hold; the end state is right though the write happened twice.
 *   <li><strong>Neither: at least once.</strong> A restart re-delivers what was written after the
 *       last checkpoint. {@code DeduplicatingSink} does not rescue this and is not wired: it drops
 *       rows at or below the highest <em>sequence</em> written, and a view commit's changes carry no
 *       sequence at all -- they are values and a weight, grouped by a commit whose boundaries a
 *       replay does not reproduce. Deduplicating on a number that is not there would drop live rows.
 * </ul>
 *
 * <p><strong>Seeding stays inside the protocol.</strong> A sink attached to a computation that is
 * already running is sent the view's contents first, so it misses nothing that happened before it
 * existed -- written into its open transaction, so for a transactional sink the seed commits with
 * the first checkpoint after it or not at all. A sink the restored checkpoint recorded is not
 * seeded as the computation starts, because it already holds that checkpoint's view; one whose name
 * is registered again after the computation has moved on is sent only the difference. A seed still
 * pending at a cut is written before the sink is prepared, so a checkpoint never records a sink as
 * holding a view it was never sent.
 *
 * <p><strong>A write that fails stops this sink, not the query.</strong> After one failed batch
 * every later batch would be written over a gap, and a sink that is missing a retraction holds a
 * total that is wrong for ever with nothing to say so -- design section 15.5's failure, arrived at
 * by a different road. So the first failure is recorded ({@link #failure()}, {@code PRV-8009}),
 * logged, and the sink is detached. The view, its subscribers, and any other sink on the same
 * computation carry on. The guarantee ends there too: re-attaching it starts it again from the
 * view's contents.
 *
 * <p>Every call into the plugin is made under this object's monitor. Writes arrive on the thread
 * that commits the view, a cut on the lane's thread, a commit on the checkpointing thread; the SPI
 * promises a sink one call at a time, and this is what keeps that promise. A slow sink is therefore
 * backpressure on the query that feeds it, and on nothing else.
 */
final class SinkDelivery implements ViewChangeListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SinkDelivery.class.getName());

    /**
     * Says what registration {@code name}'s sink is promised, where an operator will see it.
     *
     * <p>At registration and once, because the answer depends on the sink's declaration and on
     * whether this node checkpoints, and neither changes while the query runs. An operator who reads
     * "at-least-once" here knows before the first reconciliation that duplicates are possible.
     */
    void announce(String name) {
        LOG.log(System.Logger.Level.INFO, "query '" + name + "' writes to sink '" + sinkName() + "', " + guarantee());
    }

    /** Used when the sink declares no preference. */
    private static final int DEFAULT_MAX_BATCH_ROWS = 1024;

    /** Where a sink's section of a checkpoint is kept, by registration name. */
    static final String STATE_PREFIX = "sink:";

    private static final int SECTION_FORMAT = 1;

    private final String queryName;
    private final String sinkName;
    private final StreamSinkPlugin plugin;
    private final StreamSchema schema;
    private final RowLayout layout;
    private final int maxBatchRows;
    private final MemoryAccess access;
    private final java.util.function.Consumer<StreamSinkPlugin> release;

    /** {@link SinkFactory#redact}, so a PRV-8009 message carries no configured credential. */
    private final java.util.function.UnaryOperator<String> redact;

    private final boolean transactional;
    private final boolean idempotent;
    private final boolean acceptsRetractions;

    private final AtomicLong rowsWritten = new AtomicLong();
    private final AtomicLong batchesWritten = new AtomicLong();

    /** Prepared at a cut and not yet committed, oldest first. Guarded by this. */
    private final Deque<Prepared> prepared = new ArrayDeque<>();

    /** The label of the open transaction. Guarded by this. */
    private long label;

    /** Whether the computation takes checkpoints; decided at attach. */
    private volatile boolean checkpointed;

    /**
     * What to write first, when this sink joined a computation already running: the view's
     * committed contents, or the difference from a restored checkpoint's, read when it is written.
     * Null once used, or when there is nothing to catch up on.
     */
    private volatile Supplier<List<ViewChange>> seed;

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
        this(queryName, sinkName, plugin, schema, access, release, java.util.function.UnaryOperator.identity());
    }

    SinkDelivery(
            String queryName,
            String sinkName,
            StreamSinkPlugin plugin,
            StreamSchema schema,
            MemoryAccess access,
            java.util.function.Consumer<StreamSinkPlugin> release,
            java.util.function.UnaryOperator<String> redact) {
        this.queryName = queryName;
        this.sinkName = sinkName;
        this.plugin = plugin;
        this.schema = schema;
        this.redact = redact;
        this.layout = RowLayout.of(schema);
        SinkCapabilities capabilities = plugin.capabilities();
        int declared = capabilities.maxBatchRows();
        this.maxBatchRows = declared > 0 ? declared : DEFAULT_MAX_BATCH_ROWS;
        this.access = access;
        this.release = release;
        this.transactional = capabilities.transactional();
        this.idempotent = capabilities.idempotentUpsert();
        this.acceptsRetractions = capabilities.accepts(EmitMode.UPSERT) || capabilities.accepts(EmitMode.RETRACT);
    }

    /**
     * Starts listening to a query's commits.
     *
     * @param query the computation whose view this sink follows
     * @param joining true when the computation is already running, so rows committed before this
     *     listener existed must be written first; false when this registration is starting it and
     *     nothing can have committed yet
     */
    void attachTo(RegisteredQuery query, boolean joining) {
        checkpointed = query.checkpointed();
        Optional<Restored> restored = query.claimRestoredSink(queryName);
        synchronized (this) {
            try {
                if (restored.isPresent()) {
                    recover(restored.get());
                    if (joining || restored.get().carriedView()) {
                        // The computation was restored by another name and has moved on since, or
                        // the section was carried from somewhere else entirely -- the version a
                        // cutover replaced (ADR-046). Either way this sink holds the view the
                        // section names and not this computation's, so it is owed the difference,
                        // not the whole.
                        byte[] base = restored.get().view();
                        seed = () -> query.view().changesSince(base, acceptsRetractions);
                    }
                    // Starting the computation from a checkpoint of its own: the view is the
                    // restored one, exactly what this sink holds, and nothing has committed yet.
                } else if (joining || (transactional && query.view().size() > 0)) {
                    // Joining: the view holds what was committed before this sink existed. Starting
                    // from a restored view with no record of this sink, and transactional: whatever
                    // it was written before the crash was never committed, so it holds none of it.
                    // A sink that is not transactional is not re-seeded there -- what it was
                    // written is still in it, and re-sending the view would repeat all of it.
                    seed = () -> {
                        List<ViewChange> contents = new ArrayList<>();
                        for (Object[] row : query.view().scan()) {
                            contents.add(new ViewChange(row, 1));
                        }
                        return contents;
                    };
                }
                if (transactional) {
                    label = query.nextTransactionLabel();
                    plugin.beginTransaction(label);
                }
            } catch (RuntimeException e) {
                fail(e);
                return;
            }
        }
        detach = query.attachSink(this);
    }

    /**
     * Picks up where a restored checkpoint left this sink: commits every handle it recorded, then
     * abandons everything after it.
     *
     * <p>Committed again even though the process that prepared them probably committed them: it may
     * have died after the checkpoint was stored and before the commit was sent, and nothing here can
     * tell which. The SPI requires commit to be idempotent for exactly this.
     */
    private void recover(Restored restored) {
        for (Prepared each : restored.handles()) {
            plugin.commit(each.handle());
        }
        plugin.abortAfter(restored.checkpointId());
    }

    @Override
    public synchronized void onCommit(List<ViewChange> changes, long frontier) {
        if (closed || failure != null) {
            return;
        }
        try {
            if (!writeSeed()) {
                write(changes);
            }
            plugin.flush();
            if (transactional && !checkpointed) {
                // No checkpoint will ever prepare this transaction, so the commit is its boundary.
                String handle = plugin.prepare(label);
                plugin.commit(handle);
                plugin.beginTransaction(++label);
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /**
     * Writes the seed if one is owed, and says whether it did.
     *
     * <p>Read at the moment it is written, which is after a view commit: the view is committed
     * through the batch that would otherwise be written, so the seed contains it. It replaces that
     * batch rather than preceding it; writing both would put the batch into the sink twice.
     */
    private boolean writeSeed() {
        Supplier<List<ViewChange>> first = seed;
        if (first == null) {
            return false;
        }
        seed = null;
        write(first.get());
        return true;
    }

    /**
     * This sink's part of checkpoint {@code checkpointId}'s cut, called by {@link
     * RegisteredQuery#cutOutput} under the commit lock, with the view just committed through the
     * marker.
     *
     * <p>A seed still owed is written first: the checkpoint is about to record this sink as holding
     * the checkpoint's view, and without the seed it would not. Then a transactional sink is
     * prepared and its next transaction begun, before any later commit can reach it.
     *
     * @return this sink's section of the checkpoint -- every handle prepared and not yet committed,
     *     oldest first, since a checkpoint that failed after its cut left its handle to the next one
     *     -- or empty when this sink has failed and has no part in it
     */
    synchronized Optional<byte[]> cut(long checkpointId) {
        if (closed || failure != null) {
            return Optional.empty();
        }
        try {
            if (writeSeed()) {
                plugin.flush();
            }
            if (transactional) {
                prepared.addLast(new Prepared(checkpointId, plugin.prepare(checkpointId)));
                label = checkpointId + 1;
                plugin.beginTransaction(label);
            }
            return Optional.of(encode(List.copyOf(prepared), null));
        } catch (RuntimeException e) {
            fail(e);
            return Optional.empty();
        }
    }

    /**
     * Checkpoint {@code checkpointId} is durable: commits every handle it recorded.
     *
     * <p>Those are exactly the ones prepared at or before its cut. One prepared at a later cut is
     * left, since its checkpoint may yet fail.
     */
    synchronized void durable(long checkpointId) {
        if (closed || failure != null) {
            return;
        }
        try {
            while (!prepared.isEmpty() && prepared.peekFirst().checkpointId() <= checkpointId) {
                plugin.commit(prepared.peekFirst().handle());
                prepared.removeFirst();
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /**
     * Lets go of this sink for a registration being dropped, committing what it was written.
     *
     * <p>A drop is not a crash. Nothing will restore this name, so nothing will replay what its open
     * transaction holds, and a sink left with it uncommitted would lose the tail of the query's
     * output for no reason. A shutdown is different, and goes through {@link #close()}: the query
     * is recovered at the next start and replays from its checkpoint, so committing here would
     * repeat that tail.
     */
    synchronized void commitAndRelease() {
        if (!closed && failure == null && transactional) {
            try {
                while (!prepared.isEmpty()) {
                    plugin.commit(prepared.removeFirst().handle());
                }
                plugin.commit(plugin.prepare(label));
            } catch (RuntimeException e) {
                fail(e);
                return;
            }
        }
        close();
    }

    /**
     * What this sink is promised, in the words an operator reads at registration.
     *
     * <p>Only what is true on this node: a transactional sink on a query that takes no checkpoints is
     * at least once, whatever the sink could do with them.
     */
    String guarantee() {
        return label(transactional, idempotent, checkpointed)
                        .toLowerCase(java.util.Locale.ROOT)
                        .replace('_', '-') + ": " + why();
    }

    /**
     * The guarantee in one word, as a listing shows it: {@code EXACTLY_ONCE}, {@code
     * EFFECTIVELY_ONCE} or {@code AT_LEAST_ONCE}. The SPI's {@code SinkCapabilities.guarantee()}
     * cannot say this -- it calls idempotent upsert exactly once and knows nothing of checkpoints
     * (HLP-4) -- so anything reporting a sink's guarantee asks here.
     */
    static String label(boolean transactional, boolean idempotent, boolean checkpointed) {
        if (transactional && checkpointed) {
            return "EXACTLY_ONCE";
        }
        if (idempotent) {
            // Transactional without checkpoints lands here too: each commit is its own transaction
            // and a restart repeats it, but an upsert repeated rewrites the values already there.
            return "EFFECTIVELY_ONCE";
        }
        return "AT_LEAST_ONCE";
    }

    private String why() {
        if (transactional && checkpointed) {
            return "the sink is transactional, so what is written between checkpoints is "
                    + "prepared at each checkpoint's cut, recorded in the checkpoint, and committed once the "
                    + "checkpoint is durable";
        }
        if (transactional) {
            return "the sink is transactional, but this query takes no checkpoints "
                    + "(pravaha.checkpoint.directory is unset), so each commit is its own transaction and a "
                    + "restart delivers again"
                    + (idempotent
                            ? "; the sink upserts idempotently, so the repeat rewrites the values already there"
                            : "");
        }
        if (idempotent) {
            return "the sink upserts idempotently, so what a restart delivers again "
                    + "rewrites records with the values they already hold";
        }
        return "the sink appends and is not transactional, so a restart delivers again what "
                + "was written after the last checkpoint; a view commit carries no sequence to "
                + "deduplicate the repeat on";
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
        // Redacted here rather than at each surface. The text after "detached:" is the plugin's own
        // and can echo the connection string it was configured with, and PRV-8009 now reaches the
        // Flight listing, `pravaha queries` and both SDKs as well as the HTTP API. A surface can
        // forget; the place the failure is written down cannot. The cause keeps its own message for
        // the log's stack trace, which is the operator's.
        failure = new PravahaException(
                RegistryErrors.SINK_WRITE_FAILED,
                redact.apply("sink '" + sinkName + "' for query '" + queryName + "' failed and has been detached: "
                        + cause.getMessage() + ". Writing later batches over the one that failed would leave "
                        + "the sink missing changes with nothing to say so; the query and its view carry on, "
                        + "and re-registering the query against the sink starts it again from the view's "
                        + "contents."),
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

    String queryName() {
        return queryName;
    }

    /**
     * The label of the transaction this sink has open.
     *
     * <p>Read when a cutover moves the sink to another computation: the SPI's labels only increase,
     * across restarts and across a change of computation, so the new one's checkpoint ids have to
     * continue above this.
     */
    synchronized long label() {
        return label;
    }

    long rowsWritten() {
        return rowsWritten.get();
    }

    long batchesWritten() {
        return batchesWritten.get();
    }

    /**
     * Stops listening and lets go of the sink, committing nothing and abandoning nothing. Idempotent.
     *
     * <p>What is prepared stays prepared: a checkpoint recording it may be durable, and a restore
     * commits it. What is open is left to the sink's close, which for a sink whose transactions die
     * with their connection is the abort; otherwise the next restore's {@code abortAfter} is.
     */
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

    /** The checkpoint key this delivery's section is stored under. */
    static String stateKey(SinkDelivery delivery) {
        return STATE_PREFIX + delivery.queryName;
    }

    /** A transaction prepared at a cut: the checkpoint that cut it, and the sink's name for it. */
    record Prepared(long checkpointId, String handle) {}

    /**
     * What a restored checkpoint recorded about one sink.
     *
     * @param checkpointId the checkpoint restored
     * @param handles the transactions it recorded as prepared, to commit
     * @param view the view contents this sink holds once they are committed: the checkpoint's own
     *     view, or -- for a section carried forward unclaimed -- the view of the checkpoint it came from
     */
    record Restored(long checkpointId, List<Prepared> handles, byte[] view, boolean carriedView) {

        /** A section whose view is one this sink holds and no checkpoint of this computation does. */
        static Restored carrying(long checkpointId, byte[] view) {
            return new Restored(checkpointId, List.of(), view, true);
        }

        /** The section to store for this sink while no registration has claimed it. */
        byte[] carried() {
            return encode(handles, view);
        }

        static Restored decode(long checkpointId, byte[] section, byte[] checkpointView) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(section))) {
                int format = in.readInt();
                if (format != SECTION_FORMAT) {
                    throw new IllegalStateException("a sink's section of checkpoint " + checkpointId + " is format "
                            + format + " and this engine reads " + SECTION_FORMAT);
                }
                int count = in.readInt();
                List<Prepared> handles = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    long id = in.readLong();
                    byte[] handle = new byte[in.readInt()];
                    in.readFully(handle);
                    handles.add(new Prepared(id, new String(handle, StandardCharsets.UTF_8)));
                }
                int viewLength = in.readInt();
                byte[] view = checkpointView;
                if (viewLength >= 0) {
                    view = new byte[viewLength];
                    in.readFully(view);
                }
                return new Restored(checkpointId, List.copyOf(handles), view, viewLength >= 0);
            } catch (IOException e) {
                throw new UncheckedIOException("a sink's section of checkpoint " + checkpointId + " is unreadable", e);
            }
        }
    }

    /**
     * A section: the handles, and the view they leave the sink holding when that is not the
     * checkpoint's own ({@code null} for the usual case, where it is).
     */
    private static byte[] encode(List<Prepared> handles, byte[] view) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(SECTION_FORMAT);
            out.writeInt(handles.size());
            for (Prepared each : handles) {
                byte[] handle = each.handle().getBytes(StandardCharsets.UTF_8);
                out.writeLong(each.checkpointId());
                out.writeInt(handle.length);
                out.write(handle);
            }
            if (view == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(view.length);
                out.write(view);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}

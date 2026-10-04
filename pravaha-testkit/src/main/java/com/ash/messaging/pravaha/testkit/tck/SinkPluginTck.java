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
package com.ash.messaging.pravaha.testkit.tck;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The conformance suite every sink plugin must pass (TCKCOLLECT-1).
 *
 * <p>A sink's {@link SinkCapabilities} are promises the engine acts on at registration: whether a
 * query that revises its rows may write here, and what delivery the query can claim. As with {@link
 * SourcePluginTck}, the suite does not believe the declaration; it exercises each claim against what the
 * destination actually holds afterwards:
 *
 * <ul>
 *   <li>every sink: a batch is written whole and counted, an empty batch writes nothing, and close and
 *       health behave;
 *   <li>a sink accepting {@link EmitMode#UPSERT} or {@link EmitMode#RETRACT}: it names its key, a
 *       retraction removes the record, and an upsert replaces it by key;
 *   <li>a sink declaring {@link SinkCapabilities#idempotentUpsert()}: a replayed batch changes nothing;
 *   <li>a sink declaring {@link SinkCapabilities#transactional()}: nothing is visible before {@code
 *       commit}, a commit repeated with the same handle applies once, an aborted transaction is never
 *       visible, handles name their transactions, and after a restart {@code abortAfter} discards what
 *       came after the restored checkpoint while a recorded handle still commits.
 * </ul>
 *
 * <p>A case whose capability the sink does not declare is reported skipped, not passed. Extend it and
 * supply the destination:
 *
 * <pre>{@code
 * class MySinkTck extends SinkPluginTck {
 *     protected StreamSinkPlugin createSink() { ... }        // configured and opened, same destination each call
 *     protected List<String> readBack() { ... }               // what a reader of the destination sees now
 *     protected Object[] record(int i) { return new Object[] {"k" + i, (long) i}; }
 *     protected String render(Object[] values) { ... }        // how readBack shows that record
 * }
 * }</pre>
 */
public abstract class SinkPluginTck {

    /**
     * A configured, opened sink over the test's destination. Each call is a new instance over the
     * <em>same</em> destination, which is what a restart is; the destination starts empty per test.
     */
    protected abstract StreamSinkPlugin createSink();

    /** What a reader of the destination sees now -- committed records only -- one string per record. */
    protected abstract List<String> readBack();

    /**
     * The values of the {@code i}-th distinct record, in the sink's schema order. Records of different
     * {@code i} must have different keys, for a keyed sink.
     */
    protected abstract Object[] record(int i);

    /** How {@link #readBack()} shows a record of these values. */
    protected abstract String render(Object[] values);

    /**
     * The {@code i}-th record with the same key and different other values, for a sink accepting
     * {@link EmitMode#UPSERT}. Unsupported unless overridden; the upsert case is skipped without it.
     */
    protected Object[] revision(int i) {
        throw new UnsupportedOperationException("no revision supplied");
    }

    /** The schema rows are built in: the sink's own, which a sink with a fixed shape declares. */
    protected StreamSchema schemaOf(StreamSinkPlugin sink) {
        return sink.schema()
                .orElseThrow(() -> new IllegalStateException(
                        "this sink declares no schema(); override schemaOf to say what rows it is given"));
    }

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    private final List<StreamSinkPlugin> opened = new ArrayList<>();

    @AfterEach
    void releaseSinkTck() {
        opened.forEach(SinkPluginTck::closeQuietly);
        arena.close();
    }

    private StreamSinkPlugin sink() {
        StreamSinkPlugin sink = createSink();
        opened.add(sink);
        return sink;
    }

    // ------------------------------------------------------------------ every sink

    @Test
    void declaresANameAVersionAndConsistentCapabilities() {
        StreamSinkPlugin sink = sink();
        assertThat(sink.name()).isNotBlank();
        assertThat(sink.name())
                .as("names appear in configuration keys, so no spaces or upper case")
                .isEqualTo(sink.name().toLowerCase(Locale.ROOT).strip());
        assertThat(sink.version()).isNotNull();
        SinkCapabilities caps = sink.capabilities();
        assertThat(caps).isNotNull();
        assertThat(caps.emitModes())
                .as("a sink accepts at least one changelog mode")
                .isNotEmpty();
        assertThat(caps.maxBatchRows()).isNotNegative();
        if (revising(caps)) {
            assertThat(sink.keyColumns())
                    .as("a sink that takes updates or retractions must say which columns identify a record, "
                            + "or the engine cannot check the query's key against it")
                    .isNotEmpty();
        }
        sink.schema()
                .ifPresent(schema -> sink.keyColumns()
                        .forEach(key -> assertThat(schema.fields().stream()
                                        .anyMatch(field -> field.name().equalsIgnoreCase(key)))
                                .as("key column '%s' is in the declared schema", key)
                                .isTrue()));
    }

    @Test
    void aBatchIsWrittenWholeAndCounted() {
        StreamSinkPlugin sink = sink();
        StreamSchema schema = schemaOf(sink);
        List<RowView> batch = List.of(row(schema, 1, record(0)), row(schema, 1, record(1)), row(schema, 1, record(2)));

        int written = inOneTransaction(sink, 1, () -> sink.write(batch));

        assertThat(written).as("write returns how many rows it wrote").isEqualTo(3);
        assertThat(readBack()).containsExactlyInAnyOrder(render(record(0)), render(record(1)), render(record(2)));
    }

    @Test
    void anEmptyBatchWritesNothing() {
        StreamSinkPlugin sink = sink();
        int written = inOneTransaction(sink, 1, () -> sink.write(List.of()));
        assertThat(written).isZero();
        assertThat(readBack()).isEmpty();
    }

    @Test
    void closingIsIdempotentAndHealthIsReported() {
        StreamSinkPlugin sink = createSink();
        assertThat(sink.health()).isNotNull();
        assertThat(sink.health().state()).isNotNull();
        sink.close();
        sink.close();
    }

    // ------------------------------------------------------------------ keyed sinks

    @Test
    void aRetractionRemovesTheRecordItNames() {
        StreamSinkPlugin sink = sink();
        assumeTrue(revising(sink.capabilities()), "an append-only sink takes no retractions");
        StreamSchema schema = schemaOf(sink);
        inOneTransaction(sink, 1, () -> sink.write(List.of(row(schema, 1, record(0)), row(schema, 1, record(1)))));

        inOneTransaction(sink, 2, () -> sink.write(List.of(row(schema, -1, record(0)))));

        assertThat(readBack()).containsExactly(render(record(1)));
    }

    @Test
    void anUpsertReplacesTheRecordByKey() {
        StreamSinkPlugin sink = sink();
        assumeTrue(sink.capabilities().accepts(EmitMode.UPSERT), "the sink does not take upserts");
        Object[] revised;
        try {
            revised = revision(0);
        } catch (UnsupportedOperationException none) {
            assumeTrue(false, "the test supplies no revision(i)");
            return;
        }
        StreamSchema schema = schemaOf(sink);
        inOneTransaction(sink, 1, () -> sink.write(List.of(row(schema, 1, record(0)))));

        inOneTransaction(sink, 2, () -> sink.write(List.of(row(schema, -1, record(0)), row(schema, 1, revised))));

        assertThat(readBack()).containsExactly(render(revised));
    }

    @Test
    void anIdempotentSinkAbsorbsAReplayedBatch() {
        StreamSinkPlugin sink = sink();
        assumeTrue(sink.capabilities().idempotentUpsert(), "the sink does not declare idempotent upserts");
        StreamSchema schema = schemaOf(sink);
        List<RowView> batch = List.of(row(schema, 1, record(0)), row(schema, 1, record(1)));

        inOneTransaction(sink, 1, () -> sink.write(batch));
        // What recovery does: replay from the last checkpoint, rows the sink has already seen included.
        inOneTransaction(sink, 2, () -> sink.write(batch));

        assertThat(readBack())
                .as("a sink claiming idempotent upserts is what makes a replay harmless; a duplicate here "
                        + "is one nobody notices until a reconciliation fails")
                .containsExactlyInAnyOrder(render(record(0)), render(record(1)));
    }

    // ------------------------------------------------------------------ transactional sinks

    @Test
    void nothingIsVisibleBeforeCommitAndACommitRepeatedAppliesOnce() {
        StreamSinkPlugin sink = sink();
        assumeTrue(sink.capabilities().transactional(), "the sink is not transactional");
        StreamSchema schema = schemaOf(sink);

        sink.beginTransaction(1);
        sink.write(List.of(row(schema, 1, record(0)), row(schema, 1, record(1))));
        sink.flush();
        assertThat(readBack())
                .as("written into an open transaction is not visible")
                .isEmpty();
        String handle = sink.prepare(1);
        assertThat(handle)
                .as("a prepared transaction is named, for the checkpoint to record")
                .isNotEmpty();
        assertThat(readBack()).as("prepared is durable and still not visible").isEmpty();

        sink.commit(handle);
        sink.commit(handle); // a restore may send the commit again: it cannot know the first arrived
        assertThat(readBack())
                .as("committed once, however often the commit is sent")
                .containsExactlyInAnyOrder(render(record(0)), render(record(1)));
    }

    @Test
    void anAbortedTransactionIsNeverVisible() {
        StreamSinkPlugin sink = sink();
        assumeTrue(sink.capabilities().transactional(), "the sink is not transactional");
        StreamSchema schema = schemaOf(sink);

        sink.beginTransaction(1);
        sink.write(List.of(row(schema, 1, record(0))));
        String handle = sink.prepare(1);
        sink.abort(handle);

        assertThat(readBack()).isEmpty();
    }

    @Test
    void aRestartCommitsWhatTheCheckpointRecordedAndDiscardsWhatCameAfter() {
        StreamSinkPlugin before = sink();
        assumeTrue(before.capabilities().transactional(), "the sink is not transactional");
        StreamSchema schema = schemaOf(before);

        before.beginTransaction(1);
        before.write(List.of(row(schema, 1, record(0))));
        String recorded = before.prepare(1);
        before.beginTransaction(2);
        before.write(List.of(row(schema, 1, record(1))));
        String unrecorded = before.prepare(2);
        assertThat(unrecorded).as("each transaction has a handle of its own").isNotEqualTo(recorded);
        closeQuietly(before); // the process dies: checkpoint 1 was stored, checkpoint 2 never was

        StreamSinkPlugin after = sink();
        after.commit(recorded);
        after.abortAfter(1);

        assertThat(readBack())
                .as("the restored checkpoint's transaction is committed and the later one, which the replay "
                        + "is about to write again, is not")
                .containsExactly(render(record(0)));
    }

    // ------------------------------------------------------------------ helpers

    /** Runs {@code write} as the engine would: inside one committed transaction when the sink is transactional. */
    protected final int inOneTransaction(StreamSinkPlugin sink, long label, java.util.function.IntSupplier write) {
        boolean transactional = sink.capabilities().transactional();
        if (transactional) {
            sink.beginTransaction(label);
        }
        int written = write.getAsInt();
        sink.flush();
        if (transactional) {
            sink.commit(sink.prepare(label));
        }
        return written;
    }

    /** A row of {@code values} in {@code schema}'s layout with {@code weight}, as a delivery hands it to a sink. */
    protected final RowView row(StreamSchema schema, long weight, Object... values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            throw new IllegalStateException("the sink TCK's arena is exhausted");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
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
                default -> throw new IllegalArgumentException("no row column for a " + value.getClass());
            }
        }
        writer.weight(weight).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    private static boolean revising(SinkCapabilities caps) {
        return caps.accepts(EmitMode.UPSERT) || caps.accepts(EmitMode.RETRACT);
    }

    private static void closeQuietly(StreamSinkPlugin sink) {
        try {
            sink.close();
        } catch (RuntimeException ignored) {
            // A test that already failed is reporting the failure that matters.
        }
    }
}

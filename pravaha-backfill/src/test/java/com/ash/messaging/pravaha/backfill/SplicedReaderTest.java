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
package com.ash.messaging.pravaha.backfill;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seam between history and the present.
 *
 * <p>Every assertion here is really the same one: after the splice, the query's view of each key
 * must be the value the store holds, and each change must have been applied exactly once. The ways
 * of getting that wrong are what the individual tests name -- a change lost in the gap, a change
 * applied twice in the overlap, an old snapshot value landing after the change that superseded it.
 *
 * <p>None of those failures announce themselves in production. The query keeps running and the
 * numbers are merely a little wrong, which is why the interesting cases are asserted directly
 * rather than inferred from a total.
 */
class SplicedReaderTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("accounts")
            .field("id", Types.int64())
            .field("balance", Types.int64())
            .field("version", Types.int64())
            .build();

    /** One row: a key, a value, and the store's version of it. */
    private record Row(long id, long balance, long version) {}

    /** A reader over a fixed list, which may be added to while it is being read. */
    private static final class ListReader implements PartitionReader {
        private final ArrayDeque<Row> rows = new ArrayDeque<>();
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
        private long delivered;

        ListReader(List<Row> initial) {
            rows.addAll(initial);
        }

        void add(Row row) {
            rows.add(row);
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && !rows.isEmpty()) {
                Row row = rows.poll();
                RowWriter writer = sink.beginRow();
                writer.setLong(0, row.id())
                        .setLong(1, row.balance())
                        .setLong(2, row.version())
                        .weight(1L)
                        .eventTimestampNanos(row.version())
                        .sequence(++delivered)
                        .commit();
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset("n=" + delivered);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {
            arena.close();
        }
    }

    /** Collects what the splice emits, in order. */
    private static final class Collector implements PartitionReader.RecordSink {
        private final List<Row> rows = new ArrayList<>();
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
        private final RowLayout layout = RowLayout.of(SCHEMA);

        @Override
        public RowWriter beginRow() {
            long handle = arena.allocate(layout.rowSize(64));
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            return new CapturingWriter(writer, () -> {
                RowView view = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
                rows.add(new Row(view.getLong(0), view.getLong(1), view.getLong(2)));
            });
        }

        /** The final value the query would hold for each key, applying the rows in order. */
        Map<Long, Long> finalState() {
            Map<Long, Long> state = new LinkedHashMap<>();
            rows.forEach(row -> state.put(row.id(), row.balance()));
            return state;
        }
    }

    /** Delegates to a real writer and runs a hook on commit. */
    private record CapturingWriter(BinaryRowWriter delegate, Runnable onCommit) implements RowWriter {
        @Override
        public StreamSchema schema() {
            return delegate.schema();
        }

        @Override
        public RowWriter setNull(int ordinal) {
            delegate.setNull(ordinal);
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            delegate.setBoolean(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            delegate.setByte(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            delegate.setShort(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            delegate.setInt(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            delegate.setLong(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            delegate.setFloat(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            delegate.setDouble(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            delegate.setDecimal(ordinal, high, low);
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            delegate.setString(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            delegate.setBytes(ordinal, value);
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            delegate.weight(weight);
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            delegate.eventTimestampNanos(nanos);
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            delegate.sequence(sequence);
            return this;
        }

        @Override
        public int commit() {
            int offset = delegate.commit();
            onCommit.run();
            return offset;
        }

        @Override
        public void abort() {
            delegate.abort();
        }
    }

    private static final SpliceSpec SPEC = new SpliceSpec(List.of(0), 2);

    @Test
    void withNoChangesDuringTheScanHistoryArrivesUntouched() {
        ListReader history = new ListReader(List.of(new Row(1, 100, 1), new Row(2, 200, 1)));
        ListReader changes = new ListReader(List.of());
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            drain(reader, out);

            assertThat(out.finalState()).containsExactly(entry(1L, 100L), entry(2L, 200L));
            assertThat(reader.snapshotRowsSuperseded()).isZero();
            assertThat(reader.phase()).isEqualTo(BackfillPhase.LIVE);
        }
    }

    @Test
    void aChangeMadeDuringTheScanIsAppliedOnceAndWins() {
        // The overlap. Key 1 is updated while history is being read, so the snapshot's older value
        // must not be emitted after it -- and the change itself must be emitted exactly once.
        ListReader history = new ListReader(List.of(new Row(1, 100, 1), new Row(2, 200, 1)));
        ListReader changes = new ListReader(List.of(new Row(1, 999, 2)));
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            drain(reader, out);

            // As a map, not a sequence: key 1's snapshot row was suppressed, so its only row
            // arrives during the replay and lands after key 2's. Which is correct, and asserting an
            // order here would be asserting the shape of the seam rather than the answer.
            assertThat(out.finalState()).containsOnly(entry(1L, 999L), entry(2L, 200L));
            assertThat(out.rows.stream().filter(row -> row.id() == 1).count())
                    .as("the change was applied more than once")
                    .isEqualTo(1);
            assertThat(reader.snapshotRowsSuperseded()).isEqualTo(1);
        }
    }

    @Test
    void aChangeTheScanAlreadySawIsNotAppliedTwice() {
        // The exact-overlap case. The change landed just before the scan reached that row, so the
        // snapshot carries the new value and the feed carries the same version. Emitting both
        // applies one write twice -- which for a balance is money appearing from nowhere, and every
        // value involved is one the store really held.
        ListReader history = new ListReader(List.of(new Row(1, 999, 2)));
        ListReader changes = new ListReader(List.of(new Row(1, 999, 2)));
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            drain(reader, out);

            assertThat(out.rows).hasSize(1);
            assertThat(out.finalState()).containsOnly(entry(1L, 999L));
            assertThat(reader.snapshotRowsSuperseded())
                    .as("equal versions mean the feed will deliver it; the snapshot copy is the duplicate")
                    .isEqualTo(1);
        }
    }

    @Test
    void aChangeArrivingAfterTheScanHasPassedThatKeyStillWins() {
        // The other order, and the one a naive implementation gets wrong. The snapshot row for key 1
        // has already been emitted when the change turns up; the change is replayed afterwards, so
        // the newer value lands last.
        ListReader history = new ListReader(List.of(new Row(1, 100, 1)));
        ListReader changes = new ListReader(List.of());
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            reader.poll(out, 1);
            changes.add(new Row(1, 555, 2));
            drain(reader, out);

            assertThat(out.finalState()).containsExactly(entry(1L, 555L));
            assertThat(out.rows).hasSize(2);
        }
    }

    @Test
    void nothingIsLostInTheGap() {
        // The failure that reading history first would cause: a key that only ever appears as a
        // change during the scan. Nothing in the snapshot mentions it, so if the feed were started
        // afterwards it would never be seen at all.
        ListReader history = new ListReader(List.of(new Row(1, 100, 1)));
        ListReader changes = new ListReader(List.of(new Row(7, 700, 5)));
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            drain(reader, out);

            assertThat(out.finalState()).containsExactly(entry(1L, 100L), entry(7L, 700L));
        }
    }

    @Test
    void changesReplayInTheOrderTheStoreAppliedThem() {
        // Two updates to one key during the scan. Replayed out of order, the query ends up holding
        // the older of the two -- and every value in it is a value the store really had, which is
        // what makes it hard to notice.
        ListReader history = new ListReader(List.of(new Row(1, 100, 1)));
        ListReader changes = new ListReader(List.of(new Row(1, 200, 2), new Row(1, 300, 3)));
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            drain(reader, out);

            assertThat(out.rows.stream().map(Row::balance).toList()).containsExactly(200L, 300L);
            assertThat(out.finalState()).containsExactly(entry(1L, 300L));
        }
    }

    @Test
    void aScanSlowerThanTheChangeRateFailsSayingSo() {
        // A long scan, which is the only condition under which the buffer can fill: with a short
        // one the splice reaches the replay before the changes pile up. The first version of this
        // test used a one-row history and never filled anything.
        List<Row> history = new ArrayList<>();
        for (long id = 0; id < 500; id++) {
            history.add(new Row(id, id, 1));
        }
        List<Row> many = new ArrayList<>();
        for (long id = 0; id < 50; id++) {
            many.add(new Row(1000 + id, id, 1));
        }
        ListReader historyReader = new ListReader(history);
        ListReader changes = new ListReader(many);
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(historyReader, changes, SCHEMA, SPEC, 10)) {
            assertThatThrownBy(() -> drain(reader, out))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4010")
                    .hasMessageContaining("narrow the backfill window");
        }
    }

    @Test
    void theOffsetCarriesThePhaseSoACrashResumesInIt() {
        ListReader history = new ListReader(List.of(new Row(1, 100, 1)));
        ListReader changes = new ListReader(List.of());
        Collector out = new Collector();

        try (SplicedReader reader = new SplicedReader(history, changes, SCHEMA, SPEC, 1000)) {
            assertThat(reader.position().token()).startsWith("SNAPSHOT:");
            drain(reader, out);
            assertThat(reader.position().token()).startsWith("LIVE:");
        }
    }

    @Test
    void aThrottleLimitsHistoryAndNeverTheChangeFeed() {
        // The distinction the whole control exists for. Reading history is load the backfill is
        // adding to the store and may be slowed; the change feed is load the store is already
        // carrying, and refusing to read it does not help the store -- it only makes the buffer the
        // thing that overflows.
        List<Row> history = new ArrayList<>();
        for (long id = 0; id < 100; id++) {
            history.add(new Row(id, id, 1));
        }
        ListReader historyReader = new ListReader(history);
        ListReader changes = new ListReader(List.of(new Row(500, 5, 9), new Row(501, 5, 9)));
        Collector out = new Collector();

        // One row per second, so a tenth-of-a-second budget rounds to the minimum of one row.
        BackfillThrottle throttle =
                new BackfillThrottle(1, 1, Duration.ofMillis(10).toNanos());

        try (SplicedReader reader = new SplicedReader(historyReader, changes, SCHEMA, SPEC, 1000, throttle)) {
            reader.poll(out, 50);

            assertThat(out.rows)
                    .as("the throttle did not limit the history scan")
                    .hasSize(1);
            assertThat(reader.bufferedRows())
                    .as("the change feed was throttled along with the scan")
                    .isEqualTo(2);
            assertThat(reader.rowsPerSecond()).isEqualTo(1);
        }
    }

    @Test
    void anUnthrottledSpliceSaysSoRatherThanReportingAFakeRate() {
        try (SplicedReader reader =
                new SplicedReader(new ListReader(List.of()), new ListReader(List.of()), SCHEMA, SPEC, 10)) {
            assertThat(reader.rowsPerSecond()).isEqualTo(-1);
        }
    }

    @Test
    void aVersionColumnThatCannotBeComparedIsRefusedAtConstruction() {
        StreamSchema text = StreamSchema.builder("accounts")
                .field("id", Types.int64())
                .field("version", Types.string())
                .build();

        assertThatThrownBy(() -> new SplicedReader(
                        new ListReader(List.of()), new ListReader(List.of()), text, new SpliceSpec(List.of(0), 1), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderable as an integer");
    }

    @Test
    void aSpliceWithoutAKeyIsRefused() {
        assertThatThrownBy(() -> new SpliceSpec(List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history and the present cannot be joined");
    }

    private static void drain(SplicedReader reader, Collector out) {
        for (int i = 0; i < 200; i++) {
            reader.poll(out, 4);
            if (reader.phase() == BackfillPhase.LIVE) {
                reader.poll(out, 4);
                return;
            }
        }
        throw new AssertionError("the backfill never reached the live phase");
    }

    private static Map.Entry<Long, Long> entry(long key, long value) {
        return Map.entry(key, value);
    }
}

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServedView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader that turns another query's answer into a source (ADR-056): the answer's changes, not
 * the upstream's changelog; a snapshot compared with what was consumed; an overflow recovered by a
 * fresh snapshot; and a checkpoint's image that is exactly what the lane had at the freeze.
 */
class UpstreamReaderTest {

    private static final StreamSchema ROWS = StreamSchema.builder("u")
            .field("k", Types.string())
            .field("v", Types.int64())
            .build();

    private final ServedView view = new ServedView("u", ROWS, List.of(0), 1_000_000, Retention.forever());

    @Test
    void anUpsertIsFedAsTheRowItReplacedLeavingAndTheNewOneEntering() {
        UpstreamReader reader = follow(null);
        commit(1, "a", 10L, 1);
        assertThat(drain(reader)).containsExactly("+a=10");

        // No retraction applied: the view replaces a's row, and the reader is told how the answer changed.
        commit(2, "a", 20L, 1);
        assertThat(drain(reader)).containsExactly("-a=10", "+a=20");

        commit(3, "a", 20L, -1);
        commit(3, "a", 20L, -1);
        assertThat(drain(reader)).containsExactly("-a=20");
    }

    @Test
    void aSnapshotIsComparedWithWhatWasConsumedSoOnlyTheDifferenceIsFed() {
        commit(1, "a", 1L, 1);
        commit(1, "b", 2L, 1);
        UpstreamReader first = follow(null);
        assertThat(drain(first)).containsExactlyInAnyOrder("+a=1", "+b=2");
        first.position();
        byte[] image = first.cut();
        first.close();

        commit(2, "b", 3L, 1);
        commit(2, "c", 4L, 1);
        view.applyValues(new Object[] {"a", 1L}, -1, 2);
        view.commit(2);

        UpstreamReader restored = follow(image);
        assertThat(drain(restored)).containsExactlyInAnyOrder("-a=1", "-b=2", "+b=3", "+c=4");
    }

    @Test
    void theCutIsTheImageAsTheLaneHadItAtTheFreezeNotAsItIsNow() {
        UpstreamReader reader = follow(null);
        commit(1, "a", 1L, 1);
        drain(reader);
        reader.position(); // the checkpoint's freeze: the lane has been handed a=1
        commit(2, "a", 5L, 1);
        commit(2, "b", 6L, 1);
        drain(reader); // handed over after the freeze, before the marker
        byte[] cut = reader.cut();

        UpstreamReader restored = follow(cut);
        assertThat(drain(restored))
                .as("restored from the cut, it has consumed only a=1: the rest is fed again")
                .containsExactlyInAnyOrder("-a=1", "+a=5", "+b=6");
    }

    @Test
    void anOverflowIsRecoveredByAFreshSnapshotAndNothingIsLost() {
        UpstreamReader reader = follow(null);
        commit(1, "a", 1L, 1);
        drain(reader);
        List<Object[]> many = new ArrayList<>();
        for (int i = 0; i <= UpstreamReader.QUEUE_LIMIT; i++) {
            many.add(new Object[] {"x" + i, (long) i});
        }
        reader.onAnswer(List.of(), many, 2); // past the limit: dropped, and a snapshot is asked for
        assertThat(reader.needsSnapshot()).isTrue();
        view.applyValues(new Object[] {"b", 2L}, 1, 3);
        view.commit(3);
        reader.resnapshot();
        assertThat(drain(reader)).containsExactly("+b=2");
        assertThat(reader.imageSize()).isEqualTo(2);
    }

    @Test
    void anImageOfAnotherUpstreamIsRefused() {
        UpstreamReader reader = follow(null);
        commit(1, "a", 1L, 1);
        drain(reader);
        reader.position();
        byte[] image = reader.cut();
        assertThatThrownBy(() -> new UpstreamReader("other", view, ROWS, image))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.CHAIN_UNSUPPORTED));
    }

    private UpstreamReader follow(byte[] image) {
        UpstreamReader reader = new UpstreamReader("u", view, ROWS, image);
        reader.follow(() -> {});
        return reader;
    }

    private void commit(long frontier, String key, long value, long weight) {
        view.applyValues(new Object[] {key, value}, weight, frontier);
        view.commit(frontier);
    }

    /** Everything the reader will write now, as {@code +k=v} or {@code -k=v}. */
    private static List<String> drain(UpstreamReader reader) {
        List<String> written = new ArrayList<>();
        PartitionReader.RecordSink sink = () -> new Capture(written);
        while (reader.poll(sink, 1000) > 0) {
            // keep going
        }
        return written;
    }

    private static final class Capture implements RowWriter {
        private final List<String> into;
        private final Map<Integer, Object> values = new HashMap<>();
        private long weight;

        Capture(List<String> into) {
            this.into = into;
        }

        @Override
        public StreamSchema schema() {
            return ROWS;
        }

        @Override
        public RowWriter setNull(int ordinal) {
            values.put(ordinal, null);
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            values.put(ordinal, low);
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            values.put(ordinal, value);
            return this;
        }

        @Override
        public RowWriter weight(long value) {
            this.weight = value;
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            return this;
        }

        @Override
        public int commit() {
            into.add((weight < 0 ? "-" : "+") + values.get(0) + "=" + values.get(1));
            return 0;
        }

        @Override
        public void abort() {}
    }
}

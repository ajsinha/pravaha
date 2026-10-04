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
package com.ash.messaging.pravaha.runtime.exec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator.AggregateCall.Kind;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALLNULLAGG-1: {@code SUM}, {@code AVG}, {@code MIN} and {@code MAX} of a group with no non-null
 * value are NULL, in the unkeyed and the keyed aggregate, read once or maintained -- and a NULL
 * answer survives a checkpoint, is retracted as a NULL, and returns when a retraction leaves only
 * nulls. {@code COUNT(col)} is 0 and {@code COUNT(*)} counts the rows.
 */
class AllNullAggregateTest {

    private static final StreamSchema IN = StreamSchema.builder("txn")
            .field("k", Types.string())
            .field("v", Types.int64().withNullable(true))
            .build();

    /** SUM, AVG, MIN, MAX, COUNT(v), COUNT(*). */
    private static final List<AggregateOperator.AggregateCall> ALL = List.of(
            new AggregateOperator.AggregateCall(Kind.SUM, 1, "s"),
            new AggregateOperator.AggregateCall(Kind.AVG, 1, "a"),
            new AggregateOperator.AggregateCall(Kind.MIN, 1, "mn"),
            new AggregateOperator.AggregateCall(Kind.MAX, 1, "mx"),
            new AggregateOperator.AggregateCall(Kind.COUNT, 1, "c"),
            new AggregateOperator.AggregateCall(Kind.COUNT, -1, "n"));

    /** The retractable ones: SUM, AVG, COUNT(v). */
    private static final List<AggregateOperator.AggregateCall> RETRACTABLE = List.of(
            new AggregateOperator.AggregateCall(Kind.SUM, 1, "s"),
            new AggregateOperator.AggregateCall(Kind.AVG, 1, "a"),
            new AggregateOperator.AggregateCall(Kind.COUNT, 1, "c"));

    private static StreamSchema out(boolean keyed, List<AggregateOperator.AggregateCall> calls) {
        StreamSchema.Builder builder = StreamSchema.builder("out");
        if (keyed) {
            builder.field("k", Types.string());
        }
        for (AggregateOperator.AggregateCall call : calls) {
            builder.field(call.outputName(), Types.int64().withNullable(call.kind() != Kind.COUNT));
        }
        return builder.build();
    }

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
    private final List<String> emitted = new ArrayList<>();

    private RowProcessor recorder(StreamSchema schema) {
        return row -> emitted.add(render(row, schema));
    }

    private static String render(RowView row, StreamSchema schema) {
        StringBuilder text = new StringBuilder(row.weight() > 0 ? "+" : "-");
        for (int i = 0; i < schema.fieldCount(); i++) {
            text.append(i == 0 ? "" : "|");
            if (row.isNull(i)) {
                text.append("null");
            } else if (schema.field(i).type().typeName() == com.ash.messaging.pravaha.api.data.TypeName.STRING) {
                text.append(row.getString(i));
            } else {
                text.append(row.getLong(i));
            }
        }
        return text.toString();
    }

    private void feed(RowProcessor aggregate, String key, @Nullable Long value, long weight) {
        RowLayout layout = RowLayout.of(IN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(16));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, key);
        if (value == null) {
            writer.setNull(1);
        } else {
            writer.setLong(1, value);
        }
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
        aggregate.process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    @Test
    void aBoundedGlobalReadOfOnlyNullsAnswersNullAndCountsZero() {
        StreamSchema schema = out(false, ALL);
        GlobalAggregate aggregate = new GlobalAggregate(
                new AggregateOperator(ScanOperator.of("txn", IN), schema, List.of(), ALL), arena, recorder(schema));
        feed(aggregate, "x", null, 1);
        feed(aggregate, "x", null, 1);
        aggregate.emit();
        assertThat(emitted).containsExactly("+null|null|null|null|0|2");
        arena.close();
    }

    @Test
    void aBoundedKeyedReadAnswersNullOnlyForTheGroupWithNoValue() {
        StreamSchema schema = out(true, ALL);
        KeyedAggregate aggregate = new KeyedAggregate(
                new AggregateOperator(ScanOperator.of("txn", IN), schema, List.of(0), ALL),
                IN,
                arena,
                recorder(schema),
                100);
        feed(aggregate, "x", null, 1);
        feed(aggregate, "y", 0L, 1);
        feed(aggregate, "y", null, 1);
        aggregate.emit();
        assertThat(emitted).containsExactly("+x|null|null|null|null|0|1", "+y|0|0|0|0|1|2");
        arena.close();
    }

    @Test
    void aMaintainedGlobalAnswerGoesBackToNullAndSurvivesACheckpoint() throws IOException {
        StreamSchema schema = out(false, RETRACTABLE);
        AggregateOperator operator = new AggregateOperator(ScanOperator.of("txn", IN), schema, List.of(), RETRACTABLE);
        GlobalAggregate aggregate = new GlobalAggregate(operator, arena, recorder(schema));
        aggregate.drivenContinuously();

        feed(aggregate, "x", 5L, 1);
        aggregate.emitIncremental();
        feed(aggregate, "x", 5L, -1);
        feed(aggregate, "x", null, 1);
        aggregate.emitIncremental();
        assertThat(emitted).containsExactly("+5|5|1", "-5|5|1", "+null|null|0");

        byte[] checkpoint = checkpoint(aggregate::writeTo);
        assertThat(firstInt(checkpoint)).as("a NULL answer marks the section").isNegative();

        GlobalAggregate restored = new GlobalAggregate(operator, arena, recorder(schema));
        restored.drivenContinuously();
        restored.readFrom(new DataInputStream(new ByteArrayInputStream(checkpoint)));
        emitted.clear();
        feed(restored, "x", 7L, 1);
        restored.emitIncremental();
        assertThat(emitted)
                .as("the restored aggregate retracts the NULL it published")
                .containsExactly("-null|null|0", "+7|7|1");

        // Without a NULL in the published answer the section is written as it always was.
        assertThat(firstInt(checkpoint(restored::writeTo))).isEqualTo(RETRACTABLE.size());
        arena.close();
    }

    @Test
    void aMaintainedKeyedAnswerGoesBackToNullAndSurvivesACheckpoint() throws IOException {
        StreamSchema schema = out(true, RETRACTABLE);
        AggregateOperator operator = new AggregateOperator(ScanOperator.of("txn", IN), schema, List.of(0), RETRACTABLE);
        KeyedAggregate aggregate = new KeyedAggregate(operator, IN, arena, recorder(schema), 100);
        aggregate.drivenContinuously();

        feed(aggregate, "x", null, 1);
        feed(aggregate, "y", 3L, 1);
        aggregate.emitIncremental();
        assertThat(emitted).containsExactlyInAnyOrder("+x|null|null|0", "+y|3|3|1");

        emitted.clear();
        feed(aggregate, "y", 3L, -1);
        feed(aggregate, "y", null, 1);
        aggregate.emitIncremental();
        assertThat(emitted).containsExactly("-y|3|3|1", "+y|null|null|0");

        byte[] checkpoint = checkpoint(aggregate::writeTo);
        assertThat(firstInt(checkpoint)).isNegative();
        KeyedAggregate restored = new KeyedAggregate(operator, IN, arena, recorder(schema), 100);
        restored.drivenContinuously();
        restored.readFrom(new DataInputStream(new ByteArrayInputStream(checkpoint)));
        emitted.clear();
        feed(restored, "x", 2L, 1);
        restored.emitIncremental();
        assertThat(emitted).containsExactly("-x|null|null|0", "+x|2|2|1");
        arena.close();
    }

    @Test
    void aCheckpointWrittenBeforeNullAnswersStillRestores() throws IOException {
        // The pre-ALLNULLAGG-1 layout of a keyed section: the call count, the groups, then each
        // published answer's values with no flags. Built by hand, as the old writer wrote it.
        StreamSchema schema = out(true, RETRACTABLE);
        AggregateOperator operator = new AggregateOperator(ScanOperator.of("txn", IN), schema, List.of(0), RETRACTABLE);
        KeyedAggregate writer = new KeyedAggregate(operator, IN, arena, recorder(schema), 100);
        writer.drivenContinuously();
        feed(writer, "y", 4L, 1);
        writer.emitIncremental();
        byte[] old = checkpoint(writer::writeTo);
        assertThat(firstInt(old)).as("no NULL: the old layout, byte for byte").isEqualTo(RETRACTABLE.size());

        KeyedAggregate restored = new KeyedAggregate(operator, IN, arena, recorder(schema), 100);
        restored.drivenContinuously();
        restored.readFrom(new DataInputStream(new ByteArrayInputStream(old)));
        emitted.clear();
        feed(restored, "y", 4L, -1);
        restored.emitIncremental();
        assertThat(emitted).containsExactly("-y|4|4|1");
        arena.close();
    }

    private interface Writer {
        void writeTo(java.io.DataOutput out) throws IOException;
    }

    private static byte[] checkpoint(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.writeTo(out);
        }
        return bytes.toByteArray();
    }

    private static int firstInt(byte[] bytes) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(bytes, 4))).readInt();
    }
}

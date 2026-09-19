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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;

/**
 * A keyed {@code GROUP BY} with no window, over an input that ends.
 *
 * <p>The counterpart to {@link GlobalAggregate}, and the reason it exists is worth stating because
 * the planner refuses the same SQL over a stream. Over an endless stream a keyed aggregate holds one
 * accumulator per distinct key forever, which is unbounded state and correctly refused (PRV-2050).
 * Over a <em>bounded</em> read -- a scan of a maintained view, which stops -- the state is bounded by
 * the scan and released when it ends, so {@code SELECT tier, COUNT(*) FROM user_volume GROUP BY
 * tier} is an ordinary question and this is what answers it.
 *
 * <p>Accumulation is <strong>weighted</strong>, exactly as in {@link GlobalAggregate}: a row with
 * weight {@code -1} decrements rather than taking a separate retract path. A group whose weights
 * cancel to zero is not emitted, because it has no rows left in it.
 *
 * <p>State lives on the heap rather than in the arena, which is the right trade here and not a
 * shortcut. This runs on the read path, where the result is already materialised as objects and
 * already capped; an off-heap implementation would buy nothing and would have to be maintained
 * alongside the windowed one. It is bounded all the same -- {@code maxGroups} refuses rather than
 * grows, because "bounded by the scan" is only true if the scan is.
 */
final class KeyedAggregate implements RowProcessor {

    /** A cap on distinct groups, so a wide GROUP BY refuses instead of exhausting the heap. */
    static final int DEFAULT_MAX_GROUPS = 1_000_000;

    private final AggregateOperator operator;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final List<Integer> keyOrdinals;
    private final StreamSchema inputSchema;
    private final int maxGroups;

    /** Each call's argument type and each output column's type, for {@link AggregateSlots} (HLP-1). */
    private final TypeName[] argumentTypes;

    private final TypeName[] outputTypes;

    // Insertion-ordered so that two runs of the same query return rows in the same order. There is
    // no ORDER BY to make it meaningful, but an answer that shuffles between identical calls is one
    // people waste an afternoon on.
    private final Map<Key, Group> groups = new LinkedHashMap<>();

    KeyedAggregate(
            AggregateOperator operator,
            StreamSchema inputSchema,
            RowArena arena,
            RowProcessor downstream,
            int maxGroups) {
        this.operator = operator;
        this.inputSchema = inputSchema;
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(operator.outputSchema());
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);
        this.keyOrdinals = operator.groupKeyOrdinals();
        this.maxGroups = maxGroups;
        this.argumentTypes = AggregateSlots.typesOf(
                inputSchema,
                operator.aggregates().stream()
                        .map(AggregateOperator.AggregateCall::argumentOrdinal)
                        .toList());
        this.outputTypes = AggregateSlots.typesOf(
                operator.outputSchema(),
                java.util.stream.IntStream.range(0, operator.outputSchema().fieldCount())
                        .boxed()
                        .toList());
        if (keyOrdinals.isEmpty()) {
            throw new IllegalArgumentException("an unkeyed aggregate belongs in GlobalAggregate");
        }
    }

    @Override
    public void process(RowView row) {
        long weight = row.weight();
        if (weight == 0) {
            // A consolidated row contributes nothing and must not be counted.
            return;
        }
        Group group = groups.computeIfAbsent(keyOf(row), key -> {
            if (groups.size() >= maxGroups) {
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "this GROUP BY has produced " + maxGroups + " distinct groups, which is the ceiling "
                                + "for a single read. Narrow it with a WHERE clause, or group by fewer columns");
            }
            return new Group(operator.aggregates().size());
        });
        group.accumulate(row, weight, operator.aggregates(), argumentTypes, ordinal -> read(row, ordinal));
    }

    /**
     * ADR-039 item 6: folds a pre-combined partial into the group it belongs to, exactly as {@link
     * GlobalAggregate#processPartial} does for the unkeyed case -- see that method's javadoc for
     * why the scaled arithmetic is correct under retraction and why only {@code COUNT}/{@code SUM}
     * ever reach here.
     *
     * @param partial the group's key values, in {@link #keyOrdinals} order, then one column per
     *     aggregate call in {@code operator.aggregates()} order -- the same layout {@link #emit}
     *     itself writes
     * @param weight the Z-set weight the summarised rows arrived (or are being retracted) with
     */
    void processPartial(RowView partial, long weight) {
        if (weight == 0) {
            return;
        }
        Object[] keyValues = new Object[keyOrdinals.size()];
        for (int i = 0; i < keyValues.length; i++) {
            keyValues[i] = partial.isNull(i) ? null : readOutputColumn(partial, i);
        }
        Group group = groups.computeIfAbsent(new Key(keyValues), key -> {
            if (groups.size() >= maxGroups) {
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "this GROUP BY has produced " + maxGroups + " distinct groups, which is the ceiling "
                                + "for a single read. Narrow it with a WHERE clause, or group by fewer columns");
            }
            return new Group(operator.aggregates().size());
        });
        group.accumulatePartial(partial, keyOrdinals.size(), weight, operator.aggregates());
    }

    /**
     * One value from a partial row, read by the type its column has in this aggregate's own
     * output schema -- {@link #read} reads a raw input row by {@link #inputSchema} instead, which
     * is the wrong schema for a partial: its leading columns are group-key values already in the
     * aggregate's output shape, not columns of the stream the aggregate reads.
     */
    private Object readOutputColumn(RowView row, int ordinal) {
        return switch (operator.outputSchema().field(ordinal).type().typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case STRING -> row.getString(ordinal);
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "cannot group by a column of type "
                                + operator.outputSchema().field(ordinal).type().typeName() + " yet");
        };
    }

    /** Emits one row per surviving group. Called when the input ends. */
    void emit() {
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (Map.Entry<Key, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            if (group.rowCount == 0) {
                // Every row in this group was retracted. Emitting it would report a group that is
                // no longer there.
                continue;
            }
            long handle = arena.allocate(layout.rowSize(256));
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit a grouped aggregate result");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));

            // Group keys occupy the leading output columns, in the order Calcite put them in the
            // aggregate's row type, then one column per aggregate call.
            Object[] key = entry.getKey().values;
            for (int i = 0; i < key.length; i++) {
                writeKey(i, key[i]);
            }
            for (int i = 0; i < calls.size(); i++) {
                AggregateSlots.write(
                        writer, key.length + i, group.valueOf(i, calls.get(i)), outputTypes[key.length + i]);
            }
            writer.weight(1L)
                    .eventTimestampNanos(group.lastTimestamp)
                    .sequence(group.lastSequence)
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    private void writeKey(int outputOrdinal, Object value) {
        if (value == null) {
            writer.setNull(outputOrdinal);
            return;
        }
        TypeName type = operator.outputSchema().field(outputOrdinal).type().typeName();
        switch (type) {
            case BOOLEAN -> writer.setBoolean(outputOrdinal, (Boolean) value);
            case INT8 -> writer.setByte(outputOrdinal, ((Number) value).byteValue());
            case INT16 -> writer.setShort(outputOrdinal, ((Number) value).shortValue());
            case INT32, DATE -> writer.setInt(outputOrdinal, ((Number) value).intValue());
            case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(outputOrdinal, ((Number) value).longValue());
            case FLOAT32 -> writer.setFloat(outputOrdinal, ((Number) value).floatValue());
            case FLOAT64 -> writer.setDouble(outputOrdinal, ((Number) value).doubleValue());
            case STRING -> writer.setString(outputOrdinal, (String) value);
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE, "cannot group by a column of type " + type + " yet");
        }
    }

    /**
     * The group key for a row, as values rather than a hash.
     *
     * <p>Materialised on purpose. The row is a flyweight into arena memory that is reused as soon as
     * the next row arrives, so a key holding a reference to it would silently change identity. And a
     * hash alone would collide -- rarely, silently, and by merging two groups whose totals then look
     * plausible, which is precisely the failure this whole path is being careful about.
     */
    private Key keyOf(RowView row) {
        Object[] values = new Object[keyOrdinals.size()];
        for (int i = 0; i < values.length; i++) {
            int ordinal = keyOrdinals.get(i);
            values[i] = row.isNull(ordinal) ? null : read(row, ordinal);
        }
        return new Key(values);
    }

    private Object read(RowView row, int ordinal) {
        return switch (inputSchema.field(ordinal).type().typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case STRING -> row.getString(ordinal);
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "cannot group by a column of type "
                                + inputSchema.field(ordinal).type().typeName() + " yet");
        };
    }

    /** Groups present, including any whose weights have cancelled to zero. */
    int groupCount() {
        return groups.size();
    }

    /**
     * A group key.
     *
     * <p>NULL is a distinct key here, not a value that discards the row. That is what SQL's GROUP BY
     * does -- unlike a comparison, where NULL is UNKNOWN -- so rows with no tier group together
     * under one NULL rather than vanishing.
     */
    private record Key(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(values, key.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    /** One group's accumulators, mirroring {@link GlobalAggregate}'s arithmetic exactly. */
    private static final class Group {
        private final long[] sums;
        private final long[] counts;
        private final boolean[] seen;
        private final List<Set<Object>> distincts = new ArrayList<>();
        private long rowCount;
        private long lastTimestamp;
        private long lastSequence;

        Group(int aggregates) {
            this.sums = new long[aggregates];
            this.counts = new long[aggregates];
            this.seen = new boolean[aggregates];
            for (int i = 0; i < aggregates; i++) {
                distincts.add(null);
            }
        }

        void accumulate(
                RowView row,
                long weight,
                List<AggregateOperator.AggregateCall> calls,
                TypeName[] argumentTypes,
                java.util.function.IntFunction<Object> valueAt) {
            rowCount += weight;
            lastTimestamp = row.eventTimestampNanos();
            lastSequence = row.sequence();

            for (int i = 0; i < calls.size(); i++) {
                AggregateOperator.AggregateCall call = calls.get(i);
                switch (call.kind()) {
                    case COUNT -> {
                        // The third of three operators to get this guard. COUNT(*) counts rows;
                        // COUNT(col) counts non-null values. Counting rows either way makes a
                        // result row contradict itself -- COUNT 3, SUM 300, AVG 150.
                        if (call.argumentOrdinal() < 0 || !row.isNull(call.argumentOrdinal())) {
                            counts[i] += weight;
                        }
                    }
                    case COUNT_DISTINCT -> {
                        // Bounded by the scan, exactly like the group map itself. GlobalAggregate
                        // refuses this because a stream never ends; a read does.
                        if (call.argumentOrdinal() >= 0 && !row.isNull(call.argumentOrdinal())) {
                            if (distincts.get(i) == null) {
                                distincts.set(i, new HashSet<>());
                            }
                            // Read by the column's declared type, not as text. getString over an
                            // INT64 column read the slot's bits as a (offset, length) pair and died
                            // with a raw NegativeArraySizeException: -1 -- no code, no column named,
                            // on the read path CONTINUOUS_QUERIES.md marks supported.
                            distincts.get(i).add(valueAt.apply(call.argumentOrdinal()));
                        }
                    }
                    case SUM, AVG -> {
                        if (call.argumentOrdinal() >= 0 && !row.isNull(call.argumentOrdinal())) {
                            sums[i] += AggregateSlots.read(row, call.argumentOrdinal(), argumentTypes[i]) * weight;
                            counts[i] += weight;
                        }
                    }
                    case MIN, MAX -> {
                        if (weight < 0) {
                            throw new PravahaException(
                                    RuntimeErrors.UNSUPPORTED_AGGREGATE,
                                    call.kind() + " cannot yet handle a retraction: restoring the previous "
                                            + "extreme needs an ordered multiset per group");
                        }
                        if (call.argumentOrdinal() >= 0 && !row.isNull(call.argumentOrdinal())) {
                            long value = AggregateSlots.read(row, call.argumentOrdinal(), argumentTypes[i]);
                            if (!seen[i]) {
                                sums[i] = value;
                                seen[i] = true;
                            } else if (call.kind() == AggregateOperator.AggregateCall.Kind.MIN) {
                                sums[i] = Math.min(sums[i], value);
                            } else {
                                sums[i] = Math.max(sums[i], value);
                            }
                        }
                    }
                }
            }
        }

        /**
         * ADR-039 item 6: folds a pre-combined partial in, mirroring {@link #accumulate}'s
         * arithmetic scaled by the partial's own value rather than by one row's -- see {@link
         * KeyedAggregate#processPartial}.
         *
         * @param keyColumns how many leading columns of {@code partial} are group-key values, to
         *     skip before reading the aggregate calls' own columns
         */
        void accumulatePartial(
                RowView partial, int keyColumns, long weight, List<AggregateOperator.AggregateCall> calls) {
            rowCount += weight;
            lastTimestamp = partial.eventTimestampNanos();
            lastSequence = partial.sequence();
            for (int i = 0; i < calls.size(); i++) {
                long partialValue = partial.getLong(keyColumns + i);
                switch (calls.get(i).kind()) {
                    case COUNT -> counts[i] += weight * partialValue;
                    case SUM -> sums[i] += weight * partialValue;
                    default ->
                        throw new IllegalStateException(
                                "accumulatePartial received a " + calls.get(i).kind()
                                        + " call; SourcePushdown never offers partial-aggregate pushdown for anything but "
                                        + "COUNT and SUM");
                }
            }
        }

        long valueOf(int index, AggregateOperator.AggregateCall call) {
            return switch (call.kind()) {
                case COUNT -> counts[index];
                case SUM, MIN, MAX -> sums[index];
                // Integer division, matching SQL's AVG over an integer column.
                case AVG -> counts[index] == 0 ? 0 : sums[index] / counts[index];
                case COUNT_DISTINCT ->
                    distincts.get(index) == null ? 0 : distincts.get(index).size();
            };
        }
    }
}

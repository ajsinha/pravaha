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
import com.ash.messaging.pravaha.runtime.AggregateTotals;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.window.DecimalBits;

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
        Key touched = keyOf(row);
        if (continuous) {
            dirty.add(touched);
        }
        Group group = groups.computeIfAbsent(touched, key -> {
            if (groups.size() >= maxGroups) {
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "this GROUP BY has produced " + maxGroups + " distinct groups, which is the ceiling "
                                + "for a single read. Narrow it with a WHERE clause, or group by fewer columns");
            }
            return new Group(operator.aggregates().size());
        });
        group.accumulate(row, weight, operator.aggregates(), argumentTypes, inputSchema, ordinal -> read(row, ordinal));
        noteUnsettled(group);
    }

    /**
     * Groups a {@code SUM} of which carried past 64 bits in this batch (TRANSOVF-1), so that {@link
     * #settle} looks at those and not at every group.
     */
    private final List<Group> unsettled = new ArrayList<>();

    private void noteUnsettled(Group group) {
        if (group.unsettled && !group.queued) {
            group.queued = true;
            unsettled.add(group);
        }
    }

    /**
     * Refuses a batch after which some group's total does not fit in 64 bits (TRANSOVF-1): at the
     * end of every batch and before anything reads a total, so a total that left the range inside
     * the batch and came back is accepted, and one that did not is PRV-3025 naming the aggregate.
     */
    void settle() {
        if (unsettled.isEmpty()) {
            return;
        }
        for (Group group : unsettled) {
            group.settle(operator.aggregates(), inputSchema);
        }
        unsettled.clear();
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
        Key touched = new Key(keyValues);
        if (continuous) {
            dirty.add(touched);
        }
        Group group = groups.computeIfAbsent(touched, key -> {
            if (groups.size() >= maxGroups) {
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "this GROUP BY has produced " + maxGroups + " distinct groups, which is the ceiling "
                                + "for a single read. Narrow it with a WHERE clause, or group by fewer columns");
            }
            return new Group(operator.aggregates().size());
        });
        group.accumulatePartial(partial, keyOrdinals.size(), weight, operator.aggregates());
        noteUnsettled(group);
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
            // NANGROUP-1: one group for either zero, one for every NaN.
            case FLOAT32 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.canonical(row.getFloat(ordinal));
            case FLOAT64 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.canonical(row.getDouble(ordinal));
            case STRING -> row.getString(ordinal);
            case DECIMAL -> new DecimalBits(row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal));
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "cannot group by a column of type "
                                + operator.outputSchema().field(ordinal).type().typeName() + " yet");
        };
    }

    /** Emits one row per surviving group. Called when the input ends. */
    void emit() {
        settle();
        if (continuous) {
            // On a lane the view already holds every published answer, so the end of the input is
            // one more change to it, never the whole answer again (CKPT-3's rule, for groups).
            emitIncremental();
            return;
        }
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (Map.Entry<Key, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            if (group.rowCount == 0) {
                // Every row in this group was retracted. Emitting it would report a group that is
                // no longer there.
                continue;
            }
            long mark = arena.mark(); // EMITROOM-1, as in writeGroup
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
                if (AggregateSlots.exactAverage(calls.get(i).kind(), outputTypes[key.length + i])) {
                    AggregateSlots.writeAverage(
                            writer, key.length + i, group.sums[i], group.counts[i], operator.outputSchema());
                    continue;
                }
                // SUM, AVG, MIN and MAX of a group with no non-null value are NULL (ALLNULLAGG-1).
                AggregateSlots.write(
                        writer,
                        key.length + i,
                        group.valueOf(i, calls.get(i)),
                        group.isNull(i, calls.get(i)),
                        outputTypes[key.length + i]);
            }
            writer.weight(1L)
                    .eventTimestampNanos(group.lastTimestamp)
                    .sequence(group.lastSequence)
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            arena.resetTo(mark);
        }
    }

    /**
     * Whether a lane drives this aggregate, so it publishes changed groups as the input moves rather
     * than every group once when the input ends. Over a stream a keyed unwindowed aggregate is refused
     * (PRV-2050); over a maintained view it is bounded by the view's key ceiling (ADR-056).
     */
    private boolean continuous;

    /** Groups touched since the last publication, in the order first touched. */
    private final Set<Key> dirty = new java.util.LinkedHashSet<>();

    /** What each group last published, which is what its next publication retracts. */
    private final Map<Key, AggregateSlots.Answer> published = new java.util.HashMap<>();

    void drivenContinuously() {
        this.continuous = true;
    }

    /**
     * Publishes every group whose answer changed since the last call: a retraction of what it
     * published before and an insert of what it holds now, as one batch (ADR-056).
     *
     * <p>A group whose rows have all been retracted retracts its last answer and is released, so
     * the state held is the groups present in the input, not every group that ever was.
     */
    void emitIncremental() {
        settle();
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (Key key : dirty) {
            Group group = groups.get(key);
            AggregateSlots.Answer before = published.get(key);
            AggregateSlots.Answer now = null;
            if (group != null && group.rowCount > 0) {
                long[] values = new long[calls.size()];
                boolean[] nulls = new boolean[calls.size()];
                for (int i = 0; i < values.length; i++) {
                    nulls[i] = group.isNull(i, calls.get(i));
                    values[i] = nulls[i] ? 0 : group.valueOf(i, calls.get(i));
                }
                now = new AggregateSlots.Answer(values, nulls);
            }
            if (group != null && group.rowCount <= 0) {
                groups.remove(key);
            }
            if (java.util.Objects.equals(before, now)) {
                continue;
            }
            long timestamp = group == null ? 0 : group.lastTimestamp;
            long sequence = group == null ? 0 : group.lastSequence;
            if (before != null) {
                writeGroup(key, before, -1L, timestamp, sequence);
            }
            if (now != null) {
                writeGroup(key, now, 1L, timestamp, sequence);
                published.put(key, now);
            } else {
                published.remove(key);
            }
        }
        dirty.clear();
    }

    private void writeGroup(Key key, AggregateSlots.Answer answer, long weight, long timestamp, long sequence) {
        long[] values = answer.values();
        // EMITROOM-1: given back once downstream has copied it, as WindowedAggregate does.
        long mark = arena.mark();
        long handle = arena.allocate(layout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit a grouped aggregate result");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        Object[] keyValues = key.values;
        for (int i = 0; i < keyValues.length; i++) {
            writeKey(i, keyValues[i]);
        }
        for (int i = 0; i < values.length; i++) {
            AggregateSlots.write(
                    writer, keyValues.length + i, values[i], answer.nulls()[i], outputTypes[keyValues.length + i]);
        }
        writer.weight(weight).eventTimestampNanos(timestamp).sequence(sequence).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        arena.resetTo(mark);
    }

    /**
     * Writes the groups and what each last published, so a restore resumes beside the restored view
     * rather than publishing every group next to the answer it already holds (CKPT-2's rule).
     */
    void writeTo(java.io.DataOutput out) throws java.io.IOException {
        // Between batches there is no excess to carry (TRANSOVF-1); settling makes that a fact.
        settle();
        int calls = operator.aggregates().size();
        // When a published answer holds a NULL (ALLNULLAGG-1) the call count is written bitwise
        // inverted and every published answer carries its null flags after its values. Otherwise
        // the bytes are what they were, and a checkpoint from before the flags reads as no NULLs.
        boolean withNulls = published.values().stream().anyMatch(AggregateSlots.Answer::anyNull);
        out.writeInt(withNulls ? ~calls : calls);
        out.writeInt(groups.size());
        for (Map.Entry<Key, Group> entry : groups.entrySet()) {
            writeKeyValues(out, entry.getKey());
            Group group = entry.getValue();
            out.writeLong(group.rowCount);
            out.writeLong(group.lastTimestamp);
            out.writeLong(group.lastSequence);
            for (int i = 0; i < calls; i++) {
                out.writeLong(group.sums[i]);
                out.writeLong(group.counts[i]);
                out.writeBoolean(group.seen[i]);
                GlobalAggregate.writeDistinct(out, group.distincts.get(i));
            }
        }
        out.writeInt(published.size());
        for (Map.Entry<Key, AggregateSlots.Answer> entry : published.entrySet()) {
            writeKeyValues(out, entry.getKey());
            entry.getValue().writeValues(out);
            if (withNulls) {
                entry.getValue().writeNulls(out);
            }
        }
    }

    /** Restores what {@link #writeTo} wrote, replacing whatever this aggregate held. */
    void readFrom(java.io.DataInput in) throws java.io.IOException {
        int calls = operator.aggregates().size();
        int written = in.readInt();
        boolean withNulls = written < 0;
        if (withNulls) {
            written = ~written;
        }
        if (written != calls) {
            throw new java.io.IOException("the checkpointed grouped aggregate computes " + written
                    + " values and this one computes " + calls + ": the query changed since the checkpoint was taken");
        }
        groups.clear();
        published.clear();
        dirty.clear();
        unsettled.clear();
        int groupCount = in.readInt();
        for (int g = 0; g < groupCount; g++) {
            Key key = readKeyValues(in);
            Group group = new Group(calls);
            group.rowCount = in.readLong();
            group.lastTimestamp = in.readLong();
            group.lastSequence = in.readLong();
            for (int i = 0; i < calls; i++) {
                group.sums[i] = in.readLong();
                group.counts[i] = in.readLong();
                group.seen[i] = in.readBoolean();
                group.distincts.set(i, GlobalAggregate.readDistinct(in));
            }
            groups.put(key, group);
        }
        int publishedCount = in.readInt();
        for (int p = 0; p < publishedCount; p++) {
            Key key = readKeyValues(in);
            published.put(key, AggregateSlots.Answer.read(in, calls, withNulls));
        }
        // Every group is looked at again at the next publication: one whose accumulators moved
        // after its last publication and before the cut publishes then, and the rest compare equal.
        dirty.addAll(groups.keySet());
        dirty.addAll(published.keySet());
    }

    private void writeKeyValues(java.io.DataOutput out, Key key) throws java.io.IOException {
        out.writeInt(key.values.length);
        for (Object value : key.values) {
            out.writeBoolean(value != null);
            if (value != null) {
                GlobalAggregate.writeDistinct(out, java.util.Set.of(value));
            }
        }
    }

    private Key readKeyValues(java.io.DataInput in) throws java.io.IOException {
        int length = in.readInt();
        if (length != keyOrdinals.size()) {
            throw new java.io.IOException("a checkpointed group key of " + length + " columns for a GROUP BY of "
                    + keyOrdinals.size() + ": the query changed since the checkpoint was taken");
        }
        Object[] values = new Object[length];
        for (int i = 0; i < length; i++) {
            if (in.readBoolean()) {
                java.util.Set<Object> one = GlobalAggregate.readDistinct(in);
                values[i] = one == null || one.isEmpty() ? null : one.iterator().next();
            }
        }
        return new Key(values);
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
            case DECIMAL -> {
                DecimalBits decimal = (DecimalBits) value;
                writer.setDecimal(outputOrdinal, decimal.high(), decimal.low());
            }
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
            // NANGROUP-1: one group for either zero, one for every NaN.
            case FLOAT32 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.canonical(row.getFloat(ordinal));
            case FLOAT64 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.canonical(row.getDouble(ordinal));
            case STRING -> row.getString(ordinal);
            // The whole unscaled value (DECKEYGROUP-1, as WINDECKEY-1 for windows).
            case DECIMAL -> new DecimalBits(row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal));
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "cannot group by a column of type "
                                + inputSchema.field(ordinal).type().typeName() + " yet");
        };
    }

    /**
     * Every group this aggregate holds, as text, for somebody looking at it (ADR-048).
     *
     * <p>Read-only and non-emitting. {@link #emit} produces rows and pushes them downstream, which
     * is the last thing an inspection should do -- looking at a query must not change its answer.
     * So this reads the same accumulators and writes nothing.
     */
    void describe(java.util.function.BiConsumer<String, Map<String, String>> into) {
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        StreamSchema output = operator.outputSchema();
        for (Map.Entry<Key, Group> entry : groups.entrySet()) {
            Object[] key = entry.getKey().values;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < key.length; i++) {
                if (i > 0) {
                    text.append('|');
                }
                text.append(keyText(key[i], output, i));
            }
            Group group = entry.getValue();
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (int i = 0; i < key.length; i++) {
                values.put(output.field(i).name(), keyText(key[i], output, i));
            }
            values.put("rows", Long.toString(group.rowCount));
            for (int i = 0; i < calls.size(); i++) {
                values.put(
                        calls.get(i).outputName(),
                        AggregateSlots.text(group.valueOf(i, calls.get(i)), output, key.length + i));
            }
            into.accept(text.toString(), values);
        }
    }

    /** One key column as a person reads it: a decimal at its column's scale, not its bits. */
    private static String keyText(Object value, StreamSchema output, int ordinal) {
        return value instanceof DecimalBits decimal
                ? decimal.toBigDecimal(((com.ash.messaging.pravaha.api.data.DecimalType)
                                        output.field(ordinal).type())
                                .scale())
                        .toPlainString()
                : String.valueOf(value);
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

        /** What each {@code SUM} carries past 64 bits inside a batch; see {@link AggregateTotals}. */
        private final long[] excess;

        /** Whether an excess may be nonzero, and whether {@link #unsettled} already lists this group. */
        private boolean unsettled;

        private boolean queued;
        private final boolean[] seen;
        private final List<Set<Object>> distincts = new ArrayList<>();
        private long rowCount;
        private long lastTimestamp;
        private long lastSequence;

        Group(int aggregates) {
            this.sums = new long[aggregates];
            this.counts = new long[aggregates];
            this.excess = new long[aggregates];
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
                StreamSchema inputSchema,
                java.util.function.IntFunction<Object> valueAt) {
            rowCount += weight;
            lastTimestamp = row.eventTimestampNanos();
            lastSequence = row.sequence();

            for (int i = 0; i < calls.size(); i++) {
                AggregateOperator.AggregateCall call = calls.get(i);
                try {
                    accumulateCall(row, weight, i, call, argumentTypes, inputSchema, valueAt);
                } catch (ArithmeticException overflow) {
                    throw AggregateTotals.overflow(AggregateSlots.describe(call, inputSchema), overflow);
                }
            }
        }

        /** Folds one row into call {@code i}; every sum and count is checked (SUMWRAP-1). */
        private void accumulateCall(
                RowView row,
                long weight,
                int i,
                AggregateOperator.AggregateCall call,
                TypeName[] argumentTypes,
                StreamSchema inputSchema,
                java.util.function.IntFunction<Object> valueAt) {
            switch (call.kind()) {
                case COUNT -> {
                    // The third of three operators to get this guard. COUNT(*) counts rows;
                    // COUNT(col) counts non-null values. Counting rows either way makes a
                    // result row contradict itself -- COUNT 3, SUM 300, AVG 150.
                    if (call.argumentOrdinal() < 0 || !row.isNull(call.argumentOrdinal())) {
                        counts[i] = AggregateTotals.add(counts[i], weight);
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
                        AggregateTotals.addWeighted(
                                sums,
                                excess,
                                i,
                                AggregateSlots.read(row, call.argumentOrdinal(), argumentTypes[i], inputSchema),
                                weight);
                        unsettled |= excess[i] != 0;
                        counts[i] = AggregateTotals.add(counts[i], weight);
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
                        long value = AggregateSlots.read(row, call.argumentOrdinal(), argumentTypes[i], inputSchema);
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
                try {
                    switch (calls.get(i).kind()) {
                        case COUNT -> counts[i] = AggregateTotals.addWeighted(counts[i], partialValue, weight);
                        case SUM -> {
                            AggregateTotals.addWeighted(sums, excess, i, partialValue, weight);
                            unsettled |= excess[i] != 0;
                            // A partial with a total makes the SUM present (ALLNULLAGG-1).
                            if (!partial.isNull(keyColumns + i)) {
                                counts[i] = AggregateTotals.add(counts[i], weight);
                            }
                        }
                        default ->
                            throw new IllegalStateException("accumulatePartial received a "
                                    + calls.get(i).kind()
                                    + " call; SourcePushdown never offers partial-aggregate pushdown for anything "
                                    + "but COUNT and SUM");
                    }
                } catch (ArithmeticException overflow) {
                    throw AggregateTotals.overflow(calls.get(i).kind() + " of a pushed-down partial", overflow);
                }
            }
        }

        /** Refuses a total this batch left outside the 64-bit range, naming its aggregate. */
        void settle(List<AggregateOperator.AggregateCall> calls, StreamSchema inputSchema) {
            queued = false;
            for (int i = 0; i < excess.length; i++) {
                try {
                    AggregateTotals.settled(sums[i], excess[i]);
                } catch (ArithmeticException overflow) {
                    throw AggregateTotals.overflow(AggregateSlots.describe(calls.get(i), inputSchema), overflow);
                }
            }
            unsettled = false;
        }

        /** Whether call {@code index}'s answer is NULL: SUM/AVG/MIN/MAX of no non-null value (ALLNULLAGG-1). */
        boolean isNull(int index, AggregateOperator.AggregateCall call) {
            return AggregateSlots.isNull(call.kind(), counts[index], seen[index]);
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

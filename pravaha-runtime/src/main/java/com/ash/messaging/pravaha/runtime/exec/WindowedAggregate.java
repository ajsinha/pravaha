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

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;

/**
 * The interpreted windowed aggregate.
 *
 * <p>Folds each row into its slice and fires whole windows when the watermark passes their end
 * (design section 15.3). The state lives in {@link SlicedAggregateState}, which is where the
 * arithmetic and the bounds are; this class is the part that knows about rows.
 *
 * <p><strong>Grouping is on a single composite key.</strong> The group columns are hashed into one
 * {@code long}, which is what the slice state is keyed by. That is a deliberate simplification with
 * a real consequence: two different key combinations that hash the same would be merged, silently.
 * Two independently-seeded 64-bit digests are taken, so the joint probability is the product and the
 * risk at a million groups is not worth reasoning about -- <em>provided</em> the two digests can
 * actually disagree. They could not, for strings: both read {@code String.hashCode()}, 32 bits and
 * trivially collidable, so a constructible pair like {@code "Aa"} and {@code "BB"} collided in both
 * at once. The digests now read every character.
 *
 * <p>The honest fix remains keying by the group's <em>values</em> rather than by a digest of them,
 * which is what {@link KeyedAggregate} already does one operator over ({@code record Key(Object[]
 * values)}). This used to say the fix was {@code L0StateMap} and that Wave 8 would wire it; that was
 * wrong twice over. {@code L0StateMap} takes a fixed-width key and a group key containing a
 * {@code STRING} has no fixed width, so it could never have held this state -- and it has been
 * deleted (W8-12). The 64-bit fold in this class's {@code emitted} map is now gone too: it is keyed
 * by the group's values, which were already carried in {@link Published} so a vanished key could be
 * named when it is withdrawn. That fold turned out to be the easiest collision of the three to
 * construct -- {@code keyHigh ^ (keyLow * C)} collides for {@code (H, 0)} and {@code (H ^ C, 1)},
 * one line, no search (see {@code SlicedAggregateStateTest}). A collision there suppresses one
 * group's retraction with another's, leaving a stale row in the view for ever.
 *
 * <p>What remains is {@link SlicedAggregateState}'s 128-bit {@code SliceKey}, still a digest with no
 * comparison of values behind it. Narrowing that is a larger change -- it is the per-row hot path
 * rather than the once-per-window-per-key path this one was -- and it is recorded as W8-14 rather
 * than done on the way past.
 *
 * <p>Firing is driven by {@link #advanceWatermark}, and end of input fires everything still open.
 * A bounded source -- a file, a backfill -- would otherwise leave its last windows unemitted, which
 * looks exactly like the query being wrong about its final period.
 *
 * <p><strong>Late data has three outcomes, not two</strong> (design section 15.4). A record whose
 * window has not closed is simply on time. One whose window has closed but is still within the
 * allowed lateness re-opens that window: the previous result is retracted with weight {@code -1} and
 * the corrected one emitted, which downstream consolidates to exactly the difference. One that is
 * later than that is <em>too late</em> -- its state is gone and cannot be reconstructed -- so it is
 * routed to the late output and counted, never silently dropped and never allowed to produce a
 * result that contradicts one already sent.
 */
final class WindowedAggregate implements RowProcessor, AutoCloseable {

    private final WindowedAggregateOperator operator;
    private final SlicedWindows windows;
    private final SlicedAggregateState state;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final long[] scratch;
    private final boolean[] present;

    /**
     * The distinct columns' values as themselves, or null when no aggregate needs them.
     *
     * <p>Null when nothing is distinct, so the ordinary windowed aggregate allocates and reads
     * nothing extra: this exists for COUNT(DISTINCT), which is the only thing that cares what a
     * value <em>is</em> rather than what it adds up to.
     */
    private final Object[] distinctScratch;

    private final List<Integer> valueOrdinals;

    /** Each call's argument type and each output column's type, for {@link AggregateSlots} (HLP-1). */
    private final com.ash.messaging.pravaha.api.data.TypeName[] argumentTypes;

    private final com.ash.messaging.pravaha.api.data.TypeName[] outputTypes;
    private final List<com.ash.messaging.pravaha.api.data.TypeName> groupTypes;
    /** Group keys other than the window boundaries: the actual data keys. */
    private final List<Integer> dataKeyOrdinals;

    /**
     * One published result row, kept so it can be withdrawn exactly as it was sent.
     *
     * <p>The values alone were kept, and that is enough to retract a row whose numbers changed --
     * the key is still in the new firing, so its key columns come from there. It is not enough to
     * retract a row whose key has <em>gone</em>: a group whose weights net to zero after the window
     * was published simply stops appearing, and there is nothing left to build the retraction from.
     * So the key's own columns and its window's start travel with it.
     */
    private record Published(Object[] keyValues, long windowStartNanos, long[] values) {}

    /**
     * A group, by its values, so two groups cannot become one.
     *
     * <p>This map used to be keyed by {@code WindowResult.key()} -- {@code keyHigh ^ (keyLow *
     * 0x9E37...)}, 128 bits of digest folded down to 64. A collision there does not merge sums the
     * way PF-10's did; it makes one group's retraction suppress another's, so the wrong row is
     * withdrawn and a stale one stands in the view for ever. The narrowest state key in the engine
     * guarding the operation that is hardest to notice going wrong.
     *
     * <p>{@code KeyedAggregate} one operator over already does this, and says why: "a hash alone
     * would collide -- rarely, silently, and by merging two groups". The values were already here,
     * carried in {@link Published} so a vanished key can be named when it is withdrawn, so keying by
     * them costs an array comparison on a path that runs once per window per key, not per row.
     */
    private record GroupKey(Object[] values) {

        @Override
        public boolean equals(Object other) {
            return other instanceof GroupKey key && java.util.Arrays.equals(values, key.values);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return java.util.Arrays.toString(values);
        }
    }

    /** What each window last emitted per key, so a correction can retract it exactly. */
    private final java.util.Map<Long, java.util.Map<GroupKey, Published>> emitted = new java.util.HashMap<>();
    /** Windows a late record has changed since they last fired. */
    private final java.util.Set<Long> dirty = new java.util.LinkedHashSet<>();

    private java.util.function.Consumer<RowView> lateOutput = row -> {};
    private long lateRecords;
    private long corrections;

    private long watermark = Long.MIN_VALUE;
    private long lastFiredWatermark = Long.MIN_VALUE;
    private long highestEventTime = Long.MIN_VALUE;
    private long earliestWindowStart = Long.MAX_VALUE;

    WindowedAggregate(WindowedAggregateOperator operator, RowArena arena, RowProcessor downstream) {
        this(operator, arena, downstream, null, 0);
    }

    /**
     * ADR-037 item B2: a windowed aggregate whose live-slice count exceeds {@code operator.maxSlices()}
     * keeps running, slower, once given an overflow tier -- see {@link SlicedAggregateState}'s own
     * overflow-aware constructor, which this passes straight through to. {@code COUNT DISTINCT}
     * spills too, since ADR-044.
     *
     * @param overflowAccess where state beyond the in-memory ceiling is carved from, or {@code null}
     *     for no overflow tier -- today's behaviour, unchanged
     * @param maxOverflowSlabs the ceiling on {@code overflowAccess} slabs, ignored when {@code
     *     overflowAccess} is {@code null}
     */
    WindowedAggregate(
            WindowedAggregateOperator operator,
            RowArena arena,
            RowProcessor downstream,
            MemoryAccess overflowAccess,
            int maxOverflowSlabs) {
        this.operator = operator;
        this.windows = new SlicedWindows(operator.spec());
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(operator.outputSchema());
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);

        SlicedAggregateState.Kind[] kinds =
                new SlicedAggregateState.Kind[operator.aggregates().size()];
        this.valueOrdinals = operator.aggregates().stream()
                .map(AggregateOperator.AggregateCall::argumentOrdinal)
                .toList();
        this.argumentTypes = AggregateSlots.typesOf(operator.input().outputSchema(), valueOrdinals);
        this.outputTypes = AggregateSlots.typesOf(
                operator.outputSchema(),
                java.util.stream.IntStream.range(0, operator.outputSchema().fieldCount())
                        .boxed()
                        .toList());
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = switch (operator.aggregates().get(i).kind()) {
                case COUNT -> SlicedAggregateState.Kind.COUNT;
                case COUNT_DISTINCT -> SlicedAggregateState.Kind.COUNT_DISTINCT;
                case SUM -> SlicedAggregateState.Kind.SUM;
                case AVG -> SlicedAggregateState.Kind.AVG;
                case MIN -> SlicedAggregateState.Kind.MIN;
                case MAX -> SlicedAggregateState.Kind.MAX;
            };
        }
        this.state = new SlicedAggregateState(windows, kinds, operator.maxSlices(), overflowAccess, maxOverflowSlabs);
        this.scratch = new long[kinds.length];
        this.present = new boolean[kinds.length];
        boolean anyDistinct = false;
        for (SlicedAggregateState.Kind kind : kinds) {
            anyDistinct |= kind == SlicedAggregateState.Kind.COUNT_DISTINCT;
        }
        this.distinctScratch = anyDistinct ? new Object[kinds.length] : null;
        // The window boundaries are group keys in SQL and must NOT be part of the accumulator's key
        // here. The slice dimension already separates windows; including the boundaries as well
        // gives each slice of a window its own accumulator and they never combine -- which is
        // exactly what happened, and produced two partial sums where one total belonged, both of
        // them arithmetically correct and neither of them the answer.
        this.dataKeyOrdinals = operator.groupKeys().stream()
                .filter(ordinal -> ordinal != operator.windowStartOrdinal() && ordinal != operator.windowEndOrdinal())
                .toList();
        // Resolved once, at construction: the key hash must not look a column's type up per row.
        this.groupTypes = dataKeyOrdinals.stream()
                .map(ordinal ->
                        operator.input().outputSchema().field(ordinal).type().typeName())
                .toList();
    }

    /**
     * Where records too late to correct anything are sent.
     *
     * <p>A named side output rather than a drop. "The number was wrong because 0.2 % of records
     * arrived after their window had been released" is a diagnosis; a missing record is not.
     */
    void lateOutput(java.util.function.Consumer<RowView> sink) {
        this.lateOutput = sink;
    }

    @Override
    public void process(RowView row) {
        long windowStart = row.getLong(operator.windowStartOrdinal());
        long lastWindowEnd = windows.lastWindowEndFor(windowStart);
        if (watermark != Long.MIN_VALUE && lastWindowEnd + operator.allowedLatenessNanos() <= watermark) {
            // Its state has been released and cannot be rebuilt. Accepting it would produce a result
            // that contradicts one already sent, from state that no longer exists.
            lateRecords++;
            lateOutput.accept(row);
            return;
        }
        long keyHigh = compositeKey(row, 0x9E3779B97F4A7C15L);
        // A second, independently-seeded digest of the same columns. Two 64-bit hashes of the same
        // input are not two independent 64-bit hashes -- but seeded differently and finalised
        // separately they are close enough that the joint collision probability is the product,
        // which is what takes a one-in-thirty-million risk at a million groups down to nothing worth
        // reasoning about.
        long keyLow = compositeKey(row, 0xC2B2AE3D27D4EB4FL);
        for (int i = 0; i < scratch.length; i++) {
            int ordinal = valueOrdinals.get(i);
            // The value itself, for anything counting distinct values. scratch holds getLong of
            // the slot, which over a STRING column is its (offset, length) pair rather than its
            // text -- so distinctness was computed over storage addresses.
            if (distinctScratch != null) {
                distinctScratch[i] = ordinal >= 0 && !row.isNull(ordinal)
                        ? readKey(
                                row,
                                ordinal,
                                operator.input()
                                        .outputSchema()
                                        .field(ordinal)
                                        .type()
                                        .typeName())
                        : null;
            }
            // A null flattens to 0 for the arithmetic, and `present` remembers that it was a null.
            // Without that memory COUNT(col) counts rows and AVG divides by the wrong number, and
            // neither can tell a genuine zero from an absent value.
            boolean known = ordinal < 0 || !row.isNull(ordinal);
            present[i] = known;
            scratch[i] = known && ordinal >= 0
                    ? AggregateSlots.read(
                            row, ordinal, argumentTypes[i], operator.input().outputSchema())
                    : 0;
        }
        // The key's values travel with the accumulator: the result row has to contain them, and a
        // hash can say that a group counted seven without saying which group.
        Object[] keyValues = new Object[dataKeyOrdinals.size()];
        for (int i = 0; i < keyValues.length; i++) {
            int ordinal = dataKeyOrdinals.get(i);
            keyValues[i] = row.isNull(ordinal) ? null : readKey(row, ordinal, groupTypes.get(i));
        }
        // The window start is the event time as far as slicing is concerned: the assigner has
        // already placed the row, and using it here keeps the two from disagreeing about a boundary.
        state.update(keyHigh, keyLow, keyValues, windowStart, scratch, present, distinctScratch, row.weight());
        highestEventTime = Math.max(highestEventTime, row.eventTimestampNanos());
        earliestWindowStart = Math.min(earliestWindowStart, windowStart);

        // Late but still correctable: mark every already-fired window this record belongs to, so the
        // next advance re-emits them with the correction rather than leaving the old answer standing.
        if (watermark != Long.MIN_VALUE) {
            for (long windowEnd : windows.windowEndsContaining(windowStart)) {
                if (emitted.containsKey(windowEnd)) {
                    dirty.add(windowEnd);
                }
            }
        }
    }

    /**
     * Fires every window that has completed since the last call.
     *
     * <p>Windows fire in order and each fires once, because a consumer applying two results for one
     * window in arrival order keeps whichever arrived last.
     */
    void advanceWatermark(long watermarkNanos) {
        if (watermarkNanos <= watermark) {
            return;
        }
        watermark = watermarkNanos;
        if (lastFiredWatermark == Long.MIN_VALUE && earliestWindowStart == Long.MAX_VALUE) {
            // No row has reached this aggregate yet, so no window holds anything and there is
            // nothing to fire. Firing "from the first window" would start at the epoch: a filter
            // upstream that dropped the stream's first rows (cancel_rate's WHERE event_type =
            // 'CANCEL' behind a NEW) would then walk fifty-six years of empty ten-second windows and
            // stop the query with PRV-3022, blaming a timestamp nobody sent. Found by
            // CaseStudyRunTest on the trading study.
            lastFiredWatermark = watermark;
            return;
        }
        long from = lastFiredWatermark == Long.MIN_VALUE ? firstWindowStart() : lastFiredWatermark;
        // Corrections first: a consumer applying results in arrival order should see the fix for an
        // old window before the results of newer ones.
        for (long windowEnd : List.copyOf(dirty)) {
            corrections++;
            emitWindow(windowEnd);
        }
        dirty.clear();

        for (long windowEnd : windows.windowsCompletedBetween(from, watermark)) {
            emitWindow(windowEnd);
        }
        lastFiredWatermark = watermark;
        // Release what no window can need again -- at the watermark plus the stream's declared
        // allowed lateness, which is what keeps a closed window correctable for as long as its
        // source said corrections might arrive.
        int released = state.discardSlicesEndingBefore(watermark, operator.allowedLatenessNanos());
        if (released > 0) {
            // Forget what those windows emitted too: keeping it would be state that outlives the
            // state it describes, which is the definition of a leak.
            emitted.keySet().removeIf(windowEnd -> windowEnd + operator.allowedLatenessNanos() <= watermark);
        }
    }

    /**
     * Where firing starts on the very first advance.
     *
     * <p>The <em>earliest</em> window seen, not the latest, and not the epoch. Starting from the
     * latest silently drops the opening windows of the stream: the first end-to-end query came back
     * with three groups where five were expected, and every number in those three was correct.
     * Starting from the epoch would instead walk every window since 1970 to reach the first real one.
     *
     * <p>One slide is subtracted because the scan takes window ends strictly after the point it
     * starts from, and the earliest window's own end must count.
     */
    private long firstWindowStart() {
        return earliestWindowStart == Long.MAX_VALUE
                ? 0
                : earliestWindowStart - operator.spec().slideNanos();
    }

    /** Called when the input ends: a bounded source must not leave its last windows unemitted. */
    @Override
    public void finish() {
        if (highestEventTime != Long.MIN_VALUE) {
            // Past the end of the last window that can contain the highest event time seen.
            advanceWatermark(highestEventTime
                    + operator.spec().sizeNanos()
                    + operator.spec().slideNanos());
        }
    }

    private void emitWindow(long windowEnd) {
        java.util.Map<GroupKey, Published> previous = emitted.get(windowEnd);
        java.util.Map<GroupKey, Published> current = new java.util.HashMap<>();

        // Streamed: each group is emitted as the state combines it, so firing holds one result at a
        // time rather than the window's whole list (SPILL-3). `current` still holds what the window
        // published, for as long as a correction may need to retract it.
        state.fire(windowEnd, result -> {
            GroupKey key = new GroupKey(result.keyValues());
            if (previous != null) {
                Published before = previous.get(key);
                if (before != null) {
                    if (java.util.Arrays.equals(before.values(), result.values())) {
                        // Unchanged by the correction. Emitting a retraction and an identical
                        // insertion would be two rows that consolidate to nothing, which is
                        // arithmetically harmless and pure noise on the wire.
                        current.put(key, before);
                        return;
                    }
                    emitRow(result.keyValues(), result.windowStartNanos(), windowEnd, before.values(), -1L);
                }
            }
            current.put(key, new Published(result.keyValues(), result.windowStartNanos(), result.values()));
            emitRow(result.keyValues(), result.windowStartNanos(), windowEnd, result.values(), 1L);
        });

        // A key that was published and is no longer here has to be withdrawn. It used to be dropped
        // silently: a group whose weights netted to zero after its window was published left its
        // stale row standing in the view for ever, and nothing counted it. The correction path
        // retracted a key whose *values* had changed and forgot one that had disappeared, which is
        // the harder half and the one a Z-set exists to get right.
        if (previous != null) {
            for (java.util.Map.Entry<GroupKey, Published> gone : previous.entrySet()) {
                if (!current.containsKey(gone.getKey())) {
                    Published row = gone.getValue();
                    emitRow(row.keyValues(), row.windowStartNanos(), windowEnd, row.values(), -1L);
                    withdrawals++;
                }
            }
        }
        emitted.put(windowEnd, current);
    }

    /**
     * Every window this aggregate is still retaining, with what it published for each key (ADR-048).
     *
     * <p><strong>Fired windows only, and that is a real limit rather than an oversight.</strong> A
     * window still filling lives in the sliced accumulators, and the only way to read a slice's
     * answer is {@code fire()}, which emits it -- so listing an open window would publish it early
     * and change the answer the operator was being asked about. A debugger that alters the query it
     * is debugging is worse than one that shows less, so this shows what has fired and is still
     * within allowed lateness, which is exactly the state a correction would retract.
     *
     * <p>Sorted by window end and then by key, because {@code emitted} is a hash map and a page of
     * an unordered collection is a different page every time it is asked for.
     */
    void describe(java.util.function.BiConsumer<String, java.util.Map<String, String>> into) {
        java.util.List<com.ash.messaging.pravaha.runtime.plan.AggregateOperator.AggregateCall> calls =
                operator.aggregates();
        java.util.List<Long> windowEnds = new java.util.ArrayList<>(emitted.keySet());
        java.util.Collections.sort(windowEnds);
        for (long windowEnd : windowEnds) {
            java.util.List<Published> rows =
                    new java.util.ArrayList<>(emitted.get(windowEnd).values());
            rows.sort(java.util.Comparator.comparing(row -> keyText(row.keyValues())));
            for (Published row : rows) {
                java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
                values.put("window_start", Long.toString(row.windowStartNanos()));
                values.put("window_end", Long.toString(windowEnd));
                Object[] keyValues = row.keyValues();
                for (int i = 0; i < keyValues.length; i++) {
                    values.put("key" + i, String.valueOf(keyValues[i]));
                }
                long[] published = row.values();
                for (int i = 0; i < published.length && i < calls.size(); i++) {
                    values.put(
                            calls.get(i).outputName(),
                            AggregateSlots.text(
                                    published[i],
                                    operator.outputSchema(),
                                    operator.groupKeys().size() + i));
                }
                into.accept(windowEnd + "|" + keyText(keyValues), values);
            }
        }
    }

    private static String keyText(Object[] keyValues) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < keyValues.length; i++) {
            if (i > 0) {
                text.append('|');
            }
            text.append(keyValues[i]);
        }
        return text.toString();
    }

    /** Windows this aggregate is retaining a published answer for. */
    int retainedWindows() {
        return emitted.size();
    }

    /** Keys withdrawn because a correction removed them from a window already published. */
    private long withdrawals;

    long withdrawals() {
        return withdrawals;
    }

    /**
     * Writes one result row with the given values and Z-set weight.
     *
     * <p>Takes the key's columns and its window rather than a {@code WindowResult}, because a
     * withdrawal has no result to take them from: the whole point is that the group is no longer
     * being produced.
     */
    private void emitRow(Object[] keyValues, long windowStartNanos, long windowEndNanos, long[] values, long weight) {
        {
            long handle = arena.allocate(layout.rowSize(256));
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(
                        RuntimeErrors.ARENA_EXHAUSTED,
                        "no room to emit a window result for key " + java.util.Arrays.toString(keyValues));
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            // Group keys in the order the plan put them. The window boundaries come from the window
            // that fired, not from the row: the assigner wrote *slice* boundaries, which are what
            // the slicing needs and are narrower than the window for anything hopping.
            int column = 0;
            int dataKey = 0;
            for (int ordinal : operator.groupKeys()) {
                if (ordinal == operator.windowStartOrdinal()) {
                    writer.setLong(column++, windowStartNanos);
                } else if (ordinal == operator.windowEndOrdinal()) {
                    writer.setLong(column++, windowEndNanos);
                } else {
                    writeKey(column++, keyValues[dataKey++]);
                }
            }
            for (int i = 0; i < values.length; i++) {
                AggregateSlots.write(writer, column, values[i], outputTypes[column]);
                column++;
            }
            writer.weight(weight)
                    .eventTimestampNanos(windowEndNanos)
                    .sequence(windowEndNanos)
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    /**
     * Writes this operator's state: the accumulators, the watermark cursors, and what each window
     * last emitted.
     *
     * <p>The last of those is easy to leave out and wrong to. Without it a restored operator does
     * not know what it has already told anybody, so the first correction after a restore emits a new
     * answer with no retraction of the old one -- and a retract-mode consumer ends up holding both.
     * The state that describes what was emitted is part of the state.
     */
    void writeTo(java.io.DataOutput out) throws java.io.IOException {
        out.writeLong(watermark);
        out.writeLong(lastFiredWatermark);
        out.writeLong(highestEventTime);
        out.writeLong(earliestWindowStart);
        out.writeLong(lateRecords);
        out.writeLong(corrections);

        out.writeInt(emitted.size());
        for (java.util.Map.Entry<Long, java.util.Map<GroupKey, Published>> window : emitted.entrySet()) {
            out.writeLong(window.getKey());
            out.writeInt(window.getValue().size());
            for (java.util.Map.Entry<GroupKey, Published> perKey :
                    window.getValue().entrySet()) {
                // No separate key field any more. It used to write the 64-bit fold here and the key
                // columns immediately below, which is the same group named twice -- once exactly and
                // once approximately. The approximate one is gone and the key is rebuilt from the
                // columns on read, so the restored map cannot disagree with the one that was saved.
                Published row = perKey.getValue();
                // The key's own columns travel with the values. Without them a restored operator
                // can retract a row whose numbers changed and cannot retract one whose key has
                // gone, because it has nothing left to name the group with -- the same gap this
                // class had in memory, preserved across a restart.
                out.writeLong(row.windowStartNanos());
                writeTaggedValues(out, row.keyValues());
                out.writeInt(row.values().length);
                for (long value : row.values()) {
                    out.writeLong(value);
                }
            }
        }
        state.writeTo(out);
    }

    /** Reads state back, replacing whatever is held. */
    void readFrom(java.io.DataInput in) throws java.io.IOException {
        watermark = in.readLong();
        lastFiredWatermark = in.readLong();
        highestEventTime = in.readLong();
        earliestWindowStart = in.readLong();
        lateRecords = in.readLong();
        corrections = in.readLong();

        emitted.clear();
        dirty.clear();
        int windows = in.readInt();
        for (int w = 0; w < windows; w++) {
            long windowEnd = in.readLong();
            int keys = in.readInt();
            java.util.Map<GroupKey, Published> perWindow = new java.util.HashMap<>();
            for (int k = 0; k < keys; k++) {
                long windowStart = in.readLong();
                Object[] keyValues = readTaggedValues(in);
                long[] values = new long[in.readInt()];
                for (int v = 0; v < values.length; v++) {
                    values[v] = in.readLong();
                }
                perWindow.put(new GroupKey(keyValues), new Published(keyValues, windowStart, values));
            }
            emitted.put(windowEnd, perWindow);
        }
        state.readFrom(in);
    }

    /** Live accumulators this aggregate holds, and the ceiling it is refused at. */
    com.ash.messaging.pravaha.runtime.window.SlicedAggregateState state() {
        return state;
    }

    /** Whether this aggregate has spilled to its overflow tier (ADR-037 item B2). */
    boolean hasSpilled() {
        return state.hasSpilled();
    }

    @Override
    public void close() {
        state.close();
    }

    /** Records too late to correct anything. The number that says whether the lateness is set right. */
    long lateRecords() {
        return lateRecords;
    }

    /** Windows re-emitted because a late record changed them. */
    long corrections() {
        return corrections;
    }

    /**
     * Hashes the grouping columns into one long.
     *
     * <p>Window boundaries are part of the key, so two windows for the same user are different
     * groups without the state needing to know what a window is.
     */
    private long compositeKey(RowView row, long seed) {
        long hash = seed;
        for (int i = 0; i < dataKeyOrdinals.size(); i++) {
            int ordinal = dataKeyOrdinals.get(i);
            long value;
            if (row.isNull(ordinal)) {
                // A distinct constant rather than zero: NULL and 0 are different groups, and SQL is
                // emphatic that they are.
                value = 0xD1B54A32D192ED03L;
            } else if (groupTypes.get(i) == com.ash.messaging.pravaha.api.data.TypeName.STRING) {
                // Every character, seeded by this digest. Not String.hashCode(): it is 32 bits and
                // trivially collidable -- "Aa" and "BB" are both 2112 -- and feeding it to both
                // digests made them collide together, so the second one bought nothing. Two users
                // named that way were summed into one group and reported under one of the names,
                // with no error anywhere.
                String text = row.getString(ordinal);
                hash = mix(hash ^ 0x2F5E3A1D7C9B4E61L); // separates "" from an absent column
                for (int c = 0; c < text.length(); c++) {
                    hash = mix(hash ^ text.charAt(c));
                }
                continue;
            } else {
                value = switch (groupTypes.get(i)) {
                    case BOOLEAN -> row.getBoolean(ordinal) ? 1 : 0;
                    case INT8 -> row.getByte(ordinal);
                    case INT16 -> row.getShort(ordinal);
                    case INT32, DATE -> row.getInt(ordinal);
                    default -> row.getLong(ordinal);
                };
            }
            hash = mix(hash ^ value);
        }
        return hash;
    }

    /** Reads one group column as an object, so it can be written back out verbatim. */
    private static Object readKey(RowView row, int ordinal, com.ash.messaging.pravaha.api.data.TypeName type) {
        return switch (type) {
            case STRING -> row.getString(ordinal);
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    /** Writes one group column back into the result row, in the output schema's type. */
    /** A group's key columns, each tagged with its shape, for the checkpoint. */
    private static void writeTaggedValues(java.io.DataOutput out, Object[] values) throws java.io.IOException {
        out.writeInt(values == null ? -1 : values.length);
        if (values == null) {
            return;
        }
        for (Object value : values) {
            if (value == null) {
                out.writeByte(0);
            } else if (value instanceof String text) {
                out.writeByte(1);
                out.writeUTF(text);
            } else if (value instanceof Double || value instanceof Float) {
                out.writeByte(2);
                out.writeDouble(((Number) value).doubleValue());
            } else if (value instanceof Boolean flag) {
                out.writeByte(3);
                out.writeBoolean(flag);
            } else {
                out.writeByte(4);
                out.writeLong(((Number) value).longValue());
            }
        }
    }

    private static Object[] readTaggedValues(java.io.DataInput in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        Object[] values = new Object[length];
        for (int i = 0; i < length; i++) {
            byte tag = in.readByte();
            values[i] = switch (tag) {
                case 0 -> null;
                case 1 -> in.readUTF();
                case 2 -> in.readDouble();
                case 3 -> in.readBoolean();
                case 4 -> in.readLong();
                default -> throw new java.io.IOException("unknown key tag " + tag + " in a window checkpoint");
            };
        }
        return values;
    }

    private void writeKey(int column, Object value) {
        if (value == null) {
            writer.setNull(column);
            return;
        }
        switch (operator.outputSchema().field(column).type().typeName()) {
            case STRING -> writer.setString(column, (String) value);
            case BOOLEAN -> writer.setBoolean(column, (Boolean) value);
            case INT8 -> writer.setByte(column, ((Number) value).byteValue());
            case INT16 -> writer.setShort(column, ((Number) value).shortValue());
            case INT32, DATE -> writer.setInt(column, ((Number) value).intValue());
            case FLOAT32 -> writer.setFloat(column, ((Number) value).floatValue());
            case FLOAT64 -> writer.setDouble(column, ((Number) value).doubleValue());
            default -> writer.setLong(column, ((Number) value).longValue());
        }
    }

    private static long mix(long z) {
        long h = z;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}

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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A query's answer, indexed and readable (design section 17).
 *
 * <p>The insight is that there is nothing to build. An incrementally-maintained aggregate already
 * <em>is</em> a materialised table keyed by its {@code GROUP BY} key, sitting in lane-local state.
 * Serving a point lookup against it needs no serving database, no sink write and no second network
 * hop -- it needs a hash probe. What this class adds is not storage but the two things a read
 * actually needs and does not otherwise have: a committed view of the data, and an honest statement
 * of how stale the answer is.
 *
 * <p><strong>Committed and uncommitted are kept apart, not versioned.</strong> Updates land in a
 * pending overlay and move into the visible map when the frontier commits, so a {@code CONSISTENT}
 * read is the visible map and a {@code LATEST} read is the visible map with the overlay on top. Two
 * maps rather than a version chain per key: the chain is what a general-purpose MVCC store needs,
 * and it costs an allocation and an indirection on the read path to answer a question this engine
 * can answer by construction -- a stream commits in frontier order and nothing else ever asks for a
 * point between two commits.
 *
 * <p>Weights are the update language, as everywhere else. A row with weight {@code +1} sets the
 * value for its key; {@code -1} removes it. An update is both, and needs no update path.
 *
 * <p>Bounded, because a view keyed on something unbounded is the same failure as an unbounded
 * {@code GROUP BY} arriving by a different route. The ceiling is enforced on commit and names the
 * count.
 */
public final class ServedView {

    private final String name;
    private final StreamSchema schema;
    private final int[] keyOrdinals;
    private final int maxKeys;

    /** Committed rows: what a CONSISTENT read sees. */
    private final Map<Key, Object[]> visible = new HashMap<>();

    /** Rows applied since the last commit, in arrival order, not yet visible to a consistent read. */
    private final Map<Key, Object[]> pending = new LinkedHashMap<>();

    private volatile long committedFrontier = Long.MIN_VALUE;
    private volatile long appliedFrontier = Long.MIN_VALUE;
    private long updates;
    private long removals;
    private long commits;

    public ServedView(String name, StreamSchema schema, List<Integer> keyOrdinals, int maxKeys) {
        if (keyOrdinals.isEmpty()) {
            throw new IllegalArgumentException(
                    "a served view needs a key: without one there is nothing to look a row up by, and the view "
                            + "is a stream with extra steps");
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("a view needs room for at least one key, got " + maxKeys);
        }
        this.name = name;
        this.schema = schema;
        this.keyOrdinals = keyOrdinals.stream().mapToInt(Integer::intValue).toArray();
        this.maxKeys = maxKeys;
    }

    /**
     * Applies one change.
     *
     * <p>Into the overlay, not into the visible map. A consistent read must not see half a batch,
     * and the boundary between batches is the frontier -- so nothing becomes visible until one
     * commits.
     *
     * @param frontier the input position this change reflects
     */
    public void apply(RowView row, long frontier) {
        Key key = keyOf(row);
        if (row.weight() < 0) {
            // A tombstone, kept in the overlay so a consistent read does not see the removal early.
            pending.put(key, null);
            removals++;
        } else {
            pending.put(key, valuesOf(row));
            updates++;
        }
        appliedFrontier = Math.max(appliedFrontier, frontier);
    }

    /**
     * Applies a change already decoded into values.
     *
     * <p>For a caller that has the row as objects rather than as bytes -- a sink staging a writer,
     * a test. Same semantics as {@link #apply}: the overlay, not the visible map.
     */
    public void applyValues(Object[] values, long weight, long frontier) {
        Object[] keyValues = new Object[keyOrdinals.length];
        for (int i = 0; i < keyOrdinals.length; i++) {
            keyValues[i] = values[keyOrdinals[i]];
        }
        Key key = new Key(keyValues);
        if (weight < 0) {
            pending.put(key, null);
            removals++;
        } else {
            pending.put(key, values.clone());
            updates++;
        }
        appliedFrontier = Math.max(appliedFrontier, frontier);
    }

    /**
     * Publishes everything applied so far, as of {@code frontier}.
     *
     * <p>Called when the engine's frontier commits. Until then, a consistent read is answered from
     * the previous commit -- which is the whole point: two views committed at the same frontier
     * agree on the same prefix of the input, and a number compared across them means something.
     */
    public void commit(long frontier) {
        if (frontier < committedFrontier) {
            throw new IllegalArgumentException("frontier went backwards: " + frontier + " after " + committedFrontier);
        }
        pending.forEach((key, values) -> {
            if (values == null) {
                visible.remove(key);
            } else {
                visible.put(key, values);
            }
        });
        pending.clear();
        if (visible.size() > maxKeys) {
            throw new PravahaException(
                    ServingErrors.VIEW_TOO_LARGE,
                    "view '" + name + "' holds " + visible.size() + " keys, past its ceiling of " + maxKeys
                            + ". A view keyed on something unbounded grows until the node dies; bound the key, "
                            + "add a retention window, or raise the ceiling deliberately.");
        }
        committedFrontier = frontier;
        commits++;
    }

    /** Reads one key, the default way. */
    public ViewResult get(Object... key) {
        return get(Consistency.defaultMode(), java.time.Duration.ZERO, key);
    }

    /**
     * Reads one key at a declared consistency.
     *
     * @param timeout how long an {@link Consistency.AtLeast} read may wait. Ignored by the others
     */
    public ViewResult get(Consistency consistency, java.time.Duration timeout, Object... key) {
        Key lookup = new Key(key.clone());
        return switch (consistency) {
            case Consistency.Latest ignored -> readLatest(lookup);
            case Consistency.Consistent ignored -> readCommitted(lookup);
            case Consistency.AtLeast atLeast -> {
                awaitFrontier(atLeast.frontier(), timeout);
                yield readCommitted(lookup);
            }
            case Consistency.AsOf asOf ->
                throw new PravahaException(
                        ServingErrors.NO_HISTORY,
                        "view '" + name + "' cannot answer as of frontier " + asOf.frontier() + ": a view holds "
                                + "the present, and the past lives in checkpoints. Read the checkpoint at that "
                                + "frontier instead -- answering with the current value would be the worst "
                                + "possible response to an audit question.");
        };
    }

    private ViewResult readLatest(Key key) {
        // The overlay first: it is newer by definition, and a tombstone in it means the key is gone
        // whatever the committed map still says.
        if (pending.containsKey(key)) {
            Object[] values = pending.get(key);
            return new ViewResult(Optional.ofNullable(values), appliedFrontier, 0, false);
        }
        Object[] values = visible.get(key);
        return new ViewResult(Optional.ofNullable(values), appliedFrontier, 0, pending.isEmpty());
    }

    private ViewResult readCommitted(Key key) {
        long staleness = Math.max(0, appliedFrontier - committedFrontier);
        Object[] values = visible.get(key);
        return new ViewResult(Optional.ofNullable(values), committedFrontier, staleness, true);
    }

    /**
     * Waits for the view to commit through {@code frontier}.
     *
     * <p>Bounded, and it says so when it gives up. An unbounded wait here hangs the caller whenever
     * a source goes quiet -- which is normal, not exceptional -- and a hung read is indistinguishable
     * from a hung engine.
     */
    private void awaitFrontier(long frontier, java.time.Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (committedFrontier < frontier) {
            if (System.nanoTime() >= deadline) {
                throw new PravahaException(
                        ServingErrors.READ_TIMED_OUT,
                        "view '" + name + "' is committed through " + committedFrontier + " and the read asked "
                                + "for " + frontier + ", which it did not reach within " + timeout
                                + ". The source may be idle, or behind.");
            }
            LockSupport.parkNanos(100_000L);
        }
    }

    /** Every key currently committed. For a full scan at a pinned frontier. */
    public List<Object[]> scan() {
        return new ArrayList<>(visible.values());
    }

    public String name() {
        return name;
    }

    public long committedFrontier() {
        return committedFrontier;
    }

    /** How far the view has been updated, committed or not. */
    public long appliedFrontier() {
        return appliedFrontier;
    }

    /** Keys committed and visible. */
    public int size() {
        return visible.size();
    }

    /** Changes applied but not yet visible to a consistent read. */
    public int pendingChanges() {
        return pending.size();
    }

    public long updates() {
        return updates;
    }

    public long removals() {
        return removals;
    }

    public long commits() {
        return commits;
    }

    private Key keyOf(RowView row) {
        Object[] values = new Object[keyOrdinals.length];
        for (int i = 0; i < keyOrdinals.length; i++) {
            values[i] = value(row, keyOrdinals[i]);
        }
        return new Key(values);
    }

    private Object[] valuesOf(RowView row) {
        Object[] values = new Object[schema.fields().size()];
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            values[ordinal] = value(row, ordinal);
        }
        return values;
    }

    private Object value(RowView row, int ordinal) {
        if (row.isNull(ordinal)) {
            return null;
        }
        return switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case STRING -> row.getString(ordinal);
            default -> row.getString(ordinal);
        };
    }

    /** A key by value, so it can be a map key. */
    private record Key(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return Arrays.toString(values);
        }
    }

    @Override
    public String toString() {
        return "ServedView[" + name + ", " + visible.size() + " keys committed through " + committedFrontier + ", "
                + pending.size() + " pending]";
    }
}

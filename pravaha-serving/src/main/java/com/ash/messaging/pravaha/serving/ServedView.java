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
    private final StreamSchema presented;
    private final int[] keyOrdinals;
    private final int maxKeys;

    /** Committed rows: what a CONSISTENT read sees. */
    // Access-ordered so eviction can take the least recently updated key without scanning, and
    // insertion-ordered enough that "oldest first" means what a reader expects.
    /**
     * The committed state, and the overlay below it.
     *
     * <p><strong>Guarded by {@code this}.</strong> Three threads reach these maps and none of them
     * knew about the others: the lane applies rows, the ingest feed commits on its own timer, and
     * Flight readers scan. Before rows could arrive at a server nothing committed, so the collision
     * was unreachable and the maps were plain ones; the moment ingestion worked, {@code
     * ServedView.commit} iterated this map on the feed thread while the lane thread wrote to it and
     * the feed died of a {@link java.util.ConcurrentModificationException} -- silently, after
     * 181,248 of 200,000 rows, with the query still reporting RUNNING.
     *
     * <p>The lock covers the map operations and deliberately not {@code awaitFrontier}, which spins
     * until a commit lands. Holding the monitor there would wait for a commit that needs the
     * monitor: a deadlock in place of a race. The frontiers stay {@code volatile} for exactly that
     * reason.
     *
     * <p>A monitor rather than concurrent maps because {@code commit} must move the whole overlay
     * across atomically -- a reader must not see half a batch -- and because this is the serving
     * path, which already boxes each row into an {@code Object[]}. It is not the arena path, and the
     * rule about no locks on the per-row hot path is about that one.
     */
    private final LinkedHashMap<Key, Object[]> visible = new LinkedHashMap<>();

    /** The frontier each visible key was last written at, for age-based retention. */
    private final Map<Key, Long> writtenAt = new HashMap<>();

    private final Retention retention;

    private long evicted;

    /** Rows applied since the last commit, in arrival order, not yet visible to a consistent read. */
    private final Map<Key, Object[]> pending = new LinkedHashMap<>();

    /**
     * The event time each pending row carried.
     *
     * <p>Staged per row rather than taken from the commit, because a commit covers a batch and the
     * rows in it are not all the same age. Recording the commit frontier instead made every row in a
     * batch look equally fresh, so a fifty-thousand-row batch evicted nothing -- a bug that hid
     * completely from any test that committed after every row.
     */
    private final Map<Key, Long> pendingTime = new LinkedHashMap<>();

    /**
     * Net weight per key, committed and pending.
     *
     * <p>The engine is Z-sets: a row carries a weight, an update is a retraction plus an insert, and
     * a key is present exactly while its weights sum to something positive. This class did not sum
     * them. It branched on the sign of whichever weight arrived last, so a weight of 0 -- the
     * consolidated row that means "these cancelled" -- was stored as an insert, and a single −1
     * removed a key that three inserts had put there.
     *
     * <p>Guarded by {@code this}, with the maps above.
     */
    private final Map<Key, Long> weights = new LinkedHashMap<>();

    private final Map<Key, Long> pendingWeight = new LinkedHashMap<>();

    private volatile long committedFrontier = Long.MIN_VALUE;
    private volatile long appliedFrontier = Long.MIN_VALUE;
    private long updates;
    private long removals;
    private long commits;

    /** The output columns this view is keyed by, which is what a subscriber conflates on. */
    public List<Integer> keyOrdinals() {
        return java.util.Arrays.stream(keyOrdinals).boxed().toList();
    }

    /**
     * The base streams this view's query actually reads.
     *
     * <p>SX-11. Authorization was keyed on the name a view was <em>registered under</em>, and a
     * registrant chooses that name. A view called {@code secret_pay} reading the {@code payroll}
     * stream was authorized as {@code secret_pay}, so a principal denied everything named
     * "payroll" read payroll rows -- measured at 6 of 8 such views visible and 2 readable. The name
     * is a label; this is the provenance, and it is what a read must actually be judged against.
     *
     * <p>Empty means <strong>this view is its own source</strong> -- nothing derived it, so there is
     * nothing behind it to check. That is true of a view constructed directly, which is what tests
     * and the embedded API do. It is not a way to opt out: {@code QueryRegistry} sets provenance on
     * every view it builds, and {@code ViewProvenanceTest} asserts it, because a registered query is
     * exactly the case where the name and the data can disagree.
     */
    private volatile java.util.Set<String> derivedFrom = java.util.Set.of();

    /** Records what this view reads. Called once, by whoever planned the query. */
    public ServedView derivedFrom(java.util.Collection<String> streams) {
        this.derivedFrom = java.util.Set.copyOf(streams);
        return this;
    }

    /** The base streams behind this view; empty when the view is its own source. */
    public java.util.Set<String> derivedFrom() {
        return derivedFrom;
    }

    public ServedView(String name, StreamSchema schema, List<Integer> keyOrdinals, int maxKeys) {
        this(name, schema, keyOrdinals, maxKeys, Retention.forever());
    }

    /**
     * A view that forgets rows it no longer needs.
     *
     * <p>The ceiling and the retention are different things and both are wanted. Retention says what
     * the view is <em>meant</em> to hold -- a session, a day -- and rows outside it are evicted
     * quietly because that is the policy working. The ceiling is a backstop for a query whose key
     * space was misjudged, and reaching it is a failure rather than a policy.
     *
     * <p>With a retention set, the ceiling should almost never be reached: eviction runs first, so a
     * view only exceeds its ceiling when its retention window genuinely holds more rows than the
     * ceiling allows, which means one of the two numbers is wrong and saying so is useful.
     */
    public ServedView(String name, StreamSchema schema, List<Integer> keyOrdinals, int maxKeys, Retention retention) {
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
        StreamSchema.Builder presenting = StreamSchema.builder(name);
        schema.fields().forEach(field -> presenting.field(field.name(), field.type()));
        this.presented = presenting.build();
        this.keyOrdinals = keyOrdinals.stream().mapToInt(Integer::intValue).toArray();
        this.maxKeys = maxKeys;
        this.retention = retention == null ? Retention.forever() : retention;
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
    public synchronized void apply(RowView row, long frontier) {
        Key key = keyOf(row);
        applyWeighted(key, valuesOf(row), row.weight(), frontier);
        appliedFrontier = Math.max(appliedFrontier, frontier);
    }

    /**
     * Adds one weighted change to the overlay.
     *
     * <p>The key is present exactly while its weights sum positive. Everything else follows: an
     * update arrives as −1 then +1 and nets to the new values; a retraction of a key inserted twice
     * leaves it present with weight 1; and a weight of 0 changes nothing, because it is the row that
     * says two changes cancelled.
     */
    private void applyWeighted(Key key, Object[] values, long weight, long frontier) {
        if (weight == 0) {
            return;
        }
        long net = weights.getOrDefault(key, 0L) + pendingWeight.merge(key, weight, Long::sum);
        if (net <= 0) {
            // A tombstone, kept in the overlay so a consistent read does not see the removal early.
            pending.put(key, null);
            removals++;
        } else {
            pending.put(key, values);
            pendingTime.put(key, frontier);
            updates++;
        }
    }

    /**
     * Applies a change already decoded into values.
     *
     * <p>For a caller that has the row as objects rather than as bytes -- a sink staging a writer,
     * a test. Same semantics as {@link #apply}: the overlay, not the visible map.
     */
    public synchronized void applyValues(Object[] values, long weight, long frontier) {
        Object[] keyValues = new Object[keyOrdinals.length];
        for (int i = 0; i < keyOrdinals.length; i++) {
            keyValues[i] = values[keyOrdinals[i]];
        }
        Key key = new Key(keyValues);
        applyWeighted(key, values, weight, frontier);
        appliedFrontier = Math.max(appliedFrontier, frontier);
    }

    /**
     * Publishes everything applied so far, as of {@code frontier}.
     *
     * <p>Called when the engine's frontier commits. Until then, a consistent read is answered from
     * the previous commit -- which is the whole point: two views committed at the same frontier
     * agree on the same prefix of the input, and a number compared across them means something.
     */
    public synchronized void commit(long frontier) {
        if (frontier < committedFrontier) {
            throw new IllegalArgumentException("frontier went backwards: " + frontier + " after " + committedFrontier);
        }
        pending.forEach((key, values) -> {
            if (values == null) {
                visible.remove(key);
                writtenAt.remove(key);
                weights.remove(key);
            } else {
                // Removed first so the re-insert puts this key at the back: "oldest" has to mean
                // least recently written, or a hot key would be evicted while stale ones survived.
                // Removed first so the re-insert puts this key at the back: "oldest" has to mean
                // least recently written, or a hot key would age out while stale ones survived.
                visible.remove(key);
                visible.put(key, values);
                // The row's own event time, not the commit's. A commit covers a batch and the rows
                // in it are not all the same age.
                writtenAt.put(key, pendingTime.getOrDefault(key, frontier));
            }
        });
        pendingWeight.forEach((key, delta) -> {
            long net = weights.merge(key, delta, Long::sum);
            if (net <= 0) {
                weights.remove(key);
            }
        });
        pending.clear();
        pendingTime.clear();
        pendingWeight.clear();
        committedFrontier = frontier;
        evict();
        if (visible.size() > maxKeys) {
            throw new PravahaException(
                    ServingErrors.VIEW_TOO_LARGE,
                    "view '" + name + "' holds " + visible.size() + " keys, past its ceiling of " + maxKeys
                            + ", with retention " + retention + " already applied. "
                            + (retention.isForever()
                                    ? "This view keeps everything, so a key space that keeps growing grows it "
                                            + "until the node dies. Give it a Retention, bound the key, or raise "
                                            + "the ceiling deliberately."
                                    : "Retention says what this view means and the ceiling says what the node "
                                            + "can afford, and right now the meaning does not fit: " + retention
                                            + " of this data is more than " + maxKeys + " rows. Shorten the "
                                            + "window, or provision for the volume."));
        }
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

    private synchronized ViewResult readLatest(Key key) {
        // The overlay first: it is newer by definition, and a tombstone in it means the key is gone
        // whatever the committed map still says.
        if (pending.containsKey(key)) {
            Object[] values = pending.get(key);
            return new ViewResult(Optional.ofNullable(values), appliedFrontier, 0, false);
        }
        Object[] values = visible.get(key);
        return new ViewResult(Optional.ofNullable(values), appliedFrontier, 0, pending.isEmpty());
    }

    private synchronized ViewResult readCommitted(Key key) {
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
    public synchronized List<Object[]> scan() {
        return new ArrayList<>(visible.values());
    }

    /**
     * This view's committed contents, for a checkpoint.
     *
     * <p>The view was not part of a checkpoint at all, and for a filter-or-projection query the
     * view <em>is</em> the whole answer: there are no operator accumulators to capture. So a restart
     * restored the source's offsets, read nothing more, and served an empty view -- every row the
     * query had ever produced, gone, with the query reporting RUNNING over the emptiness.
     *
     * <p>Committed rows only. What is pending has not been published to any reader, so writing it
     * would restore an answer nobody was ever given.
     */
    public synchronized byte[] snapshot() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeLong(committedFrontier);
            out.writeInt(visible.size());
            for (Map.Entry<Key, Object[]> entry : visible.entrySet()) {
                Object[] values = entry.getValue();
                out.writeInt(values.length);
                for (Object value : values) {
                    writeValue(out, value);
                }
                out.writeLong(weights.getOrDefault(entry.getKey(), 1L));
                out.writeLong(writtenAt.getOrDefault(entry.getKey(), committedFrontier));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not snapshot view '" + name + "'", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Replaces this view's contents with a snapshot's.
     *
     * <p>Replaces rather than merges: a restore happens into a view that has just been built and is
     * empty, and merging would quietly double a row if that ever stopped being true.
     */
    public synchronized void restore(byte[] snapshot) {
        if (snapshot == null || snapshot.length == 0) {
            return;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(snapshot))) {
            visible.clear();
            weights.clear();
            writtenAt.clear();
            pending.clear();
            pendingWeight.clear();
            pendingTime.clear();
            long frontier = in.readLong();
            int rows = in.readInt();
            for (int i = 0; i < rows; i++) {
                Object[] values = new Object[in.readInt()];
                for (int v = 0; v < values.length; v++) {
                    values[v] = readValue(in);
                }
                long weight = in.readLong();
                long at = in.readLong();
                Key key = keyOf(values);
                visible.put(key, values);
                weights.put(key, weight);
                writtenAt.put(key, at);
            }
            committedFrontier = frontier;
            appliedFrontier = Math.max(appliedFrontier, frontier);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not restore view '" + name + "'", e);
        }
    }

    private static void writeValue(java.io.DataOutputStream out, Object value) throws java.io.IOException {
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
        } else if (value instanceof byte[] raw) {
            out.writeByte(5);
            out.writeInt(raw.length);
            out.write(raw);
        } else {
            out.writeByte(4);
            out.writeLong(((Number) value).longValue());
        }
    }

    private static Object readValue(java.io.DataInputStream in) throws java.io.IOException {
        byte tag = in.readByte();
        switch (tag) {
            case 0:
                return null;
            case 1:
                return in.readUTF();
            case 2:
                return in.readDouble();
            case 3:
                return in.readBoolean();
            case 5: {
                byte[] raw = new byte[in.readInt()];
                in.readFully(raw);
                return raw;
            }
            case 4:
                return in.readLong();
            default:
                throw new java.io.IOException("unknown value tag " + tag + " in a view snapshot");
        }
    }

    public String name() {
        return name;
    }

    /**
     * The shape of the rows this view holds, under the view's own name.
     *
     * <p>Renamed deliberately. A view's schema arrives from a query's output, which the planner
     * names after its inputs -- {@code txn_stream_windowed_aggregated} and worse. A client queries
     * {@code SELECT ... FROM user_volume}, so that is the name the schema has to carry, or two views
     * built from the same query shape collide under one name and the second is invisible.
     */
    public StreamSchema schema() {
        return presented;
    }

    public long committedFrontier() {
        return committedFrontier;
    }

    /** How far the view has been updated, committed or not. */
    public long appliedFrontier() {
        return appliedFrontier;
    }

    /**
     * Forgets rows the retention policy no longer covers.
     *
     * <p>Runs at commit, after the batch is applied, so a row written and aged out in the same
     * commit is never briefly visible. Eviction is silent to subscribers by design -- see
     * {@link Retention} -- because an evicted row was not withdrawn, it aged out.
     */
    private void evict() {
        if (retention.isForever()) {
            return;
        }
        long horizon = retention.horizonFor(committedFrontier);
        if (horizon > Long.MIN_VALUE) {
            java.util.Iterator<Map.Entry<Key, Object[]>> entries =
                    visible.entrySet().iterator();
            while (entries.hasNext()) {
                Key key = entries.next().getKey();
                Long written = writtenAt.get(key);
                if (written != null && written < horizon) {
                    entries.remove();
                    writtenAt.remove(key);
                    // The weight goes with the row. Leaving it behind would mean a key that is
                    // evicted and then inserted again starts from its old count rather than from
                    // nothing, and a later retraction would not be enough to remove it.
                    weights.remove(key);
                    evicted++;
                }
            }
        }
    }

    /** What this view is meant to keep. */
    public Retention retention() {
        return retention;
    }

    /**
     * Rows forgotten because they fell outside the retention policy.
     *
     * <p>Not an error count. It is how an operator sees the policy working, and how they notice a
     * window that is shorter than the questions people are asking of it.
     */
    public long evicted() {
        return evicted;
    }

    /** Keys committed and visible. */
    public synchronized int size() {
        return visible.size();
    }

    /** Changes applied but not yet visible to a consistent read. */
    public synchronized int pendingChanges() {
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

    /** The key of a row already read out as values, which is the shape a snapshot holds. */
    private Key keyOf(Object[] values) {
        Object[] key = new Object[keyOrdinals.length];
        for (int i = 0; i < keyOrdinals.length; i++) {
            key[i] = values[keyOrdinals[i]];
        }
        return new Key(key);
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
            case BYTES -> {
                // Not getString. A BYTES column fell through to the string branch, and
                // ArrowSchemas.write then cast that String to byte[] -- a ClassCastException on
                // every non-null value, which passed only while the column was entirely NULL.
                // The bytes themselves, read out of the region the slice points into. Going via
                // getString(...).getBytes(UTF_8) -- which this did -- round-trips arbitrary binary
                // through a decoder: anything that is not valid UTF-8 comes back as replacement
                // characters, so the column survived the cast and lost its contents instead.
                com.ash.messaging.pravaha.api.data.MutableSlice slice =
                        new com.ash.messaging.pravaha.api.data.MutableSlice();
                row.getBytes(ordinal, slice);
                byte[] bytes = new byte[slice.length()];
                if (bytes.length > 0 && row instanceof com.ash.messaging.pravaha.common.row.BinaryRowView binary) {
                    binary.region().getBytes(slice.offset(), bytes, 0, bytes.length);
                }
                yield bytes;
            }
            // Finding TY-19, the streaming half. Every ordinal is materialised when a row is
            // applied, so a DECIMAL column landed on the refusal below whatever the query selected
            // -- and a view fed by a lane was poisoned by a column nobody asked for, exactly as the
            // ad-hoc scan path was. A BigDecimal, matching what applyValues accepts and what the
            // result writer produces, so the column has one class wherever it is held.
            case DECIMAL ->
                com.ash.messaging.pravaha.common.row.Decimals.toBigDecimal(
                        row.getDecimalHigh(ordinal),
                        row.getDecimalLow(ordinal),
                        ((com.ash.messaging.pravaha.api.data.DecimalType)
                                        schema.field(ordinal).type())
                                .scale());
            default ->
                throw new PravahaException(
                        ServingErrors.UNSUPPORTED_QUERY,
                        "view '" + name + "' has a "
                                + schema.field(ordinal).type().typeName() + " column ('"
                                + schema.field(ordinal).name()
                                + "'), which this engine cannot serve. Falling through to the string reader "
                                + "produced a value of the wrong class at the wire, which surfaced as an "
                                + "internal error rather than as this sentence.");
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

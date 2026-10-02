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

    /**
     * The rows of each key that holds more than one distinct row, committed (VIEWW-1).
     *
     * <p>Sparse: a key holding one row at a time -- nearly every key -- has no entry, and its row
     * and weight are the ones in {@link #visible} and {@link #weights}.
     */
    private final Map<Key, KeyRows> rowsOf = new HashMap<>();

    /** The same, as the overlay has changed it; a null value means the key is back to one row. */
    private final Map<Key, KeyRows> pendingRowsOf = new HashMap<>();

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

    /**
     * Why the query behind this view died, or null while it is alive.
     *
     * <p>E-13. A view outlives the query that fills it: when a lane dies, `RegisteredQuery` moves to
     * `FAILED` and the view keeps every row it had at that moment — and a `SELECT` against it
     * answered from that frozen snapshot, indistinguishable from live data. A query that failed
     * three hours ago served three-hour-old rows to a reader with no way to tell.
     *
     * <p>That is the same shape as this project's other worst defects: not an error, a wrong answer
     * with a confident face. `PRV-8004 QUERY_FAILED` existed for exactly this and fired nowhere near
     * it — its throw sites were subscriber-side failures, which is a different event entirely.
     */
    private volatile com.ash.messaging.pravaha.api.PravahaException failure;

    /** Marks this view's producer as dead. Called by the registry when a query fails. */
    public void failed(com.ash.messaging.pravaha.api.PravahaException cause) {
        this.failure = cause;
    }

    /** Why the query behind this view died, if it did. */
    public java.util.Optional<com.ash.messaging.pravaha.api.PravahaException> failure() {
        return java.util.Optional.ofNullable(failure);
    }

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
     *
     * <p>When two different rows with the key are present at once, the key shows the one that most
     * recently gained weight, and a retraction takes weight from the row it names -- so withdrawing
     * one of them leaves the other showing, never the row just withdrawn (VIEWW-1).
     */
    private void applyWeighted(Key key, Object[] values, long weight, long frontier) {
        if (weight == 0) {
            return;
        }
        long net = weights.getOrDefault(key, 0L) + pendingWeight.merge(key, weight, Long::sum);
        if (net <= 0) {
            // A tombstone, kept in the overlay so a consistent read does not see the removal early.
            pending.put(key, null);
            stageRows(key, null);
            removals++;
        } else {
            pending.put(key, rowAfter(key, values, weight, net - weight));
            pendingTime.put(key, frontier);
            updates++;
        }
    }

    /**
     * The row a still-present key shows after one change of {@code weight} to {@code values}.
     *
     * @param before the key's net weight before the change
     */
    private Object[] rowAfter(Key key, Object[] values, long weight, long before) {
        KeyRows rows = pendingRowsOf.containsKey(key) ? pendingRowsOf.get(key) : rowsOf.get(key);
        if (rows == null) {
            Object[] current = before <= 0 ? null : pending.containsKey(key) ? pending.get(key) : visible.get(key);
            if (current == null || KeyRows.same(current, values)) {
                // Absent until now, or the same row again: one row, which the view's own maps hold.
                return values;
            }
            if (weight < 0) {
                // The key's only row, whatever values the retraction carries: summing the key's
                // weight is all this ever needed.
                return current;
            }
            KeyRows two = KeyRows.of(current, before, values, weight);
            stageRows(key, two);
            return two.shown();
        }
        // Copied before the first change since the commit: the committed rows are what a
        // checkpoint writes, and they must not move until the overlay does.
        KeyRows changed = pendingRowsOf.containsKey(key) ? rows : rows.copy();
        if (weight > 0) {
            changed.add(values, weight);
        } else {
            changed.retract(values, -weight);
        }
        stageRows(key, changed.size() > 1 ? changed : null);
        return changed.shown();
    }

    /** Records a key's rows in the overlay; null when it holds one row or none. */
    private void stageRows(Key key, KeyRows rows) {
        if (rows != null || pendingRowsOf.containsKey(key) || rowsOf.containsKey(key)) {
            pendingRowsOf.put(key, rows);
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
     * Applies a whole batch of changes already decoded into values, in one step.
     *
     * <p>For a lane's batch, which must reach the overlay whole: a reader of the latest state, or a
     * commit, that took this view's monitor between two of its rows would see an update's retraction
     * without its insert (VIEW-1). Same semantics per row as {@link #applyValues}.
     *
     * @param frontiers the input position each change reflects
     */
    public synchronized void applyBatch(List<Object[]> values, long[] weights, long[] frontiers, int count) {
        for (int i = 0; i < count; i++) {
            applyValues(values.get(i), weights[i], frontiers[i]);
        }
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
        // ADR-056: what a following query is handed is how the ANSWER changed -- the row held
        // under each touched key before and after -- not what was applied, which for a key upserted
        // without a retraction is a second row the view never shows.
        if (!answerListeners.isEmpty() || answerWanted) {
            leaving = new ArrayList<>();
            entering = new ArrayList<>();
        }
        pending.forEach((key, values) -> {
            // The index entry goes with the row it points at, in this same critical section: the
            // previous row first, because an update that changed the ordered column would otherwise
            // leave the old entry behind and the index would answer with a row the view no longer
            // holds.
            Object[] replaced = visible.get(key);
            if (leaving != null && !java.util.Arrays.deepEquals(replaced, values)) {
                if (replaced != null) {
                    leaving.add(replaced);
                }
                if (values != null) {
                    entering.add(values);
                }
            }
            if (replaced != null) {
                indexRemove(replaced);
                // The equality indexes are told the row the view held, never the retraction: the
                // entry to delete is filed under the PREVIOUS row's value, and this is the one
                // place that value is known for certain (ADR-055).
                for (EqualityIndex<Key> index : equalityIndexes.values()) {
                    index.remove(key, replaced);
                }
            }
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
                indexPut(values);
                for (EqualityIndex<Key> index : equalityIndexes.values()) {
                    index.put(key, values);
                }
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
        pendingRowsOf.forEach((key, rows) -> {
            if (rows == null) {
                rowsOf.remove(key);
            } else {
                rowsOf.put(key, rows);
            }
        });
        pending.clear();
        pendingTime.clear();
        pendingWeight.clear();
        pendingRowsOf.clear();
        committedFrontier = frontier;
        evict();
        handAnswerOver(frontier);
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
        scans++;
        return new ArrayList<>(visible.values());
    }

    // ------------------------------------------------------------------ the ordered index
    //
    // Design section 17.2 asks for three access paths and this engine had one of them. A point
    // lookup by the whole key was a full scan with a filter on top, because every read surface --
    // Flight SQL, GET /api/v1/views/{name}/query and the PostgreSQL gateway alike -- goes through
    // ViewQuery, and ViewQuery scanned. So `WHERE user_id = 'u_42'` over a million-row view read a
    // million rows to answer with one, and the 50 microsecond target in that table was a target
    // nothing was measured against.
    //
    // Two paths are added here and one is deliberately not.
    //
    //  * The whole key, by equality: one hash probe into `visible`. It needs no index and no
    //    declaration, because the view already IS a hash map keyed by exactly that.
    //  * The key's leading columns by equality and its LAST column between bounds: the ordered
    //    index below. `RANGE (column)` in the statement declares it -- see ADR-049 for why that
    //    declaration is a check rather than an allocation.
    //  * A predicate on a column that is not in the key is a scan and a filter unless the column
    //    was declared with INDEX (column), in which case it is an equality-index probe (ADR-055).
    //    That index has the failure mode ADR-049 declined it for -- a row's non-key values change
    //    under it, so the entry to delete must be found from the row's PREVIOUS values -- and the
    //    answer is that the previous row is the one in `visible`, read under this monitor in the
    //    same critical section that replaces it, never the retraction that caused the change.

    /**
     * The ordered index: for each value of the key's leading columns, the rows with that prefix
     * sorted by the key's last column.
     *
     * <p>Null until something asks for a range, and dropped when a restore replaces the contents.
     * The rows in it are the same {@code Object[]} instances the visible map holds -- an index
     * entry is a reference and a tree node, not a copy of the row -- so the index adds a bounded
     * per-key overhead to a map that already keeps three entries per key, and it holds exactly one
     * entry per visible key: the ceiling that bounds the view bounds it too, with no second number
     * for an operator to get wrong. It does not spill. Neither does the view (the mapped tier of
     * ADR-044 is operator state, not the serving map), and an index that spilled while the rows it
     * points at did not would be slower than the scan it replaces.
     *
     * <p>Guarded by {@code this}, with the maps above, and maintained inside {@code commit} -- so
     * an index entry becomes visible in the same critical section as the row it points at, and a
     * reader can never see one without the other.
     */
    private Map<Key, java.util.TreeMap<Object, Object[]>> ordered;

    private long pointLookups;
    private long rangeLookups;
    private long scans;
    private long indexBuilds;

    /**
     * Compares the key's last column. Every type an ordered index is allowed over ({@code
     * ContinuousStatement.Create.rangeOrdinal}) arrives here as a {@link Number} whose {@code
     * longValue} is exact -- the integral widths, {@code DATE} as an epoch day, {@code TIME} and
     * {@code TIMESTAMP} as a count since their epoch -- so one comparator covers them all and none
     * of them needs a widening that could lose a digit.
     */
    private static final java.util.Comparator<Object> RANGE_ORDER =
            java.util.Comparator.comparingLong(value -> ((Number) value).longValue());

    /** Whether {@code value} is one this index can sort. A null range value is in no range. */
    private static boolean orderable(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }

    private Key prefixOf(Object[] values) {
        Object[] prefix = new Object[keyOrdinals.length - 1];
        for (int i = 0; i < prefix.length; i++) {
            prefix[i] = values[keyOrdinals[i]];
        }
        return new Key(prefix);
    }

    private void indexPut(Object[] values) {
        if (ordered == null) {
            return;
        }
        Object last = values[keyOrdinals[keyOrdinals.length - 1]];
        if (!orderable(last)) {
            // A NULL in the ordered column, or a column this index was never meant to sort. It
            // satisfies no bound, so leaving it out of the index costs no answer -- and this is
            // why a range read is the only read the index answers: a prefix-only read would have
            // to find these rows too.
            return;
        }
        ordered.computeIfAbsent(prefixOf(values), ignored -> new java.util.TreeMap<>(RANGE_ORDER))
                .put(last, values);
    }

    private void indexRemove(Object[] values) {
        if (ordered == null) {
            return;
        }
        Object last = values[keyOrdinals[keyOrdinals.length - 1]];
        if (!orderable(last)) {
            return;
        }
        Key prefix = prefixOf(values);
        java.util.TreeMap<Object, Object[]> bucket = ordered.get(prefix);
        if (bucket != null) {
            bucket.remove(last);
            if (bucket.isEmpty()) {
                ordered.remove(prefix);
            }
        }
    }

    /** Builds the index from the committed map. Called under the monitor, once, on first use. */
    private void buildIndex() {
        ordered = new HashMap<>();
        indexBuilds++;
        for (Object[] values : visible.values()) {
            indexPut(values);
        }
    }

    // ------------------------------------------------------------------ equality indexes (ADR-055)

    /**
     * How many columns of one view may carry an equality index. Each costs one entry per visible
     * row, so this and the view's key ceiling together bound what the indexes can hold.
     */
    public static final int MAX_EQUALITY_INDEXES = 4;

    /**
     * Declared equality indexes, by the column each is over. Guarded by {@code this}, maintained in
     * {@code commit}, {@code evict} and {@code restore} with the rows they point at.
     */
    private final Map<Integer, EqualityIndex<Key>> equalityIndexes = new java.util.LinkedHashMap<>();

    private long indexLookups;
    private long equalityIndexBuilds;

    /**
     * Keeps an equality index over {@code ordinal} from now on, built at once from the committed
     * rows. Declaring one that is already kept does nothing, because two registrations sharing
     * this view may each declare it.
     *
     * <p>Which columns may be indexed is judged at registration, against the planned output, by
     * {@code ContinuousStatement.Create.indexOrdinal}; this refuses only what would be unsafe
     * whoever asked.
     *
     * @throws PravahaException {@code PRV-2074} past {@link #MAX_EQUALITY_INDEXES}
     */
    public synchronized void index(int ordinal) {
        if (ordinal < 0 || ordinal >= schema.fieldCount()) {
            throw new IllegalArgumentException("view '" + name + "' has " + schema.fieldCount()
                    + " columns, so there is no column " + ordinal + " to index");
        }
        if (equalityIndexes.containsKey(ordinal)) {
            return;
        }
        if (equalityIndexes.size() >= MAX_EQUALITY_INDEXES) {
            throw new PravahaException(
                    com.ash.messaging.pravaha.sql.SqlErrors.INDEX_UNUSABLE,
                    "view '" + name + "' already keeps " + MAX_EQUALITY_INDEXES + " equality indexes, on "
                            + indexedColumnNames() + ", and that is the most one view keeps: each costs an entry "
                            + "per row, and the view's ceiling bounds the rows, not the indexes. Index one "
                            + "of those columns, or read '"
                            + schema.field(ordinal).name() + "' by a scan.");
        }
        EqualityIndex<Key> index = new EqualityIndex<>(ordinal);
        rebuild(index);
        equalityIndexes.put(ordinal, index);
    }

    private void rebuild(EqualityIndex<Key> index) {
        index.clear();
        equalityIndexBuilds++;
        for (Map.Entry<Key, Object[]> entry : visible.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Stops keeping the equality index over {@code ordinal}, and lets go of its entries (IDXSHR-1):
     * called when no registration answered by this view declares it any more. Nothing when none is
     * kept.
     */
    public synchronized void dropIndex(int ordinal) {
        EqualityIndex<Key> index = equalityIndexes.remove(ordinal);
        if (index != null) {
            index.clear();
        }
    }

    /** The columns an equality index is kept over, in the order they were declared. */
    public synchronized List<Integer> indexedColumns() {
        return List.copyOf(equalityIndexes.keySet());
    }

    private List<String> indexedColumnNames() {
        List<String> names = new ArrayList<>();
        equalityIndexes
                .keySet()
                .forEach(ordinal -> names.add(schema.field(ordinal).name()));
        return names;
    }

    /**
     * The committed rows whose column {@code ordinal} holds one of {@code values}, from that
     * column's equality index: one probe per value, not a scan.
     *
     * <p>Each value must already be the column's stored class -- {@code ViewAccessPath} converts a
     * literal before it asks -- because the index is filed by stored-value equality. A value given
     * twice is probed once, so a row is never returned twice.
     *
     * @throws IllegalArgumentException when no index is kept over {@code ordinal}
     */
    public synchronized List<Object[]> committedWith(int ordinal, java.util.Collection<?> values) {
        EqualityIndex<Key> index = equalityIndexes.get(ordinal);
        if (index == null) {
            throw new IllegalArgumentException("view '" + name + "' keeps no equality index over column " + ordinal
                    + "; it keeps " + equalityIndexes.keySet());
        }
        indexLookups++;
        List<Object[]> rows = new ArrayList<>();
        for (Object value : new java.util.LinkedHashSet<>(values)) {
            rows.addAll(index.rowsWith(value));
        }
        return rows;
    }

    /** Entries the equality index over {@code ordinal} holds; zero when there is none. */
    public synchronized long indexEntries(int ordinal) {
        EqualityIndex<Key> index = equalityIndexes.get(ordinal);
        return index == null ? 0 : index.entries();
    }

    /** Reads answered by probing an equality index rather than by a scan. */
    public long indexLookups() {
        return indexLookups;
    }

    /** Times an equality index was built from the committed rows: once when declared, once per restore. */
    public long equalityIndexBuilds() {
        return equalityIndexBuilds;
    }

    /**
     * The committed row under the whole key, or empty when there is none.
     *
     * <p>One hash probe. This is design section 17.2's point lookup, and it is the same map a
     * consistent read of {@link #get} sees -- {@link #scan} would have found exactly this row and
     * read every other one on the way.
     */
    public synchronized Optional<Object[]> committedRow(Object[] key) {
        pointLookups++;
        return Optional.ofNullable(visible.get(new Key(key.clone())));
    }

    /**
     * The committed rows whose key begins with {@code prefix} and whose last key column lies
     * between the bounds, in ascending order of that column.
     *
     * <p>A null bound is unbounded on that side. Rows whose ordered column is null are in no range
     * and are not returned, which is what SQL's three-valued logic says about {@code column >
     * anything} when the column is null.
     *
     * @param prefix the key's leading columns, one value each; empty for a single-column key
     */
    public synchronized List<Object[]> committedRange(
            Object[] prefix, Object low, boolean lowInclusive, Object high, boolean highInclusive) {
        if (prefix.length != keyOrdinals.length - 1) {
            throw new IllegalArgumentException("view '" + name + "' is keyed by " + keyOrdinals.length
                    + " columns, so a range needs " + (keyOrdinals.length - 1) + " leading values, not "
                    + prefix.length);
        }
        if (ordered == null) {
            buildIndex();
        }
        rangeLookups++;
        // An empty range, before the tree is asked. `WHERE at > 90 AND at < 40` is a predicate a
        // generated query reaches in seconds and a human writes by getting two bounds the wrong way
        // round, and a TreeMap answers it by throwing "toKey out of range" rather than with no
        // rows -- which would have turned a read that correctly finds nothing into an internal
        // error (caught by ViewIndexEquivalenceTest, seed 2026091908).
        if (low != null && high != null) {
            int order = RANGE_ORDER.compare(low, high);
            if (order > 0 || (order == 0 && !(lowInclusive && highInclusive))) {
                return List.of();
            }
        }
        java.util.TreeMap<Object, Object[]> bucket = ordered.get(new Key(prefix.clone()));
        if (bucket == null) {
            return List.of();
        }
        java.util.NavigableMap<Object, Object[]> slice = bucket;
        if (low != null) {
            slice = slice.tailMap(low, lowInclusive);
        }
        if (high != null) {
            slice = slice.headMap(high, highInclusive);
        }
        return new ArrayList<>(slice.values());
    }

    /** Entries the ordered index holds; zero when nothing has asked for a range yet. */
    public synchronized long indexedRows() {
        if (ordered == null) {
            return 0;
        }
        long rows = 0;
        for (java.util.TreeMap<Object, Object[]> bucket : ordered.values()) {
            rows += bucket.size();
        }
        return rows;
    }

    /** Reads answered by a hash probe on the whole key rather than by a scan. */
    public long pointLookups() {
        return pointLookups;
    }

    /** Reads answered from the ordered index rather than by a scan. */
    public long rangeLookups() {
        return rangeLookups;
    }

    /** Reads that walked every committed row. */
    public long scans() {
        return scans;
    }

    /** Times the ordered index has been built from the committed map: once, then once per restore. */
    public long indexBuilds() {
        return indexBuilds;
    }

    /**
     * Every committed row as a change that would build it from nothing: its values, and its net
     * weight -- a key inserted twice is present with weight 2, and a subscriber summing weights must
     * be told so or the first retraction of it would empty its copy while the view still holds it.
     *
     * <p>The committed map only, never the overlay: this is what a subscription's snapshot is made
     * of (SUB-1), and a snapshot is a state some commit published. The frontier it is true at is
     * {@link #committedFrontier()} read under the same lock; {@link ViewSink} takes both inside
     * the critical section that orders it against every commit.
     */
    public synchronized List<ViewChange> committedRows() {
        List<ViewChange> rows = new ArrayList<>(visible.size());
        for (Map.Entry<Key, Object[]> entry : visible.entrySet()) {
            KeyRows several = rowsOf.get(entry.getKey());
            if (several != null) {
                // Every row of the key with its own weight: a subscriber's copy is the Z-set, and a
                // later retraction names one of these rows, not the key (VIEWW-1). The row the key
                // shows comes last, so a reader that overwrites by key ends on it.
                several.forEach((values, weight) -> rows.add(new ViewChange(values, weight)));
            } else {
                rows.add(new ViewChange(entry.getValue(), weights.getOrDefault(entry.getKey(), 1L)));
            }
        }
        return rows;
    }

    /**
     * Marks a view snapshot, so a snapshot of another format -- or bytes that are not one -- is
     * refused rather than read as rows.
     */
    private static final int SNAPSHOT_MAGIC = 0x50525656; // "PRVV"

    /**
     * Version 2: every value is written as its own class.
     *
     * <p>Version 1 had no header and four value tags. Every integral number went out as a {@code
     * long} and every floating one as a {@code double}, and a {@code BigDecimal} went through {@code
     * longValue()} -- so an {@code INT32} key came back a {@code Long}, which is not {@code equal} to
     * the {@code Integer} the engine goes on writing, and {@code 12.345} came back {@code 12}. After a
     * restore the next update for a key was a second row beside the first, a retraction missed the
     * row it withdrew, and a sink seeded from the difference was sent the wrong one (VIEW-2).
     *
     * <p>A version 1 snapshot is refused, not converted. Its decimals have already lost their
     * fractions, and nothing in the bytes says which ones did; reading it back would restore an
     * answer that is wrong without looking wrong. The query starts from its sources instead, as it
     * does for any checkpoint it cannot read.
     */
    private static final int SNAPSHOT_VERSION = 2;

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
     *
     * <p>Every value keeps its class and its exact value: see {@link #SNAPSHOT_VERSION}.
     */
    public synchronized byte[] snapshot() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(SNAPSHOT_VERSION);
            out.writeLong(committedFrontier);
            List<ViewChange> rows = committedRows();
            out.writeInt(rows.size());
            for (ViewChange row : rows) {
                // A key holding several rows writes each, the row it shows last; a restore sums them
                // back into one key (VIEWW-1). Every row of a key was written at the key's time.
                Object[] values = row.values();
                out.writeInt(values.length);
                for (int column = 0; column < values.length; column++) {
                    ViewValues.write(
                            out, values[column], name, schema.field(column).name());
                }
                out.writeLong(row.weight());
                out.writeLong(writtenAt.getOrDefault(keyOf(values), committedFrontier));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not snapshot view '" + name + "'", e);
        }
        return bytes.toByteArray();
    }

    /** One row of a snapshot, read back. */
    private record SnapshotRow(Object[] values, long weight, long writtenAt) {}

    /** A snapshot, read back whole before anything is done with it. */
    private record SnapshotContents(long frontier, List<SnapshotRow> rows) {}

    /**
     * Refuses a snapshot this engine did not write in its current format, without reading the rows.
     *
     * <p>For a caller that must know before it restores anything else beside the view: operator
     * state restored next to a view that is then refused is state with no offsets to resume from.
     */
    public static void requireReadable(byte[] snapshot) {
        if (snapshot == null || snapshot.length == 0) {
            return;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(snapshot))) {
            readHeader(in, "a view");
        } catch (java.io.IOException e) {
            throw unreadable("a view", "it is shorter than its own header", e);
        }
    }

    private static void readHeader(java.io.DataInputStream in, String whose) throws java.io.IOException {
        int magic = in.readInt();
        if (magic != SNAPSHOT_MAGIC) {
            // Version 1 had no header: its first eight bytes were the committed frontier.
            throw unreadable(
                    whose,
                    "it is not a version " + SNAPSHOT_VERSION + " view snapshot. A snapshot written before "
                            + "the format was versioned (version 1) stored INT32, INT16 and INT8 values as INT64, "
                            + "FLOAT32 as FLOAT64 and DECIMAL truncated to a whole number, so it cannot be read "
                            + "back as the values it was taken from",
                    null);
        }
        int version = in.readInt();
        if (version != SNAPSHOT_VERSION) {
            throw unreadable(
                    whose,
                    "it is view snapshot format version " + version + " and this engine reads version "
                            + SNAPSHOT_VERSION + ". Refusing to guess at the difference",
                    null);
        }
    }

    private static PravahaException unreadable(String whose, String why, Throwable cause) {
        return new PravahaException(
                com.ash.messaging.pravaha.state.StateErrors.STATE_UNREADABLE,
                "cannot restore " + whose + " from this checkpoint: " + why + ". The query resumes from its "
                        + "sources instead, as it does for any checkpoint it cannot read.",
                cause);
    }

    private SnapshotContents read(byte[] snapshot) {
        String whose = "view '" + name + "'";
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(snapshot))) {
            readHeader(in, whose);
            long frontier = in.readLong();
            int count = in.readInt();
            List<SnapshotRow> rows = new ArrayList<>(Math.max(0, Math.min(count, 1 << 16)));
            for (int i = 0; i < count; i++) {
                Object[] values = new Object[in.readInt()];
                for (int v = 0; v < values.length; v++) {
                    values[v] = ViewValues.read(in);
                }
                rows.add(new SnapshotRow(values, in.readLong(), in.readLong()));
            }
            return new SnapshotContents(frontier, rows);
        } catch (java.io.IOException e) {
            throw unreadable(whose, "it is truncated or corrupt (" + e.getMessage() + ")", e);
        }
    }

    /**
     * The changes that take a reader holding {@code snapshot}'s contents to this view's committed
     * contents now: a retraction for every row that has gone or changed, then an insert for every
     * row that is new or changed.
     *
     * <p>What a sink needs when it resumes from a checkpoint after the view has moved on without it
     * -- a second name on a computation a restart has already started, whose sink holds exactly
     * what the checkpoint's view held. Sending it the whole view would repeat every row it has;
     * sending it nothing would lose what changed while it was away.
     *
     * <p>Rows are compared as values. They used to be compared as a version 1 snapshot encoded them,
     * because a restored view held the classes that format read back rather than the ones the engine
     * wrote -- and that encoding truncated a decimal, so a change from {@code 2.250} to {@code 2.251}
     * compared equal and was never sent (VIEW-2). A snapshot now reads back exactly what it was taken
     * from, so the comparison is the plain one.
     *
     * @param snapshot an earlier {@link #snapshot()} of this same view
     * @param withRetractions false for a reader that can only append, which is sent the inserts
     */
    public synchronized List<ViewChange> changesSince(byte[] snapshot, boolean withRetractions) {
        Map<Key, Object[]> then = new LinkedHashMap<>();
        if (snapshot != null && snapshot.length > 0) {
            for (SnapshotRow row : read(snapshot).rows()) {
                then.put(keyOf(row.values()), row.values());
            }
        }
        List<ViewChange> retractions = new ArrayList<>();
        List<ViewChange> inserts = new ArrayList<>();
        for (Map.Entry<Key, Object[]> entry : visible.entrySet()) {
            Object[] now = entry.getValue();
            Object[] before = then.remove(entry.getKey());
            if (before == null) {
                inserts.add(new ViewChange(now.clone(), 1));
            } else if (!Arrays.deepEquals(before, now)) {
                retractions.add(new ViewChange(before, -1));
                inserts.add(new ViewChange(now.clone(), 1));
            }
        }
        for (Object[] gone : then.values()) {
            retractions.add(new ViewChange(gone, -1));
        }
        List<ViewChange> changes = new ArrayList<>(withRetractions ? retractions : List.of());
        changes.addAll(inserts);
        return changes;
    }

    /**
     * Replaces this view's contents with a snapshot's.
     *
     * <p>Replaces rather than merges: a restore happens into a view that has just been built and is
     * empty, and merging would quietly double a row if that ever stopped being true.
     *
     * <p>Read whole before anything is cleared, so a snapshot that is refused leaves the view as it
     * was rather than half replaced.
     */
    public synchronized void restore(byte[] snapshot) {
        if (snapshot == null || snapshot.length == 0) {
            return;
        }
        SnapshotContents contents = read(snapshot);
        // Dropped rather than maintained through the restore: the whole contents are being
        // replaced, so rebuilding it once from the restored rows is cheaper than the row-by-row
        // maintenance would be -- and it happens on the next range read, which may never come.
        ordered = null;
        visible.clear();
        weights.clear();
        writtenAt.clear();
        pending.clear();
        pendingWeight.clear();
        pendingTime.clear();
        rowsOf.clear();
        pendingRowsOf.clear();
        for (SnapshotRow row : contents.rows()) {
            Key key = keyOf(row.values());
            Object[] earlier = visible.put(key, row.values());
            Long earlierWeight = weights.put(key, row.weight());
            if (earlier != null) {
                // A second row of the same key: the key holds several, and the last is shown.
                KeyRows several = rowsOf.get(key);
                if (several == null) {
                    several = KeyRows.empty();
                    several.add(earlier, earlierWeight);
                    rowsOf.put(key, several);
                }
                several.add(row.values(), row.weight());
                weights.put(key, earlierWeight + row.weight());
            }
            writtenAt.put(key, row.writtenAt());
        }
        committedFrontier = contents.frontier();
        // Replaced with the rest, not merged: the overlay was cleared above, so nothing is applied
        // beyond what was committed. Kept as a maximum, a restore undone (RESTOREPART-1) -- the
        // checkpoint's view put back to the empty one taken before it -- left the view reporting
        // staleness from a frontier it no longer held anything at.
        appliedFrontier = contents.frontier();
        // The equality indexes are declared, not built on demand, so they are rebuilt here and now
        // from the restored rows: a read by the indexed column straight after a restore must find
        // them, and the index is never allowed to be a step behind the view it indexes.
        for (EqualityIndex<Key> index : equalityIndexes.values()) {
            rebuild(index);
        }
        // A follower's copy of the answer is now of a different answer: it is handed the restored
        // one as a fresh snapshot and diffs it against what it holds (ADR-056).
        for (AnswerListener listener : answerListeners) {
            listener.onSnapshot(new ArrayList<>(visible.values()), committedFrontier);
        }
    }

    // ------------------------------------------------------------------ following the answer

    /** Followers of this view's answer (ADR-056); see {@link #followAnswer}. */
    private final List<AnswerListener> answerListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The rows leaving and entering the answer in the commit in progress; null when nobody follows. */
    private List<Object[]> leaving;

    private List<Object[]> entering;

    /**
     * {@link #leaving} and {@link #entering} as they stood before this commit's first eviction, or
     * null when nothing aged out: the answer change a sink is handed, to which eviction stays silent
     * (SINKKEYROWS-1).
     */
    private List<Object[]> keptLeaving;

    private List<Object[]> keptEntering;

    /**
     * Hands {@code listener} the committed answer now, and every change to it after, from inside
     * the critical section of each commit (ADR-056).
     *
     * <p>The snapshot is taken and the listener registered under this view's monitor, which every
     * commit takes too, so no commit falls between the two and none is seen twice. The listener is
     * called under the monitor and must only queue: it runs on whatever thread commits.
     */
    public synchronized void followAnswer(AnswerListener listener) {
        answerListeners.add(listener);
        listener.onSnapshot(new ArrayList<>(visible.values()), committedFrontier);
    }

    /** Hands a follower the committed answer again, in the same critical section as any commit. */
    public synchronized void resnapshotAnswer(AnswerListener listener) {
        if (answerListeners.contains(listener)) {
            listener.onSnapshot(new ArrayList<>(visible.values()), committedFrontier);
        }
    }

    /** Stops handing {@code listener} this view's changes. */
    public void unfollowAnswer(AnswerListener listener) {
        answerListeners.remove(listener);
    }

    /** How many queries follow this view's answer. */
    public int answerFollowers() {
        return answerListeners.size();
    }

    private void handAnswerOver(long frontier) {
        List<Object[]> left = leaving;
        List<Object[]> entered = entering;
        leaving = null;
        entering = null;
        lastAnswer = AnswerChanges.handOver(answerListeners, left, entered, frontier);
        lastRetained = keptLeaving == null ? lastAnswer : AnswerChanges.net(keptLeaving, keptEntering);
        keptLeaving = null;
        keptEntering = null;
    }

    /**
     * Whether each commit keeps its netted answer change for {@link #takeAnswer}: set by a {@link
     * ViewSink} while a subscriber follows the answer rather than the changelog (KEYEDWT-1).
     */
    private volatile boolean answerWanted;

    /** The last commit's netted answer change, until taken; null when it changed nothing. */
    private AnswerChanges.Netted lastAnswer;

    void answerWanted(boolean wanted) {
        answerWanted = wanted;
    }

    /** Takes the answer change the last commit kept, under the monitor that commit held. */
    synchronized AnswerChanges.Netted takeAnswer() {
        AnswerChanges.Netted taken = lastAnswer;
        lastAnswer = null;
        return taken;
    }

    /** The last commit's answer change with nothing aged out, for a sink (SINKKEYROWS-1); null when none. */
    private AnswerChanges.Netted lastRetained;

    /** Takes {@link #lastRetained}, as {@link #takeAnswer} takes the answer. */
    synchronized AnswerChanges.Netted takeRetainedAnswer() {
        AnswerChanges.Netted taken = lastRetained;
        lastRetained = null;
        return taken;
    }

    /**
     * The committed answer as changes that build it from nothing: each row a reader sees, once,
     * at weight {@code +1} -- where {@link #committedRows} is the Z-set, several rows of one key
     * with their own weights. What a subscriber following the answer starts from (KEYEDWT-1).
     */
    synchronized List<ViewChange> committedAnswer() {
        List<ViewChange> rows = new ArrayList<>(visible.size());
        for (Object[] values : visible.values()) {
            rows.add(new ViewChange(values, 1L));
        }
        return rows;
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
                Map.Entry<Key, Object[]> entry = entries.next();
                Key key = entry.getKey();
                Long written = writtenAt.get(key);
                if (written != null && written < horizon) {
                    indexRemove(entry.getValue());
                    for (EqualityIndex<Key> index : equalityIndexes.values()) {
                        index.remove(key, entry.getValue());
                    }
                    entries.remove();
                    if (leaving != null && keptLeaving == null) {
                        keptLeaving = new ArrayList<>(leaving);
                        keptEntering = new ArrayList<>(entering);
                    }
                    // A row that entered in this very commit never reached the answer: it leaves
                    // the entering list rather than being handed over as both.
                    if (leaving != null && !entering.remove(entry.getValue())) {
                        leaving.add(entry.getValue());
                    }
                    writtenAt.remove(key);
                    // The weight goes with the row. Leaving it behind would mean a key that is
                    // evicted and then inserted again starts from its old count rather than from
                    // nothing, and a later retraction would not be enough to remove it.
                    weights.remove(key);
                    rowsOf.remove(key);
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

    private final java.util.concurrent.atomic.AtomicLong timedCommits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong commitNanos = new java.util.concurrent.atomic.AtomicLong();

    /** Recorded by the {@link ViewSink} that commits this view, for each commit that changed it. */
    void recordCommitNanos(long nanos) {
        commitNanos.addAndGet(Math.max(0, nanos));
        timedCommits.incrementAndGet();
    }

    /**
     * Commits that changed this view and were timed, from apply to the last listener delivered.
     *
     * <p>A count and a total rather than a distribution: the mean over any window is the difference
     * of two totals divided by the difference of two counts, which is exact. Percentiles would need
     * every commit's duration kept, and this does not keep them, so it does not claim them.
     */
    public long timedCommits() {
        return timedCommits.get();
    }

    /** The total time {@link #timedCommits} took, in nanoseconds. */
    public long commitNanosTotal() {
        return commitNanos.get();
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

    /**
     * A key by value, so it can be a map key.
     *
     * <p>Deep, because a {@code BYTES} key column holds a {@code byte[]}, and an array's {@code
     * equals} is identity: two rows with the same bytes were two keys, so an update beside the row it
     * replaced and a retraction that found nothing to withdraw.
     */
    private record Key(Object[] values) {

        /**
         * A view's key compares a DOUBLE as SQL groups it (NANGROUP-1): either zero is one key, as
         * every NaN already was under {@link Double#equals}. The row keeps its own value.
         */
        private Key {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Double d && d == 0.0) {
                    values[i] = 0.0;
                } else if (values[i] instanceof Float f && f == 0.0f) {
                    values[i] = 0.0f;
                }
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Arrays.deepEquals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
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

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An equality index over one column of a view: for each value, the rows holding it, by key
 * (ADR-055).
 *
 * <p><strong>Filed by the row the view held, never by a retraction.</strong> The failure an index
 * over a non-key column invites is removing an entry from under the wrong value: an update that
 * changed the indexed column must take the key out of the old value's bucket, and a Z-set
 * retraction need not carry the old value at all. So this structure is never told what changed.
 * The view hands it the row it is <em>replacing</em> -- the one in its own committed map, under its
 * own monitor -- and {@link #remove} files the removal under that row's value. What the index holds
 * is therefore always a function of what the view holds.
 *
 * <p>A row whose indexed value is {@code null} is not filed: {@code column = anything} is never
 * true of it, so no read this index answers wants it.
 *
 * <p>Not thread-safe. The view that owns it guards it with its own monitor, the same one that
 * orders a commit against a reader, so an entry becomes visible in the same critical section as
 * the row it points at.
 *
 * @param <K> the view's key: an entry is one key under one value, so two rows under one value are
 *     two entries and a row is in exactly one bucket
 */
final class EqualityIndex<K> {

    private final int ordinal;

    /** Value to the rows holding it, by key. Linked, so a probe answers in the order rows arrived. */
    private final Map<Object, Map<K, Object[]>> byValue = new HashMap<>();

    private long entries;

    /** @param ordinal the view column indexed */
    EqualityIndex(int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("an index is over a column, and " + ordinal + " is not one");
        }
        this.ordinal = ordinal;
    }

    /** The column indexed. */
    int ordinal() {
        return ordinal;
    }

    /** Files {@code row} under its value of the indexed column. */
    void put(K key, Object[] row) {
        Object value = row[ordinal];
        if (value == null) {
            return;
        }
        Object[] previous =
                byValue.computeIfAbsent(value, ignored -> new LinkedHashMap<>()).put(key, row);
        if (previous == null) {
            entries++;
        }
    }

    /**
     * Takes {@code key} out of the bucket {@code row} is filed under.
     *
     * @param row the row the view held for {@code key} -- its previous row, never the incoming one
     */
    void remove(K key, Object[] row) {
        Object value = row[ordinal];
        if (value == null) {
            return;
        }
        Map<K, Object[]> bucket = byValue.get(value);
        if (bucket == null) {
            return;
        }
        if (bucket.remove(key) != null) {
            entries--;
        }
        if (bucket.isEmpty()) {
            byValue.remove(value);
        }
    }

    /** The rows whose indexed column is {@code value}; none for a {@code null} value. */
    List<Object[]> rowsWith(Object value) {
        if (value == null) {
            return List.of();
        }
        Map<K, Object[]> bucket = byValue.get(value);
        return bucket == null ? List.of() : new ArrayList<>(bucket.values());
    }

    /** Empties it, for a restore that replaces the view's contents. */
    void clear() {
        byValue.clear();
        entries = 0;
    }

    /** Entries held: one per visible row whose indexed column is not null. */
    long entries() {
        return entries;
    }

    /** Distinct values held. */
    int values() {
        return byValue.size();
    }

    @Override
    public String toString() {
        return "EqualityIndex[column " + ordinal + ", " + entries + " entries under " + byValue.size() + " values]";
    }
}

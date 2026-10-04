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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows one key of a view holds, when it holds more than one (VIEWW-1).
 *
 * <p>A view shows one row per key, but its input is a Z-set of rows, and two different rows with
 * the same key can both be present: an insert of A and an insert of B is weight 2 on the key and
 * weight 1 on each row. The view used to keep only the key's net weight and whichever values
 * arrived last, so a retraction of A that left the key present showed A -- the row just withdrawn
 * -- over B, the row still there.
 *
 * <p>This holds the rows themselves, each with its own positive weight, in the order they last
 * gained weight. The key shows the last of them. It exists only while a key holds two or more
 * distinct rows: the overwhelmingly common key, one row at a time, needs no more than the view's
 * own maps already keep, and pays nothing for this.
 *
 * <p>Not thread-safe; guarded by the view's monitor with everything else.
 */
final class KeyRows {

    /** Row values compared as values, as the view's keys are. */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    private record Row(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Row that && Arrays.deepEquals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }
    }

    /** Each present row and its weight, always positive, oldest gain first. */
    private final LinkedHashMap<Row, Long> rows = new LinkedHashMap<>();

    private KeyRows() {}

    /** Two distinct rows of one key: {@code first}, then {@code second}, which is the one shown. */
    static KeyRows of(Object[] first, long firstWeight, Object[] second, long secondWeight) {
        KeyRows rows = new KeyRows();
        rows.rows.put(new Row(first), firstWeight);
        rows.rows.put(new Row(second), secondWeight);
        return rows;
    }

    /** An empty set of rows, filled by {@link #add} -- for a restore. */
    static KeyRows empty() {
        return new KeyRows();
    }

    /** Whether two rows are the same row, by value. */
    static boolean same(Object[] a, Object[] b) {
        return Arrays.deepEquals(a, b);
    }

    KeyRows copy() {
        KeyRows copy = new KeyRows();
        copy.rows.putAll(rows);
        return copy;
    }

    /** Adds weight to a row, which becomes the one the key shows. */
    void add(Object[] values, long weight) {
        Row row = new Row(values);
        Long had = rows.remove(row);
        rows.put(row, (had == null ? 0L : had) + weight);
    }

    /**
     * Takes {@code weight} away: from the row named first, and any remainder from the rows most
     * recently shown.
     *
     * <p>A lane only retracts a row it inserted, so the remainder is for a retraction whose values
     * differ from every row held -- the case the view has always absorbed by summing the key's
     * weight. Taking it from the shown rows keeps the rows' weights summing to the key's, which is
     * what the key's presence is decided by.
     */
    void retract(Object[] values, long weight) {
        long left = take(new Row(values), weight);
        if (left > 0) {
            List<Row> newestFirst = new ArrayList<>(rows.sequencedKeySet().reversed());
            for (Row row : newestFirst) {
                left = take(row, left);
                if (left == 0) {
                    break;
                }
            }
        }
    }

    private long take(Row row, long weight) {
        Long had = rows.get(row);
        if (had == null) {
            return weight;
        }
        if (had > weight) {
            rows.put(row, had - weight);
            return 0;
        }
        rows.remove(row);
        return weight - had;
    }

    /** How many distinct rows the key holds. */
    int size() {
        return rows.size();
    }

    /** The row the key shows: the present row that most recently gained weight. */
    Object[] shown() {
        return rows.lastEntry().getKey().values();
    }

    /** The only row left, when {@link #size()} is one, and its weight. */
    long weightOfShown() {
        return rows.lastEntry().getValue();
    }

    /** Each row and its weight, oldest gain first, so the last is the row shown. */
    void forEach(java.util.function.ObjLongConsumer<Object[]> action) {
        for (Map.Entry<Row, Long> entry : rows.entrySet()) {
            action.accept(entry.getKey().values(), entry.getValue());
        }
    }
}

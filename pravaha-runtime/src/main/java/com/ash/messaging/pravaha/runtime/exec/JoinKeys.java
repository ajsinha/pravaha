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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * Hashing and equality for join keys, across two schemas that agree only on the key's types.
 *
 * <p>Two rules make this work, and both are easy to get wrong in a way that looks fine in testing.
 *
 * <p>The first: the hash must depend on the key's <em>values</em> and nothing else. Not the
 * ordinals, which differ between the two sides -- {@code o.user_id} might be column 0 on one side
 * and column 3 on the other -- and not the row's header. A hash that picks up an ordinal makes the
 * two sides of the same key land in different buckets, and the join silently returns nothing.
 *
 * <p>The second: the hash is a filter, never the answer. Two different keys can share a hash, so a
 * candidate is confirmed by comparing the key columns value by value. Skipping that step is how a
 * join emits pairs that never matched, at a rate low enough to reach production.
 */
final class JoinKeys {

    private JoinKeys() {}

    /**
     * Refuses a key column whose type cannot be compared for equality without surprising somebody.
     *
     * <p>Floating point because {@code 0.1 + 0.2} does not equal {@code 0.3} and a join on it drops
     * rows for reasons no operator will ever diagnose; DECIMAL because Pravaha does not yet compare
     * its two-word form. Both are refused at plan time, where the query can be rewritten, rather
     * than at run time, where it cannot.
     */
    static void checkJoinable(StreamSchema schema, int ordinal, String side) {
        TypeName type = schema.field(ordinal).type().typeName();
        if (type == TypeName.FLOAT32 || type == TypeName.FLOAT64) {
            throw new PravahaException(
                    RuntimeErrors.UNSUPPORTED_JOIN,
                    "cannot join on '" + schema.field(ordinal).name() + "' (" + side + "): it is " + type
                            + ", and floating-point equality drops rows that differ only by rounding. "
                            + "Round or cast to an integer type, or join on a different column.");
        }
        if (type == TypeName.DECIMAL) {
            throw new PravahaException(
                    RuntimeErrors.UNSUPPORTED_JOIN,
                    "cannot join on '" + schema.field(ordinal).name() + "' (" + side
                            + "): DECIMAL keys are not supported yet");
        }
    }

    /** A value-only hash of the key columns, in the order given. */
    static long hash(RowView row, int[] ordinals, StreamSchema schema) {
        long hash = 0x9E3779B97F4A7C15L;
        for (int ordinal : ordinals) {
            hash = mix(
                    hash, fieldHash(row, ordinal, schema.field(ordinal).type().typeName()));
        }
        return hash;
    }

    private static long fieldHash(RowView row, int ordinal, TypeName type) {
        if (row.isNull(ordinal)) {
            // NULL never equals NULL in a join, so the value it hashes to does not matter -- but it
            // has to be stable, because an unstable one turns the index into a leak.
            return 0xD1B54A32D192ED03L;
        }
        return switch (type) {
            case BOOLEAN -> row.getBoolean(ordinal) ? 1 : 0;
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case STRING -> stringHash(row.getString(ordinal));
            default ->
                throw new PravahaException(RuntimeErrors.UNSUPPORTED_JOIN, "cannot hash a join key of type " + type);
        };
    }

    private static long stringHash(String value) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash = (hash ^ value.charAt(i)) * 0x100000001B3L;
        }
        return hash;
    }

    private static long mix(long accumulator, long value) {
        long h = accumulator ^ (value * 0xFF51AFD7ED558CCDL);
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 29);
    }

    /**
     * Whether two rows' key columns hold equal values.
     *
     * <p>NULL is not equal to NULL, which is what SQL says an equi-join means: a row whose key is
     * null joins with nothing, including other null-keyed rows. Getting this backwards produces a
     * cross product of everything unmatched, which is both wrong and the fastest way to run out of
     * memory.
     */
    static boolean equal(
            RowView left, int[] leftOrdinals, StreamSchema leftSchema, RowView right, int[] rightOrdinals) {
        for (int i = 0; i < leftOrdinals.length; i++) {
            int leftOrdinal = leftOrdinals[i];
            int rightOrdinal = rightOrdinals[i];
            if (left.isNull(leftOrdinal) || right.isNull(rightOrdinal)) {
                return false;
            }
            if (!sameValue(
                    left,
                    leftOrdinal,
                    right,
                    rightOrdinal,
                    leftSchema.field(leftOrdinal).type().typeName())) {
                return false;
            }
        }
        return true;
    }

    /** Whether a row's key is entirely non-null, and so can match anything at all. */
    static boolean isMatchable(RowView row, int[] ordinals) {
        for (int ordinal : ordinals) {
            if (row.isNull(ordinal)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameValue(RowView left, int leftOrdinal, RowView right, int rightOrdinal, TypeName type) {
        return switch (type) {
            case BOOLEAN -> left.getBoolean(leftOrdinal) == right.getBoolean(rightOrdinal);
            case INT8 -> left.getByte(leftOrdinal) == right.getByte(rightOrdinal);
            case INT16 -> left.getShort(leftOrdinal) == right.getShort(rightOrdinal);
            case INT32, DATE -> left.getInt(leftOrdinal) == right.getInt(rightOrdinal);
            case INT64, TIME, TIMESTAMP_LTZ -> left.getLong(leftOrdinal) == right.getLong(rightOrdinal);
            case STRING -> left.getString(leftOrdinal).equals(right.getString(rightOrdinal));
            default ->
                throw new PravahaException(RuntimeErrors.UNSUPPORTED_JOIN, "cannot compare a join key of type " + type);
        };
    }
}

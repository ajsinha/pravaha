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
package com.ash.messaging.pravaha.backfill;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * What the splice needs to know about a stream's rows to join history to the present.
 *
 * <p>Two things, and neither can be guessed. The <strong>key</strong> says which snapshot row and
 * which change describe the same thing; without it there is no way to tell an update from a new
 * row. The <strong>version</strong> says which of the two is newer -- the store's own generation,
 * LSN or last-update time, not the engine's clock, because only the store knows the order in which
 * it applied them.
 *
 * <p>A version column that does not actually increase with every write makes the splice wrong in
 * the one direction nobody notices: a change made during the snapshot is judged already included
 * and dropped. That is why it is named explicitly rather than defaulted to a timestamp column that
 * happens to be there.
 *
 * @param keyOrdinals the primary key, in the snapshot and change schemas alike
 * @param versionOrdinal a column that increases with every write to a row
 */
public record SpliceSpec(List<Integer> keyOrdinals, int versionOrdinal) {

    public SpliceSpec {
        keyOrdinals = List.copyOf(keyOrdinals);
        if (keyOrdinals.isEmpty()) {
            throw new IllegalArgumentException(
                    "a splice needs a key: without one there is no way to tell a change to an existing row from "
                            + "a new one, and history and the present cannot be joined at all");
        }
        if (versionOrdinal < 0) {
            throw new IllegalArgumentException(
                    "a splice needs a version column that increases with every write to a row; the store's "
                            + "generation, LSN or last-update time");
        }
    }

    /** Checks the ordinals against a schema, so a mismatch is a registration error. */
    public void validate(StreamSchema schema) {
        int width = schema.fields().size();
        for (int ordinal : keyOrdinals) {
            if (ordinal < 0 || ordinal >= width) {
                throw new IllegalArgumentException("key ordinal " + ordinal + " is outside '" + schema.name()
                        + "', which has " + width + " columns");
            }
        }
        if (versionOrdinal >= width) {
            throw new IllegalArgumentException("version ordinal " + versionOrdinal + " is outside '" + schema.name()
                    + "', which has " + width + " columns");
        }
        switch (schema.field(versionOrdinal).type().typeName()) {
            case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ -> {
                // Orderable as a long, which is what the splice compares.
            }
            default ->
                throw new IllegalArgumentException(
                        "version column '" + schema.field(versionOrdinal).name()
                                + "' is " + schema.field(versionOrdinal).type().typeName()
                                + "; a version must be orderable as an integer, because the splice decides which of two "
                                + "rows is newer by comparing them");
        }
    }
}

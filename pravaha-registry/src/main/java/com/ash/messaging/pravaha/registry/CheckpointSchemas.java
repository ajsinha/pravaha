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
package com.ash.messaging.pravaha.registry;

import java.nio.charset.StandardCharsets;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.state.StateErrors;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

/**
 * The output schema a checkpoint was taken under, recorded with it and compared at restore
 * (RETYPERESTORE-1).
 *
 * <p>A checkpoint is found by the query's name, and a name can come back over a stream whose column
 * changed type: {@code v BIGINT} checkpointed, the stream redeclared with {@code v VARCHAR}, and the
 * restore put the old {@code Long} into a view whose schema now said {@code VARCHAR}, beside new
 * {@code String}s. So the schema -- each column's name and SQL type, in order -- travels with the
 * view's snapshot, and a checkpoint of another schema is not restored: the query rebuilds from its
 * sources instead, which is the answer the new schema has. A column added to the stream that the
 * query does not select leaves the output schema, and so the restore, as it was.
 *
 * <p>A checkpoint written before this was recorded carries no schema and is restored as before.
 */
final class CheckpointSchemas {

    /** The checkpoint entry the schema travels under, beside the view's own. */
    static final String KEY = "output-schema";

    private CheckpointSchemas() {}

    /** {@code schema} as the checkpoint records it: {@code id INT64 NOT NULL, v VARCHAR}. */
    static byte[] of(StreamSchema schema) {
        return text(schema).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(StreamSchema schema) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < schema.fieldCount(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(schema.field(i).name())
                    .append(' ')
                    .append(schema.field(i).type().sqlName());
        }
        return out.toString();
    }

    /**
     * Refuses to restore {@code checkpoint} into a query whose output is now {@code current}, when the
     * checkpoint recorded another.
     *
     * @throws PravahaException {@code PRV-4095}, which the restore turns into a rebuild from the sources
     */
    static void requireSame(Checkpoint checkpoint, StreamSchema current, String query) {
        byte[] recorded = checkpoint.operatorState().get(KEY);
        if (recorded == null) {
            return;
        }
        String was = new String(recorded, StandardCharsets.UTF_8);
        String now = text(current);
        if (!was.equals(now)) {
            throw new PravahaException(
                    StateErrors.CHECKPOINT_SCHEMA_CHANGED,
                    "checkpoint " + checkpoint.id() + " of query '" + query + "' was taken when its output was ("
                            + was + ") and it is now (" + now + "). Restoring it would put values of the old types "
                            + "into the new columns, so it is not restored.");
        }
    }
}

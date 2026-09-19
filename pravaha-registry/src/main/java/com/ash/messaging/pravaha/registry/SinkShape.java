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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * The check that a sink will read a query's rows as what they are (SINK-1), before the sink is
 * opened: its declared schema against the query's output, and its key against the view's key.
 */
final class SinkShape {

    private SinkShape() {}

    /**
     * Refuses a sink that would read this query's rows as something else.
     *
     * <p>A sink reads each row through the schema it was configured with, and the engine hands it rows
     * laid out as the query produced them. A column added or two swapped means every value is read
     * from the wrong offset and written with the wrong name, and nothing fails -- the sink fills with
     * plausible nonsense. Types are compared by name and nullability is ignored, since a sink writes a
     * null wherever the query produces one.
     *
     * <p>And a keyed sink must key records by exactly the view's key. On fewer columns, distinct rows
     * collapse onto one record and retracting one deletes the other; on more, a changed row leaves its
     * old record behind. The engine emits a retraction and an insert per change, and both land on the
     * wrong record.
     */
    static void require(SinkFactory.Description sink, StreamSchema output, List<Integer> keyColumns, String sinkName) {
        sink.schema().ifPresent(declared -> {
            boolean same = declared.fieldCount() == output.fieldCount();
            for (int ordinal = 0; same && ordinal < output.fieldCount(); ordinal++) {
                same = declared.field(ordinal)
                                .name()
                                .equalsIgnoreCase(output.field(ordinal).name())
                        && declared.field(ordinal).type().typeName()
                                == output.field(ordinal).type().typeName();
            }
            if (!same) {
                throw new PravahaException(
                        RegistryErrors.SINK_SHAPE_MISMATCH,
                        "sink '" + sinkName + "' is configured for rows " + describe(declared)
                                + ", and this query produces " + describe(output)
                                + ". The sink reads each row through its own schema, so every column would be "
                                + "read from the wrong place and nothing would fail. Make the query's SELECT "
                                + "list match the sink's schema in order, name and type, or change the "
                                + "binding's schema.");
            }
        });
        if (!sink.keyColumns().isEmpty()) {
            java.util.Set<String> viewKey = new java.util.TreeSet<>();
            for (int ordinal : keyColumns) {
                viewKey.add(output.field(ordinal).name().toLowerCase(java.util.Locale.ROOT));
            }
            java.util.Set<String> sinkKey = new java.util.TreeSet<>();
            sink.keyColumns().forEach(column -> sinkKey.add(column.toLowerCase(java.util.Locale.ROOT)));
            if (!viewKey.equals(sinkKey)) {
                throw new PravahaException(
                        RegistryErrors.SINK_SHAPE_MISMATCH,
                        "sink '" + sinkName + "' keys its records by " + sinkKey
                                + ", and this query's view is keyed by "
                                + viewKey + ". A retraction deletes the sink record its key names: keyed on "
                                + "fewer columns than the view, distinct rows share a record and withdrawing "
                                + "one deletes the other; keyed on more, a changed row leaves its old record "
                                + "behind. Register with --keys naming " + sinkKey + ", or key the sink by "
                                + viewKey + ".");
            }
        }
    }

    private static String describe(StreamSchema schema) {
        List<String> columns = new ArrayList<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            columns.add(schema.field(ordinal).name() + ":"
                    + schema.field(ordinal).type().typeName());
        }
        return "(" + String.join(", ", columns) + ")";
    }
}

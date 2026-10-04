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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * One committed transaction's changes to the captured table, as the reader hands them over; with no
 * changes, a bare position marker that moves the reader to {@code file:endPosition}.
 *
 * @param file the binlog file {@code endPosition} is in
 * @param endPosition where the next transaction begins
 * @param alreadyDelivered changes at the front a restored checkpoint already holds, dropped on the way in
 * @param changes what remains to deliver, in the order the server applied them
 * @param commitNanos the commit's timestamp, the event time when {@code event.time} is not set
 * @param failure set when the transaction cannot be delivered at all; refused whole
 * @param gtid this transaction's GTID in GTID mode, or null
 * @param executedGtids the executed GTID set once this transaction is included, or null in file mode
 */
record BinlogTransaction(
        String file,
        long endPosition,
        int alreadyDelivered,
        List<Change> changes,
        long commitNanos,
        @Nullable PravahaException failure,
        @Nullable String gtid,
        @Nullable String executedGtids) {

    BinlogTransaction(
            String file,
            long endPosition,
            int alreadyDelivered,
            List<Change> changes,
            long commitNanos,
            @Nullable PravahaException failure) {
        this(file, endPosition, alreadyDelivered, changes, commitNanos, failure, null, null);
    }

    /**
     * One row with its weight.
     *
     * @param values one value per stream field, as {@link MySqlSchema#write} takes it
     * @param rejected why the row could not be converted, or null; offered to the dead-letter queue
     * @param raw the row as text, for the dead-letter queue
     */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    record Change(
            @Nullable Object[] values,
            long weight,
            @Nullable String rejected,
            byte[] raw) {

        /** A row from its binlog image, every column in table order. */
        static Change of(MySqlSchema.Mapping mapping, Serializable[] image, long weight) {
            StreamSchema schema = mapping.schema();
            Object[] values = new Object[schema.fieldCount()];
            StringBuilder raw = new StringBuilder();
            String rejected = null;
            for (int i = 0; i < values.length; i++) {
                Field field = schema.field(i);
                Serializable value = i < image.length ? image[i] : null;
                raw.append(i == 0 ? "" : "|")
                        .append(field.name())
                        .append('=')
                        .append(value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value);
                if (rejected != null) {
                    continue;
                }
                if (value == null) {
                    if (!field.type().nullable()) {
                        rejected = "column '" + field.name() + "' is NULL and the stream declares it NOT NULL";
                    }
                    continue;
                }
                try {
                    values[i] = MySqlSchema.convert(mapping.kinds()[i], mapping.charsets()[i], field.type(), value);
                } catch (RuntimeException e) {
                    rejected = "column '" + field.name() + "': '" + value + "' cannot be read as "
                            + field.type().sqlName() + " (" + e.getMessage() + ")";
                }
            }
            return new Change(values, weight, rejected, raw.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static BinlogTransaction marker(String file, long position) {
        return new BinlogTransaction(file, position, 0, List.of(), 0L, null);
    }

    /** A bare position marker in GTID mode ({@code executedGtids} non-null) or file mode. */
    static BinlogTransaction marker(String file, long position, @Nullable String executedGtids) {
        return new BinlogTransaction(file, position, 0, List.of(), 0L, null, null, executedGtids);
    }

    /** Where reading resumes once this transaction is delivered whole. */
    BinlogOffset offset() {
        return BinlogOffset.at(file, endPosition, executedGtids);
    }

    static BinlogTransaction refused(String file, long position, PravahaException failure) {
        return new BinlogTransaction(file, position, 0, List.of(), 0L, failure);
    }

    int size() {
        return changes.size();
    }
}

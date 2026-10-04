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
package com.ash.messaging.pravaha.plugin.feedfile;

import java.util.Locale;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * Schema declaration for a feed.
 *
 * <p>Declared, never sniffed. Inferring types from the first few rows of a drop file guesses wrong
 * on exactly the columns that matter: an account number of all digits becomes an integer, and the
 * first file containing a letter fails at 3 a.m. on a Sunday. Partners also change their files
 * without telling anyone, and a declared schema turns that into a decode error naming the line
 * rather than a silent change of meaning.
 */
final class FeedSchemas {

    private FeedSchemas() {}

    /** Parses {@code name:TYPE,name:TYPE}. A {@code ?} suffix marks a column nullable. */
    static StreamSchema parse(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : spec.split(",", -1)) {
            String[] parts = column.strip().split(":", -1);
            if (parts.length != 2) {
                throw new ConfigurationException(
                        FeedFileErrors.BAD_SCHEMA,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[1].strip()));
        }
        return builder.build();
    }

    private static PravahaType typeFor(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1);
        }
        PravahaType type =
                switch (upper) {
                    case "BOOLEAN", "BOOL" -> Types.bool();
                    case "INT8", "BYTE" -> Types.int8();
                    case "INT16", "SHORT" -> Types.int16();
                    case "INT32", "INT" -> Types.int32();
                    case "INT64", "LONG" -> Types.int64();
                    case "FLOAT32", "FLOAT" -> Types.float32();
                    case "FLOAT64", "DOUBLE" -> Types.float64();
                    case "STRING", "VARCHAR", "TEXT" -> Types.string();
                    case "BYTES", "BINARY" -> Types.bytes();
                    case "DATE" -> Types.date();
                    case "TIMESTAMP" -> Types.timestamp();
                    default ->
                        throw new ConfigurationException(
                                FeedFileErrors.BAD_SCHEMA,
                                "unknown type '" + name + "'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, "
                                        + "FLOAT32, FLOAT64, STRING, BYTES, DATE, TIMESTAMP. Suffix with ? for "
                                        + "nullable.");
                };
        return nullable ? type.withNullable(true) : type;
    }
}

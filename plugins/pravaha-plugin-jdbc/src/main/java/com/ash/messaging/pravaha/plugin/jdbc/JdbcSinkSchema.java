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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * The {@code name:TYPE} schema a JDBC sink is declared with.
 *
 * <p>Declared rather than read from the table, which is the opposite of the source's rule and for a
 * reason: the registry compares a sink's schema with the query's output <em>before</em> the sink is
 * opened (PRV-8010), so it must be answerable from configuration alone. The table is then checked
 * against the declaration when the sink opens, so the two cannot quietly disagree either.
 *
 * <p>The grammar is the one every other schema string in the engine uses -- {@code
 * FilesystemSourcePlugin.parseSchema}'s, including {@code DECIMAL(p,s)} and a trailing {@code ?} for
 * a nullable column -- repeated here because a plugin cannot depend on another plugin.
 */
final class JdbcSinkSchema {

    private static final Pattern DECIMAL = Pattern.compile("^DECIMAL\\((\\d+),\\s*(\\d+)\\)$");

    private JdbcSinkSchema() {}

    /** Parses {@code name:TYPE,name:TYPE}. */
    static StreamSchema parse(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : splitColumns(spec)) {
            String[] parts = column.strip().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new ConfigurationException(
                        JdbcErrors.BAD_CONFIGURATION,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[1].strip()));
        }
        return builder.build();
    }

    /** Splits on the commas between columns, not the one inside {@code DECIMAL(p,s)}. */
    private static List<String> splitColumns(String spec) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            columns.add(current.toString());
        }
        return columns;
    }

    private static PravahaType typeFor(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1).strip();
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
                    case "TIMESTAMP" -> Types.timestamp();
                    case "DATE" -> Types.date();
                    case "TIME" -> Types.time();
                    default -> decimalOrRefusal(name, upper);
                };
        return nullable ? type.withNullable(true) : type;
    }

    private static PravahaType decimalOrRefusal(String original, String upper) {
        Matcher decimal = DECIMAL.matcher(upper);
        if (decimal.matches()) {
            return Types.decimal(Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        throw new ConfigurationException(
                JdbcErrors.BAD_CONFIGURATION,
                "unknown type '" + original + "'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, "
                        + "FLOAT64, STRING, BYTES, DATE, TIME, TIMESTAMP, DECIMAL(p,s). Suffix with ? for nullable.");
    }
}

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
package com.ash.messaging.pravaha.plugin.filesystem;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * Encodes and decodes delimited lines.
 *
 * <p>Kept separate from the source and sink so both share one definition of what a line means. Two
 * implementations that disagree by one edge case -- a quoted delimiter, an empty trailing field --
 * produce a plugin that cannot read back what it wrote, which is the sort of bug that survives a
 * long time because each half looks correct alone.
 */
final class DelimitedCodec {

    static final ErrorCode DECODE_FAILED = new ErrorCode(5040, "FILESYSTEM_DECODE_FAILED");

    private final StreamSchema schema;
    private final char delimiter;
    private final String nullLiteral;

    DelimitedCodec(StreamSchema schema, char delimiter, String nullLiteral) {
        this.schema = schema;
        this.delimiter = delimiter;
        this.nullLiteral = nullLiteral;
    }

    /**
     * Splits a line, preserving empty fields.
     *
     * <p>{@code String.split} drops trailing empties, which silently turns a row with a null last
     * column into a short row. Doing it by hand is the difference between "works on my sample file"
     * and works.
     */
    static List<String> split(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == delimiter) {
                fields.add(line.substring(start, i));
                start = i + 1;
            }
        }
        fields.add(line.substring(start));
        return fields;
    }

    /** Decodes one line into {@code writer}. Throws with the line's content on a malformed field. */
    void decode(String line, long lineNumber, RowWriter writer) {
        List<String> fields = split(line, delimiter);
        if (fields.size() != schema.fieldCount()) {
            throw new ConfigurationException(
                    DECODE_FAILED,
                    "line " + lineNumber + " has " + fields.size() + " fields but schema '" + schema.name() + "' has "
                            + schema.fieldCount() + ": " + line);
        }
        for (int i = 0; i < fields.size(); i++) {
            String raw = fields.get(i);
            if (raw.equals(nullLiteral)) {
                if (!schema.field(i).type().nullable()) {
                    throw new ConfigurationException(
                            DECODE_FAILED,
                            "line " + lineNumber + " has null in NOT NULL column '"
                                    + schema.field(i).name() + "'");
                }
                writer.setNull(i);
                continue;
            }
            setField(writer, i, raw, lineNumber);
        }
    }

    private void setField(RowWriter writer, int ordinal, String raw, long lineNumber) {
        TypeName type = schema.field(ordinal).type().typeName();
        try {
            switch (type) {
                case BOOLEAN -> writer.setBoolean(ordinal, Boolean.parseBoolean(raw));
                case INT8 -> writer.setByte(ordinal, Byte.parseByte(raw));
                case INT16 -> writer.setShort(ordinal, Short.parseShort(raw));
                case INT32, DATE -> writer.setInt(ordinal, Integer.parseInt(raw));
                case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, Long.parseLong(raw));
                case FLOAT32 -> writer.setFloat(ordinal, Float.parseFloat(raw));
                case FLOAT64 -> writer.setDouble(ordinal, Double.parseDouble(raw));
                case STRING -> writer.setString(ordinal, raw);
                case BYTES -> writer.setBytes(ordinal, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                default ->
                    throw new ConfigurationException(
                            DECODE_FAILED,
                            "the filesystem plugin cannot decode " + type + " (column '"
                                    + schema.field(ordinal).name() + "'); use a plugin with a structured format");
            }
        } catch (NumberFormatException e) {
            throw new ConfigurationException(
                    DECODE_FAILED,
                    "line " + lineNumber + ", column '" + schema.field(ordinal).name() + "' (" + type + "): '" + raw
                            + "' is not a number");
        }
    }

    /** Encodes a row as a delimited line. */
    String encode(RowView row) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < schema.fieldCount(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            if (row.isNull(i)) {
                sb.append(nullLiteral);
                continue;
            }
            switch (schema.field(i).type().typeName()) {
                case BOOLEAN -> sb.append(row.getBoolean(i));
                case INT8 -> sb.append(row.getByte(i));
                case INT16 -> sb.append(row.getShort(i));
                case INT32, DATE -> sb.append(row.getInt(i));
                case INT64, TIME, TIMESTAMP_LTZ -> sb.append(row.getLong(i));
                case FLOAT32 -> sb.append(row.getFloat(i));
                case FLOAT64 -> sb.append(row.getDouble(i));
                case STRING, BYTES -> sb.append(row.getString(i));
                default -> sb.append(nullLiteral);
            }
        }
        return sb.toString();
    }

    StreamSchema schema() {
        return schema;
    }

    char delimiter() {
        return delimiter;
    }
}

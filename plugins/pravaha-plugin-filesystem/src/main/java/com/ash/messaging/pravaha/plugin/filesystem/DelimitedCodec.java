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

    /**
     * A {@code name:TYPE,name:TYPE} schema string that will not parse.
     *
     * <p>Finding TY-8. This was {@link #DECODE_FAILED} -- where the parser happens to live rather
     * than what went wrong -- which put a caller's typo in the PLUGIN series. The HTTP API derives
     * its status from a code's category, so {@code POST /api/v1/streams} naming a type that does
     * not exist answered {@code 500 the server is broken} for a request the client could fix by
     * itself. A schema string is configuration on every surface that writes one
     * ({@code pravaha.streams.*.schema}, {@code --schema}, a plugin's {@code schema} option), so
     * it is a configuration code and a {@code 400}; 5040 stays what it has always been, a line of
     * data a file could not decode.
     *
     * <p>In the 1xxx configuration range but declared here rather than in {@code ConfigErrors},
     * because a plugin depends on {@code pravaha-api} and nothing else. The number is reserved
     * there in a comment so it cannot be handed out twice.
     */
    static final ErrorCode SCHEMA_MALFORMED = new ErrorCode(1028, "CONFIG_SCHEMA_MALFORMED");

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
    /**
     * The event time of the row decoded by the last {@link #decode}, or {@code Long.MIN_VALUE}.
     *
     * <p>Read straight after a decode, on the reader's own thread, which is the only thread that
     * calls either. A returned value would be cleaner and would change a signature that several
     * callers share; this is confined to the one call site that needs it.
     */
    long lastEventTimeNanos() {
        return lastEventTimeNanos;
    }

    private long lastEventTimeNanos = Long.MIN_VALUE;
    private String lastOpValue;
    private int opOrdinal = -1;

    /** Names the column whose value says whether a row is an insertion or a retraction. */
    void markOperationColumn(String columnName) {
        this.opOrdinal = columnName == null || columnName.isBlank() ? -1 : schema.indexOf(columnName);
    }

    /** The operation column's value for the row just decoded, or null when there is no such column. */
    String lastOperation() {
        return lastOpValue;
    }

    void decode(String line, long lineNumber, RowWriter writer) {
        lastEventTimeNanos = Long.MIN_VALUE;
        lastOpValue = null;
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

    /**
     * A DATE, TIME or TIMESTAMP field, in the units the engine holds it in.
     *
     * <p>A bare number is taken as those units already -- days for a DATE, nanoseconds for the other
     * two -- which is what this codec has always done and what its own writer emits, so a file it
     * wrote reads back identically.
     *
     * <p>Text is the addition, and it is why this method exists. A TIME column read a bare number
     * and stored it unscaled, so the only spelling that worked was nanoseconds-of-day; a person
     * writing the natural thing, 3600000 for an hour, got 3.6 milliseconds. Every
     * {@code WHERE <time column> < TIME '00:00:01'} over that data was then silently wrong -- not
     * refused, not an error, a wrong row set under a success status -- and there was no spelling to
     * work around it with, because comparing the column to a bare integer is separately refused.
     */
    private static final java.util.regex.Pattern NUMERIC = java.util.regex.Pattern.compile("[+-]?\\d+");

    private static long temporal(TypeName type, String raw) {
        String text = raw.strip();
        // A sign only at the front. Allowing '-' anywhere made 2026-09-14 look like a number, and
        // the parse then failed reporting that a date is not a number -- true, and useless.
        if (NUMERIC.matcher(text).matches()) {
            return Long.parseLong(text);
        }
        return switch (type) {
            case DATE -> java.time.LocalDate.parse(text).toEpochDay();
            case TIME -> java.time.LocalTime.parse(text).toNanoOfDay();
            default -> {
                // With a zone or without: an offset-bearing stamp keeps its instant, a bare one is
                // read as UTC rather than as the machine's zone, so the same file means the same
                // thing wherever it is read.
                java.time.Instant instant = text.endsWith("Z") || text.contains("+") || text.lastIndexOf('-') > 7
                        ? java.time.OffsetDateTime.parse(text).toInstant()
                        : java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
                // Checked (FARTIME-1): nanoseconds since the epoch in 64 bits end at 2262-04-11, and
                // an unchecked product wrapped 3000-01-01 into 1677. setField turns the overflow
                // into a decode failure naming the line, the column and the range.
                yield Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
            }
        };
    }

    private void setField(RowWriter writer, int ordinal, String raw, long lineNumber) {
        TypeName type = schema.field(ordinal).type().typeName();
        try {
            switch (type) {
                case BOOLEAN -> writer.setBoolean(ordinal, Boolean.parseBoolean(raw));
                case INT8 -> writer.setByte(ordinal, Byte.parseByte(raw));
                case INT16 -> writer.setShort(ordinal, Short.parseShort(raw));
                case INT32 -> writer.setInt(ordinal, Integer.parseInt(raw));
                case DATE -> writer.setInt(ordinal, Math.toIntExact(temporal(type, raw)));
                case INT64 -> {
                    long value = Long.parseLong(raw);
                    writer.setLong(ordinal, value);
                    // Remembered so the reader can stamp the row with it. Nothing did, so every row
                    // a file produced carried event time zero -- and a watermark derived from zero
                    // never reaches a window in the present, so windowed queries ingested every row
                    // and emitted nothing, for ever, while reporting RUNNING.
                    if (schema.eventTimeOrdinal().orElse(-1) == ordinal) {
                        lastEventTimeNanos = value;
                    }
                }
                case TIME, TIMESTAMP_LTZ -> {
                    long value = temporal(type, raw);
                    writer.setLong(ordinal, value);
                    // Remembered so the reader can stamp the row with it. Nothing did, so every row
                    // a file produced carried event time zero -- and a watermark derived from zero
                    // never reaches a window in the present, so windowed queries ingested every row
                    // and emitted nothing, for ever, while reporting RUNNING.
                    if (schema.eventTimeOrdinal().orElse(-1) == ordinal) {
                        lastEventTimeNanos = value;
                    }
                }
                case FLOAT32 -> writer.setFloat(ordinal, Float.parseFloat(raw));
                case FLOAT64 -> writer.setDouble(ordinal, Double.parseDouble(raw));
                case STRING -> {
                    writer.setString(ordinal, raw);
                    if (ordinal == opOrdinal) {
                        lastOpValue = raw;
                    }
                }
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
        } catch (ArithmeticException e) {
            throw new ConfigurationException(
                    DECODE_FAILED,
                    "line " + lineNumber + ", column '" + schema.field(ordinal).name() + "' (" + type + "): '" + raw
                            + "' is outside the range this engine holds a " + type + " in ("
                            + (type == TypeName.DATE
                                    ? "days since 1970 in 32 bits"
                                    : "nanoseconds since 1970 in 64 bits: 1677-09-21 to 2262-04-11 UTC")
                            + "); the line is not read rather than stored as a different time");
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

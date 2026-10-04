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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * Delimited text, with the quoting rules real files actually have.
 *
 * <p>A split on commas is the version everybody writes first and every partner file eventually
 * breaks: an address field contains a comma, a description contains a quoted comma, and a quoted
 * field contains a doubled quote. Those three are handled here. Embedded newlines inside a quoted
 * field are <em>not</em>, and that limitation is deliberate and declared -- supporting them means a
 * record can span lines, which breaks the line-oriented resume that makes this source replayable.
 * A feed that needs them should be Parquet.
 */
final class CsvDecoder implements FeedRecordDecoder {

    private final char delimiter;
    private final String nullLiteral;
    private final boolean skipHeader;

    private @Nullable BufferedReader reader;

    @SuppressWarnings("NullAway.Init") // set by open(), which comes before any read
    private StreamSchema schema;

    @SuppressWarnings("NullAway.Init") // set by open(), which comes before any read
    private Path file;

    private long lineNumber;
    private @Nullable String current;
    private int eventTimeOrdinal = -1;
    private long lastEventTimeNanos = Long.MIN_VALUE;

    CsvDecoder(char delimiter, String nullLiteral, boolean skipHeader) {
        this.delimiter = delimiter;
        this.nullLiteral = nullLiteral;
        this.skipHeader = skipHeader;
    }

    @Override
    public void open(Path path, StreamSchema streamSchema) {
        close();
        this.file = path;
        this.schema = streamSchema;
        this.eventTimeOrdinal = streamSchema.eventTimeOrdinal().orElse(-1);
        this.lineNumber = 0;
        try {
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            if (skipHeader) {
                reader.readLine();
                lineNumber++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open " + path, e);
        }
    }

    @Override
    public boolean advance() {
        current = readLine();
        return current != null;
    }

    @Override
    public void write(RowWriter writer) {
        String line = Objects.requireNonNull(current, "write() follows an advance() that returned true");
        List<String> fields = split(line);
        if (fields.size() != schema.fieldCount()) {
            throw new PravahaException(
                    FeedFileErrors.DECODE_FAILED,
                    file.getFileName() + " line " + lineNumber + " has " + fields.size() + " fields, but the "
                            + "declared schema has " + schema.fieldCount() + ": " + line);
        }
        lastEventTimeNanos = Long.MIN_VALUE;
        for (int i = 0; i < fields.size(); i++) {
            setField(writer, i, fields.get(i));
        }
    }

    @Override
    public long lastEventTimeNanos() {
        return lastEventTimeNanos;
    }

    private @Nullable String readLine() {
        try {
            String line = Objects.requireNonNull(reader, "open() comes before any read")
                    .readLine();
            if (line != null) {
                lineNumber++;
            }
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    @Override
    public void skip(long count) {
        for (long i = 0; i < count; i++) {
            if (readLine() == null) {
                return;
            }
        }
    }

    /** Splits one line, honouring quotes and doubled quotes inside them. */
    private List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"' && current.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private void setField(RowWriter writer, int ordinal, String raw) {
        String value = raw.strip();
        if (value.equals(nullLiteral)) {
            if (!schema.field(ordinal).type().nullable()) {
                throw new PravahaException(
                        FeedFileErrors.DECODE_FAILED,
                        file.getFileName() + " line " + lineNumber + " has an empty value for column '"
                                + schema.field(ordinal).name() + "', which the schema declares NOT NULL. Mark it "
                                + "nullable with a ? suffix, or fix the feed.");
            }
            writer.setNull(ordinal);
            return;
        }
        TypeName type = schema.field(ordinal).type().typeName();
        try {
            switch (type) {
                case BOOLEAN -> writer.setBoolean(ordinal, Boolean.parseBoolean(value));
                case INT8 -> writer.setByte(ordinal, Byte.parseByte(value));
                case INT16 -> writer.setShort(ordinal, Short.parseShort(value));
                case INT32, DATE -> writer.setInt(ordinal, Integer.parseInt(value));
                case INT64, TIMESTAMP_LTZ, TIME -> {
                    long parsed = Long.parseLong(value);
                    writer.setLong(ordinal, parsed);
                    if (ordinal == eventTimeOrdinal) {
                        lastEventTimeNanos = parsed;
                    }
                }
                case FLOAT32 -> writer.setFloat(ordinal, Float.parseFloat(value));
                case FLOAT64 -> writer.setDouble(ordinal, Double.parseDouble(value));
                default -> writer.setString(ordinal, value);
            }
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    FeedFileErrors.DECODE_FAILED,
                    file.getFileName() + " line " + lineNumber + ": '" + value + "' is not a valid " + type
                            + " for column '" + schema.field(ordinal).name() + "'",
                    e);
        }
    }

    @Override
    public void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot close " + file, e);
            } finally {
                reader = null;
            }
        }
    }
}

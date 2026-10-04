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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * Parquet drop files.
 *
 * <p>The format most exports actually land in, and the one a CSV feed graduates to when it gets
 * large: columnar, compressed, typed, and roughly a fifth of the bytes. Reading it record-at-a-time
 * through the group API gives up Parquet's columnar advantage, which is the right trade here --
 * this is a <em>feed reader</em> whose output is rows for a row-oriented engine, and a vectorised
 * path would be wasted converting batches back into records. When the engine grows a vectorised
 * ingest path this decoder should be revisited; until then, simplicity wins and the cost is stated.
 *
 * <p>Values are read by column <em>name</em> rather than by position, so a file whose columns are in
 * a different order than the declared schema still decodes correctly. Partners reorder columns
 * between releases and almost never mention it.
 */
final class ParquetDecoder implements FeedRecordDecoder {

    private final Configuration configuration = new Configuration();

    private @Nullable ParquetReader<Group> reader;

    @SuppressWarnings("NullAway.Init") // set by open(), which comes before any read
    private StreamSchema schema;

    @SuppressWarnings("NullAway.Init") // set by open(), which comes before any read
    private Path file;

    private long recordNumber;
    private @Nullable Group current;
    private int eventTimeOrdinal = -1;
    private long lastEventTimeNanos = Long.MIN_VALUE;

    @Override
    public void open(Path path, StreamSchema streamSchema) {
        close();
        this.file = path;
        this.schema = streamSchema;
        this.eventTimeOrdinal = streamSchema.eventTimeOrdinal().orElse(-1);
        this.recordNumber = 0;
        try {
            this.reader = ParquetReader.builder(new GroupReadSupport(), new org.apache.hadoop.fs.Path(path.toUri()))
                    .withConf(configuration)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open Parquet file " + path, e);
        }
    }

    @Override
    public boolean advance() {
        current = read();
        return current != null;
    }

    @Override
    public void write(RowWriter writer) {
        Group group = Objects.requireNonNull(current, "write() follows an advance() that returned true");
        lastEventTimeNanos = Long.MIN_VALUE;
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String name = schema.field(ordinal).name();
            if (group.getFieldRepetitionCount(name) == 0) {
                if (!schema.field(ordinal).type().nullable()) {
                    throw new PravahaException(
                            FeedFileErrors.DECODE_FAILED,
                            file.getFileName() + " record " + recordNumber + " has no value for column '" + name
                                    + "', which the schema declares NOT NULL");
                }
                writer.setNull(ordinal);
                continue;
            }
            setField(writer, ordinal, group, name);
        }
    }

    @Override
    public long lastEventTimeNanos() {
        return lastEventTimeNanos;
    }

    private void setField(RowWriter writer, int ordinal, Group group, String name) {
        TypeName type = schema.field(ordinal).type().typeName();
        try {
            switch (type) {
                case BOOLEAN -> writer.setBoolean(ordinal, group.getBoolean(name, 0));
                case INT8 -> writer.setByte(ordinal, (byte) group.getInteger(name, 0));
                case INT16 -> writer.setShort(ordinal, (short) group.getInteger(name, 0));
                case INT32, DATE -> writer.setInt(ordinal, group.getInteger(name, 0));
                case INT64, TIMESTAMP_LTZ, TIME -> {
                    long value = group.getLong(name, 0);
                    writer.setLong(ordinal, value);
                    if (ordinal == eventTimeOrdinal) {
                        lastEventTimeNanos = value;
                    }
                }
                case FLOAT32 -> writer.setFloat(ordinal, group.getFloat(name, 0));
                case FLOAT64 -> writer.setDouble(ordinal, group.getDouble(name, 0));
                case BYTES -> writer.setBytes(ordinal, group.getBinary(name, 0).getBytes());
                default -> writer.setString(ordinal, group.getString(name, 0));
            }
        } catch (RuntimeException e) {
            throw new PravahaException(
                    FeedFileErrors.DECODE_FAILED,
                    file.getFileName() + " record " + recordNumber + ": column '" + name + "' does not read as " + type
                            + ". The file's Parquet type and the declared schema disagree.",
                    e);
        }
    }

    private @Nullable Group read() {
        try {
            Group group = Objects.requireNonNull(reader, "open() comes before any read")
                    .read();
            if (group != null) {
                recordNumber++;
            }
            return group;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    @Override
    public void skip(long count) {
        // Record-at-a-time, because ParquetReader has no seek. A resume into the middle of a large
        // file therefore costs a scan of its head. Row-group-aligned offsets would fix it and are
        // the obvious follow-up; pretending the cost is not there would not be.
        for (long i = 0; i < count; i++) {
            if (read() == null) {
                return;
            }
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

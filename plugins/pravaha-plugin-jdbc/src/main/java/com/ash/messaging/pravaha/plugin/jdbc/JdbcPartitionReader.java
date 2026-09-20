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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Polls a table for rows above a high-water mark.
 *
 * <p>The universal fallback: every database has a JDBC driver, most do not have a change feed that
 * is available, affordable or permitted, and this works on all of them. What it costs is stated
 * plainly in {@link JdbcSourcePlugin#capabilities()} rather than implied away -- polling sees
 * inserts and, if the watermark column is a modification timestamp, updates. It <strong>cannot see
 * a delete</strong>, because a deleted row is simply not in the next result set, and nothing
 * distinguishes that from a row that was never there.
 *
 * <p>The query is bounded and ordered, so a poll is a bounded amount of work against an indexed
 * column rather than a table scan somebody notices in production. With a key column configured it
 * is keyset pagination -- {@code WHERE w > ? OR (w = ? AND k > ?) ORDER BY w, k} -- which resumes
 * exactly because the sort is total. Without one it falls back to counting rows at the boundary
 * value, which is right only if the database returns tied rows in a stable order; it does not
 * promise that and does not do it, so that configuration declares itself non-replayable. See
 * {@link JdbcOffset} for how that was found.
 */
final class JdbcPartitionReader implements PartitionReader {

    private final Connection connection;
    private final String firstQuery;
    private final String resumeQuery;

    /** Values for the pushed filters' markers, empty when nothing was pushed. */
    private final java.util.List<Object> pushedValues;

    /**
     * For each column of the schema, its one-based position in the SELECT list, or 0 when a pushed
     * projection left it out and it is written with {@link RowWriter#setUnread}.
     */
    private final int[] resultIndex;

    private final StreamSchema schema;
    private final String watermarkColumn;
    private final String keyColumn;
    private final int fetchSize;

    private JdbcOffset offset;
    private boolean paused;
    private long sequence;

    /**
     * Nanoseconds per unit of the watermark column, or {@code 0} for "this is a cursor, not a
     * time" (T-5). Set from {@code watermark.unit}; see the emission site below.
     */
    private long watermarkUnitNanos;

    /** See {@link #watermarkUnitNanos}. */
    JdbcPartitionReader readingWatermarkAs(long nanosPerUnit) {
        this.watermarkUnitNanos = nanosPerUnit;
        return this;
    }

    /**
     * The watermark value as nanoseconds since the epoch, or zero when it is only a cursor.
     *
     * <p>An overflow here is the wrong unit declared, not a row from the year 2262: a column of
     * epoch <em>milliseconds</em> read as {@code seconds} multiplies by a billion and leaves the
     * range of a long. Refused by name, because the alternative is a wrapped negative event time
     * that puts the watermark before the epoch and closes every window at once.
     */
    private long eventTimeNanos(long watermark) {
        if (watermarkUnitNanos == 0L) {
            return 0L;
        }
        try {
            return Math.multiplyExact(watermark, watermarkUnitNanos);
        } catch (ArithmeticException overflow) {
            throw new PravahaException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "the watermark column '" + watermarkColumn + "' holds " + watermark
                            + ", which is past the year 2262 in the unit watermark.unit declares ("
                            + watermarkUnitNanos + " nanoseconds each) and does not fit an event time. "
                            + "This is almost always the wrong unit: a column of epoch milliseconds read "
                            + "as seconds overflows exactly like this.");
        }
    }

    JdbcPartitionReader(
            Connection connection,
            String firstQuery,
            String resumeQuery,
            StreamSchema schema,
            String watermarkColumn,
            String keyColumn,
            int fetchSize,
            SourceOffset resumeFrom) {
        this(
                connection,
                firstQuery,
                resumeQuery,
                schema,
                watermarkColumn,
                keyColumn,
                fetchSize,
                resumeFrom,
                java.util.List.of(),
                null);
    }

    JdbcPartitionReader(
            Connection connection,
            String firstQuery,
            String resumeQuery,
            StreamSchema schema,
            String watermarkColumn,
            String keyColumn,
            int fetchSize,
            SourceOffset resumeFrom,
            java.util.List<Object> pushedValues,
            List<String> selected) {
        this.pushedValues = java.util.List.copyOf(pushedValues);
        this.resultIndex = resultIndexes(schema, selected);
        this.connection = connection;
        this.firstQuery = firstQuery;
        this.resumeQuery = resumeQuery;
        this.schema = schema;
        this.watermarkColumn = watermarkColumn;
        this.keyColumn = keyColumn;
        this.fetchSize = fetchSize;
        this.offset = JdbcOffset.parse(resumeFrom);
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        int limit = Math.min(maxRecords, fetchSize);
        boolean fromStart = offset.isBeginning();
        String sql = fromStart ? firstQuery : resumeQuery;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (!fromStart) {
                statement.setLong(parameter++, offset.watermark());
                if (keyed()) {
                    statement.setLong(parameter++, offset.watermark());
                    statement.setLong(parameter++, offset.key());
                }
            }
            // Pushed filter values, in the order the clause was built and after the resume
            // parameters, because that is the order the markers appear in. setObject rather than a
            // typed setter: the value came from a literal in the query and the driver knows its own
            // mapping better than a switch here would.
            for (Object value : pushedValues) {
                statement.setObject(parameter++, value);
            }
            // Keyless mode re-selects the boundary rows it has already emitted, so it must ask for
            // enough to still return `limit` new ones after skipping them.
            int request = keyed() ? limit : limit + (int) Math.min(offset.emittedAtWatermark(), fetchSize);
            statement.setInt(parameter, request);
            statement.setFetchSize(limit);
            try (ResultSet results = statement.executeQuery()) {
                return emit(results, sink, limit);
            }
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED, "polling failed: " + e.getMessage() + "\n  query: " + sql, e);
        }
    }

    private boolean keyed() {
        return !keyColumn.isBlank();
    }

    /** One-based result-set index of a column, matched the way the database named it. */
    private int indexOf(String column) {
        List<String> names = schema.fields().stream()
                .map(com.ash.messaging.pravaha.api.data.Field::name)
                .toList();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(column) && resultIndex[i] > 0) {
                return resultIndex[i];
            }
        }
        throw new PravahaException(JdbcErrors.QUERY_FAILED, "column '" + column + "' is not in the result: " + names);
    }

    /** See {@link #resultIndex}; {@code selected} null means {@code SELECT *}, in schema order. */
    private static int[] resultIndexes(StreamSchema schema, List<String> selected) {
        int[] indexes = new int[schema.fieldCount()];
        for (int ordinal = 0; ordinal < indexes.length; ordinal++) {
            indexes[ordinal] = selected == null
                    ? ordinal + 1
                    : selected.indexOf(schema.field(ordinal).name()) + 1;
        }
        return indexes;
    }

    private int emit(ResultSet results, RecordSink sink, int limit) throws SQLException {
        List<TypeName> types =
                schema.fields().stream().map(f -> f.type().typeName()).toList();
        int watermarkIndex = indexOf(watermarkColumn);

        int keyIndex = keyed() ? indexOf(keyColumn) : 0;
        long skipAtWatermark = keyed() ? 0 : offset.emittedAtWatermark();
        int emitted = 0;
        while (emitted < limit && results.next()) {
            long watermark = results.getLong(watermarkIndex);
            if (watermark == offset.watermark() && skipAtWatermark > 0) {
                // Keyless mode only: the boundary rows the >= comparison deliberately re-selects.
                skipAtWatermark--;
                continue;
            }
            long key = keyed() ? results.getLong(keyIndex) : 0L;
            RowWriter writer = sink.beginRow();
            for (int ordinal = 0; ordinal < types.size(); ordinal++) {
                if (resultIndex[ordinal] == 0) {
                    // Not selected: the engine said nothing above the scan reads it.
                    writer.setUnread(ordinal);
                } else {
                    JdbcTypes.copyValue(results, resultIndex[ordinal], writer, ordinal, types.get(ordinal));
                }
            }
            writer.weight(1L)
                    // T-5. This used to be `.eventTimestampNanos(watermark)` -- the watermark
                    // column's value, raw. That column is a monotone cursor and need not be a
                    // time at all; where it is one, it is whatever unit the table keeps, and the
                    // engine counts nanoseconds (ADR-012). So an `updated_at BIGINT` holding epoch
                    // MILLISECONDS produced an event time out by a factor of a million -- a
                    // watermark stuck in 1970, windows that never close, and nothing said.
                    //
                    // `watermark.unit` is how a deployment says which it is, and it defaults to
                    // `none`: a cursor, carrying no event time. Guessing was the defect.
                    .eventTimestampNanos(eventTimeNanos(watermark))
                    .sequence(sequence++)
                    .commit();
            offset = offset.advanced(watermark, key);
            emitted++;
        }
        return emitted;
    }

    @Override
    public SourceOffset position() {
        return offset.toSourceOffset();
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public void close() {
        // The connection belongs to the plugin, which may hand it to another reader; closing it here
        // would break the next one for no benefit.
    }
}

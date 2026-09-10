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

    private final StreamSchema schema;
    private final String watermarkColumn;
    private final String keyColumn;
    private final int fetchSize;

    private JdbcOffset offset;
    private boolean paused;
    private long sequence;

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
                java.util.List.of());
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
            java.util.List<Object> pushedValues) {
        this.pushedValues = java.util.List.copyOf(pushedValues);
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
            if (names.get(i).equalsIgnoreCase(column)) {
                return i + 1;
            }
        }
        throw new PravahaException(JdbcErrors.QUERY_FAILED, "column '" + column + "' is not in the result: " + names);
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
                JdbcTypes.copyValue(results, ordinal + 1, writer, ordinal, types.get(ordinal));
            }
            writer.weight(1L)
                    // The watermark column is the closest thing this source has to an event time,
                    // and it is what the query already orders by, so using it is a statement of
                    // fact rather than a guess.
                    .eventTimestampNanos(watermark)
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

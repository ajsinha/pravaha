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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Polls a table the way {@link JdbcPartitionReader} does, but hands the engine each page as
 * pre-combined {@code COUNT}/{@code SUM} partials instead of as rows (ADR-039 item 6).
 *
 * <p><strong>A polled source's partial covers new rows only, and that is exactly right.</strong> The
 * row reader already delivers a table as a sequence of pages -- keyset pages ordered by {@code
 * (watermark, key)}, each starting where the last one ended -- and the engine's aggregate adds each
 * row it is given. A partial for a page is the same sum taken by the database instead: {@code SELECT
 * g, COUNT(*), SUM(x) ... WHERE (w, k) is after the offset AND (w, k) is at or before the page's
 * last row AND every pushed filter GROUP BY g}. Summed over pages it is the same total, with the
 * same offsets, and a restore resumes from the same place.
 *
 * <p>Each poll is two statements. The first finds where a page of at most {@code min(fetch.size,
 * maxRecords)} rows ends, reading only the watermark and key; the second aggregates everything in
 * {@code (offset, end]}. Bounding the page by the rows the lane can take bounds the groups too, so
 * every partial of a page is written in the poll that computed it and the offset moves with it --
 * no partial is ever emitted from a page whose offset a checkpoint could still record as unread. A
 * row inserted between the two statements with a watermark inside the page is summed once, in this
 * page, which is exactly what a row poll a moment later would have done with a longer page.
 *
 * <p><strong>Retractions: none, and none are claimed.</strong> A poll cannot see a delete, and an
 * update is seen as the row arriving again with its new values (when the watermark is a modification
 * time) -- never as a {@code -1} for the old one. The row reader has exactly that behaviour, so each
 * partial is written with weight {@code +1} and the pushed answer equals the unpushed one on the
 * same data, updates included; neither is a faithful changelog, which is what {@link
 * JdbcSourcePlugin#capabilities()} already says.
 *
 * <p>Only built by {@link JdbcSourcePlugin} when every filter in the request made it into the SQL
 * ({@link JdbcPushdown#exact()}), since the engine cannot re-apply a filter to rows it never sees.
 */
final class JdbcPartialAggregateReader implements PartitionReader {

    /** The hidden row count every partial query selects, so a group of none can be skipped. */
    private static final String ROWS = "pv_rows__";

    private final Connection connection;
    private final String source;
    private final String watermarkColumn;
    private final String keyColumn;
    private final String pageClause;
    private final int fetchSize;
    private final JdbcPushdown pushed;
    private final ReadRequest.PartialAggregate partial;

    private final ArrayDeque<Object[]> pending = new ArrayDeque<>();

    private JdbcOffset offset;
    private @Nullable JdbcOffset pageEnd;
    private boolean paused;
    private long sequence;
    private long pages;
    private long rowsSummarised;

    JdbcPartialAggregateReader(
            Connection connection,
            String source,
            String watermarkColumn,
            String keyColumn,
            String pageClause,
            int fetchSize,
            JdbcPushdown pushed,
            ReadRequest.PartialAggregate partial,
            @Nullable SourceOffset resumeFrom) {
        this.connection = connection;
        this.source = source;
        this.watermarkColumn = watermarkColumn;
        this.keyColumn = keyColumn;
        this.pageClause = pageClause;
        this.fetchSize = fetchSize;
        this.pushed = pushed;
        this.partial = partial;
        this.offset = JdbcOffset.parse(resumeFrom);
    }

    @Override
    public boolean deliversPartialAggregate() {
        return true;
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused || maxRecords <= 0) {
            return 0;
        }
        if (pending.isEmpty()) {
            try {
                JdbcOffset end = pageEnd(Math.min(fetchSize, maxRecords));
                if (end == null) {
                    return 0;
                }
                aggregate(end);
                pageEnd = end;
                pages++;
            } catch (SQLException e) {
                throw new PravahaException(
                        JdbcErrors.QUERY_FAILED, "polling a partial aggregate failed: " + e.getMessage(), e);
            }
        }
        int emitted = 0;
        while (emitted < maxRecords && !pending.isEmpty()) {
            write(sink, pending.poll());
            emitted++;
        }
        if (pending.isEmpty() && pageEnd != null) {
            // Only once every partial of the page is out: until then a checkpoint must still see the
            // page as unread. Normally this is the same poll -- see the class javadoc.
            offset = pageEnd;
            pageEnd = null;
        }
        return emitted;
    }

    /** Where a page of at most {@code limit} rows after the offset ends, or null when none are new. */
    private @Nullable JdbcOffset pageEnd(int limit) throws SQLException {
        String sql = "SELECT " + watermarkColumn + ", " + keyColumn + " FROM " + source + where(afterOffset(), null)
                + " ORDER BY " + watermarkColumn + ", " + keyColumn + " " + pageClause;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = bindAfterOffset(statement, 1);
            for (Object value : pushed.values()) {
                statement.setObject(parameter++, value);
            }
            statement.setInt(parameter, limit);
            statement.setFetchSize(limit);
            JdbcOffset end = null;
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    end = new JdbcOffset(results.getLong(1), results.getLong(2), 0L);
                }
            }
            return end;
        }
    }

    /** Runs the partial query over {@code (offset, end]} and queues one partial per group. */
    private void aggregate(JdbcOffset end) throws SQLException {
        List<String> groups = partial.groupByColumns();
        List<String> select = new ArrayList<>(groups);
        select.add("COUNT(*) AS " + ROWS);
        select.add("MAX(" + watermarkColumn + ")");
        for (ReadRequest.PartialAggregate.AggregateCall call : partial.aggregates()) {
            select.add(
                    switch (call.kind()) {
                        case COUNT -> call.column() == null ? "COUNT(*)" : "COUNT(" + call.column() + ")";
                        // The engine's own SUM starts at zero and ignores nulls, so a group whose
                        // values are all null sums to 0 there -- not to SQL's NULL.
                        case SUM -> "COALESCE(SUM(" + call.column() + "), 0)";
                    });
        }
        String upTo = "(" + watermarkColumn + " < ? OR (" + watermarkColumn + " = ? AND " + keyColumn + " <= ?))";
        String sql = "SELECT " + String.join(", ", select) + " FROM " + source + where(afterOffset(), upTo)
                + (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = bindAfterOffset(statement, 1);
            statement.setLong(parameter++, end.watermark());
            statement.setLong(parameter++, end.watermark());
            statement.setLong(parameter++, end.key());
            for (Object value : pushed.values()) {
                statement.setObject(parameter++, value);
            }
            try (ResultSet results = statement.executeQuery()) {
                int columns = groups.size() + 2 + partial.aggregates().size();
                while (results.next()) {
                    long rows = results.getLong(groups.size() + 1);
                    if (rows == 0) {
                        // COUNT(*) with no GROUP BY answers one row even over nothing. A partial of
                        // zero would still tell the aggregate "something arrived", which the rows
                        // path never says for an empty page.
                        continue;
                    }
                    rowsSummarised += rows;
                    Object[] values = new Object[columns];
                    for (int i = 0; i < columns; i++) {
                        values[i] = results.getObject(i + 1);
                    }
                    pending.add(values);
                }
            }
        }
    }

    /** One partial: the group keys, then each call's value, stamped with the group's latest time. */
    private void write(RecordSink sink, Object[] values) {
        RowWriter writer = sink.beginRow();
        int keys = partial.groupByColumns().size();
        for (int i = 0; i < keys; i++) {
            writeKey(writer, i, values[i]);
        }
        for (int i = 0; i < partial.aggregates().size(); i++) {
            writer.setLong(keys + i, ((Number) values[keys + 2 + i]).longValue());
        }
        long latest = ((Number) values[keys + 1]).longValue();
        writer.weight(1L)
                // The newest watermark in the group: what the row path's last row of this group
                // in this page would have carried as its event time.
                .eventTimestampNanos(latest)
                .sequence(sequence++)
                .commit();
    }

    /** A group-key value, written as the aggregate's output column declares it. */
    private static void writeKey(RowWriter writer, int ordinal, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
        }
        TypeName type = writer.schema().field(ordinal).type().typeName();
        switch (type) {
            case BOOLEAN -> writer.setBoolean(ordinal, (Boolean) value);
            case INT8 -> writer.setByte(ordinal, ((Number) value).byteValue());
            case INT16 -> writer.setShort(ordinal, ((Number) value).shortValue());
            case INT32 -> writer.setInt(ordinal, ((Number) value).intValue());
            case INT64 -> writer.setLong(ordinal, ((Number) value).longValue());
            case FLOAT32 -> writer.setFloat(ordinal, ((Number) value).floatValue());
            case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());
            case STRING -> writer.setString(ordinal, value.toString());
            default ->
                // JdbcSourcePlugin only builds this reader for group keys of the types above; this
                // is the guard for a caller that did not go through it.
                throw new PravahaException(
                        JdbcErrors.QUERY_FAILED, "cannot write a " + type + " group key into a partial aggregate");
        }
    }

    /** The resume predicate, or null on the first poll -- see JdbcSourcePlugin for why two shapes. */
    private @Nullable String afterOffset() {
        return offset.isBeginning()
                ? null
                : "(" + watermarkColumn + " > ? OR (" + watermarkColumn + " = ? AND " + keyColumn + " > ?))";
    }

    private int bindAfterOffset(PreparedStatement statement, int parameter) throws SQLException {
        if (offset.isBeginning()) {
            return parameter;
        }
        statement.setLong(parameter++, offset.watermark());
        statement.setLong(parameter++, offset.watermark());
        statement.setLong(parameter++, offset.key());
        return parameter;
    }

    /** The WHERE clause: resume, page end, then the pushed filters, in parameter order. */
    private String where(@Nullable String after, @Nullable String upTo) {
        List<String> parts = new ArrayList<>(3);
        if (after != null) {
            parts.add(after);
        }
        if (upTo != null) {
            parts.add(upTo);
        }
        if (!pushed.isEmpty()) {
            parts.add("(" + pushed.sql() + ")");
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    /** Pages summarised so far, for tests and for anyone asking what pushdown saved. */
    long pages() {
        return pages;
    }

    /** Rows the database combined into partials rather than sending. */
    long rowsSummarised() {
        return rowsSummarised;
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
        // The connection belongs to the plugin, as it does for the row reader.
    }
}

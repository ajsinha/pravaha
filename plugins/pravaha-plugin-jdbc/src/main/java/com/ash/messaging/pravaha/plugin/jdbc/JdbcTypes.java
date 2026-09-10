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

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * JDBC's type system, mapped onto Pravaha's.
 *
 * <p>Derived from {@link ResultSetMetaData} rather than declared, and this is the one source where
 * that is the right call: the database already holds an authoritative, typed schema, so asking it
 * beats asking a human to restate it and get it subtly wrong. A CSV file has no such authority,
 * which is why the feed-file plugin insists on a declaration and this one does not.
 *
 * <p>Anything unmapped is refused by name. Silently stringifying a {@code JSONB} or an array column
 * would produce a query that runs and is wrong about that column forever.
 */
final class JdbcTypes {

    private JdbcTypes() {}

    /** Builds a stream schema from a result set's metadata. */
    static StreamSchema toStreamSchema(String streamName, ResultSetMetaData metadata) throws SQLException {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String name = metadata.getColumnLabel(column);
            PravahaType type = toPravahaType(streamName, name, metadata.getColumnType(column));
            boolean nullable = metadata.isNullable(column) != ResultSetMetaData.columnNoNulls;
            builder.field(name, nullable ? type.withNullable(true) : type);
        }
        return builder.build();
    }

    private static PravahaType toPravahaType(String stream, String column, int sqlType) {
        return switch (sqlType) {
            case Types.BOOLEAN, Types.BIT -> com.ash.messaging.pravaha.api.data.Types.bool();
            case Types.TINYINT -> com.ash.messaging.pravaha.api.data.Types.int8();
            case Types.SMALLINT -> com.ash.messaging.pravaha.api.data.Types.int16();
            case Types.INTEGER -> com.ash.messaging.pravaha.api.data.Types.int32();
            case Types.BIGINT -> com.ash.messaging.pravaha.api.data.Types.int64();
            case Types.REAL -> com.ash.messaging.pravaha.api.data.Types.float32();
            case Types.FLOAT, Types.DOUBLE -> com.ash.messaging.pravaha.api.data.Types.float64();
            case Types.NUMERIC, Types.DECIMAL ->
                // Mapped to DECIMAL rather than DOUBLE deliberately: a monetary column that silently
                // becomes a float is a rounding bug in somebody's ledger.
                com.ash.messaging.pravaha.api.data.Types.decimal(38, 9);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
                com.ash.messaging.pravaha.api.data.Types.string();
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> com.ash.messaging.pravaha.api.data.Types.bytes();
            case Types.DATE -> com.ash.messaging.pravaha.api.data.Types.date();
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> com.ash.messaging.pravaha.api.data.Types.timestamp();
            default ->
                throw new PravahaException(
                        JdbcErrors.UNSUPPORTED_TYPE,
                        "column '" + column + "' of stream '" + stream + "' has JDBC type " + sqlType
                                + ", which this plugin does not map. Cast it in the query -- the source is a "
                                + "SQL statement, so `SELECT col::text AS col` is available -- rather than "
                                + "having the engine guess at an encoding.");
        };
    }

    /** Copies one column of the current row into a Pravaha row. */
    static void copyValue(ResultSet results, int column, RowWriter writer, int ordinal, TypeName type)
            throws SQLException {
        switch (type) {
            case BOOLEAN -> writer.setBoolean(ordinal, results.getBoolean(column));
            case INT8 -> writer.setByte(ordinal, results.getByte(column));
            case INT16 -> writer.setShort(ordinal, results.getShort(column));
            case INT32 -> writer.setInt(ordinal, results.getInt(column));
            case DATE -> {
                java.sql.Date date = results.getDate(column);
                writer.setInt(ordinal, (int) date.toLocalDate().toEpochDay());
            }
            case INT64 -> writer.setLong(ordinal, results.getLong(column));
            case TIMESTAMP_LTZ -> {
                java.sql.Timestamp timestamp = results.getTimestamp(column);
                // Nanoseconds since the epoch (ADR-012). getTime() is milliseconds and getNanos()
                // is the sub-second part in full, so the milliseconds must be removed from one
                // before adding the other -- doing it the obvious way double-counts them.
                long millis = timestamp.getTime();
                writer.setLong(ordinal, (millis / 1000L) * 1_000_000_000L + timestamp.getNanos());
            }
            case FLOAT32 -> writer.setFloat(ordinal, results.getFloat(column));
            case FLOAT64 -> writer.setDouble(ordinal, results.getDouble(column));
            case DECIMAL -> {
                java.math.BigInteger unscaled = results.getBigDecimal(column)
                        .setScale(9, java.math.RoundingMode.UNNECESSARY)
                        .unscaledValue();
                writer.setDecimal(ordinal, unscaled.shiftRight(64).longValue(), unscaled.longValue());
            }
            // The variable-width types are read before being written, unlike the primitives above.
            // JDBC returns null for them rather than a zero, and the row writer will not take a
            // null -- so the "write it, then ask wasNull" protocol that works for a long throws a
            // NullPointerException here, on the first nullable text column that happens to be
            // empty. Found by a lookup test; it was reachable from the polling source too, where
            // no test had ever had a null string.
            case BYTES -> {
                byte[] value = results.getBytes(column);
                if (value == null) {
                    writer.setNull(ordinal);
                } else {
                    writer.setBytes(ordinal, value);
                }
            }
            default -> {
                String value = results.getString(column);
                if (value == null) {
                    writer.setNull(ordinal);
                } else {
                    writer.setString(ordinal, value);
                }
            }
        }
        if (results.wasNull()) {
            // Checked after reading, because that is the only moment JDBC will answer the question.
            // Reading first and overwriting with a null is the documented protocol, not a workaround.
            writer.setNull(ordinal);
        }
    }
}

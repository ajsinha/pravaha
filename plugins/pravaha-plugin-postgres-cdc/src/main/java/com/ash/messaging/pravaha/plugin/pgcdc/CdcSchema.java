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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * The captured table's columns, and the stream schema they become.
 *
 * <p>Asked of the database, as the {@code jdbc} source does: the catalog holds an authoritative
 * typed definition and a human restating it can only disagree. A declared {@code schema} is still
 * accepted -- to pin the shape a query was written against, or to leave out a column whose type has
 * no mapping -- and is <em>checked</em> against the table, by name, so a declaration that has drifted
 * from the table is refused at open rather than read into the wrong columns.
 *
 * <p>A declared schema may name fewer columns than the table has. The stream is then a projection,
 * and an update touching only an omitted column arrives as a retraction and an insertion of the same
 * row, which cancel -- correct, and cheap.
 */
final class CdcSchema {

    /** One column of the table, as {@code pg_attribute} has it. */
    record Column(String name, int typeOid, char typtype, boolean notNull, int typmod, String typeName) {}

    /** The stream, and for each of its fields the table column and type OID it is read from. */
    record Mapping(StreamSchema schema, List<String> columnNames, int[] typeOids) {}

    private CdcSchema() {}

    static List<Column> load(Connection connection, CdcOptions options) throws SQLException {
        String sql = "SELECT a.attname, a.atttypid, t.typtype, a.attnotnull, a.atttypmod, "
                + "format_type(a.atttypid, a.atttypmod) FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid "
                + "WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum";
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, options.quotedTable());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new Column(
                            rows.getString(1),
                            rows.getInt(2),
                            rows.getString(3).charAt(0),
                            rows.getBoolean(4),
                            rows.getInt(5),
                            rows.getString(6)));
                }
            }
        }
        return columns;
    }

    /** The type a column maps to when nothing is declared, or null when it has no mapping. */
    static PravahaType natural(Column column) {
        PravahaType type =
                switch (column.typeOid()) {
                    case PgValues.BOOL -> Types.bool();
                    case PgValues.INT2 -> Types.int16();
                    case PgValues.INT4 -> Types.int32();
                    case PgValues.INT8 -> Types.int64();
                    case PgValues.FLOAT4 -> Types.float32();
                    case PgValues.FLOAT8 -> Types.float64();
                    case PgValues.NUMERIC -> numeric(column.typmod());
                    case PgValues.TEXT,
                            PgValues.VARCHAR,
                            PgValues.BPCHAR,
                            PgValues.NAME,
                            PgValues.CHAR,
                            PgValues.UUID -> Types.string();
                    case PgValues.BYTEA -> Types.bytes();
                    case PgValues.DATE -> Types.date();
                    case PgValues.TIMESTAMP, PgValues.TIMESTAMPTZ -> Types.timestamp();
                    // An enum's text output is its label.
                    default -> column.typtype() == 'e' ? Types.string() : null;
                };
        return type == null ? null : type.withNullable(!column.notNull());
    }

    /**
     * {@code numeric(p,s)} keeps its scale; an unconstrained {@code numeric} becomes {@code
     * DECIMAL(38,9)} as it does in the {@code jdbc} source, and a value with more places is refused
     * when it arrives rather than rounded.
     */
    private static PravahaType numeric(int typmod) {
        if (typmod < 4) {
            return Types.decimal(38, 9);
        }
        int precision = ((typmod - 4) >> 16) & 0xFFFF;
        int scale = (typmod - 4) & 0xFFFF;
        if (precision > 38 || scale > precision) {
            return null;
        }
        return Types.decimal(38, scale);
    }

    /** Builds the stream schema and its column mapping, refusing anything that cannot be read exactly. */
    static Mapping resolve(CdcOptions options, List<Column> columns) {
        if (columns.isEmpty()) {
            throw mismatch(options, "table " + options.qualifiedTable() + " has no columns this source can see");
        }
        List<String> names = new ArrayList<>();
        List<Integer> oids = new ArrayList<>();
        StreamSchema.Builder builder = StreamSchema.builder(options.streamName());
        if (options.declaredSchema().isEmpty()) {
            for (Column column : columns) {
                PravahaType type = natural(column);
                if (type == null) {
                    throw mismatch(
                            options,
                            "column '" + column.name() + "' of " + options.qualifiedTable() + " is "
                                    + column.typeName()
                                    + ", which this source does not map. Declare a 'schema' that leaves "
                                    + "it out -- a declared schema may name any subset of the table's columns -- rather than "
                                    + "have the engine guess at an encoding.");
                }
                builder.field(column.name(), type);
                names.add(column.name());
                oids.add(column.typeOid());
            }
        } else {
            for (String entry : options.declaredSchema().split(",", -1)) {
                String[] parts = entry.strip().split(":", -1);
                if (parts.length != 2 || parts[0].isBlank()) {
                    throw CdcOptions.bad(
                            options.instanceName(),
                            "schema entry '" + entry.strip() + "' is not 'name:TYPE'. Example: id:INT64,tier:STRING?");
                }
                String name = parts[0].strip();
                Column column = columns.stream()
                        .filter(c -> c.name().equals(name))
                        .findFirst()
                        .or(() -> columns.stream()
                                .filter(c -> c.name().equalsIgnoreCase(name))
                                .findFirst())
                        .orElseThrow(() -> mismatch(
                                options,
                                "the declared schema names column '" + name + "', which "
                                        + options.qualifiedTable() + " does not have. Its columns: "
                                        + columns.stream().map(Column::name).toList()));
                PravahaType declared = declaredType(options, name, parts[1].strip());
                check(options, column, declared);
                builder.field(name, declared);
                names.add(column.name());
                oids.add(column.typeOid());
            }
        }
        if (!options.eventTimeColumn().isEmpty()) {
            int index = names.indexOf(options.eventTimeColumn());
            StreamSchema provisional = builder.build();
            if (index < 0 || provisional.field(index).type().typeName() != TypeName.TIMESTAMP_LTZ) {
                throw CdcOptions.bad(
                        options.instanceName(),
                        "event.time '" + options.eventTimeColumn()
                                + "' must name a timestamp column of the stream; its fields are "
                                + provisional.fields().stream().map(Field::name).toList());
            }
            builder.eventTime(options.eventTimeColumn());
        }
        return new Mapping(
                builder.build(),
                List.copyOf(names),
                oids.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Widening a column is allowed; narrowing it, or reading it as another kind of thing, is not. */
    private static final Set<String> WIDENINGS = Set.of("INT16>INT32", "INT16>INT64", "INT32>INT64", "FLOAT32>FLOAT64");

    private static void check(CdcOptions options, Column column, PravahaType declared) {
        PravahaType natural = natural(column);
        if (natural == null) {
            throw mismatch(
                    options,
                    "column '" + column.name() + "' is " + column.typeName()
                            + ", which this source does not map; leave it out of the declared schema");
        }
        TypeName from = natural.typeName();
        TypeName to = declared.typeName();
        if (from != to && !WIDENINGS.contains(from + ">" + to)) {
            throw mismatch(
                    options,
                    "column '" + column.name() + "' is " + column.typeName() + " in the table and "
                            + "declared " + to + ". It would be read as " + from
                            + "; declare that, or a wider integer or "
                            + "float, rather than a type the text of its values cannot be read as.");
        }
        if (!column.notNull() && !declared.nullable()) {
            throw mismatch(
                    options,
                    "column '" + column.name() + "' may be NULL in the table and is declared NOT "
                            + "NULL. Declare it '" + column.name() + ":" + to
                            + "?' -- the first NULL would otherwise stop "
                            + "the stream.");
        }
    }

    private static PravahaType declaredType(CdcOptions options, String column, String spelled) {
        String upper = spelled.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1);
        }
        PravahaType type =
                switch (upper) {
                    case "BOOLEAN", "BOOL" -> Types.bool();
                    case "INT16", "SMALLINT" -> Types.int16();
                    case "INT32", "INT", "INTEGER" -> Types.int32();
                    case "INT64", "LONG", "BIGINT" -> Types.int64();
                    case "FLOAT32", "REAL" -> Types.float32();
                    case "FLOAT64", "DOUBLE" -> Types.float64();
                    case "DECIMAL" -> Types.decimal(38, 9);
                    case "STRING", "TEXT", "VARCHAR" -> Types.string();
                    case "BYTES" -> Types.bytes();
                    case "DATE" -> Types.date();
                    case "TIMESTAMP" -> Types.timestamp();
                    default ->
                        throw CdcOptions.bad(
                                options.instanceName(),
                                "unknown type '" + spelled + "' for column '"
                                        + column
                                        + "'. Supported: BOOLEAN, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL, "
                                        + "STRING, BYTES, DATE, TIMESTAMP, each optionally followed by '?' for nullable.");
                };
        return nullable ? type.withNullable(true) : type;
    }

    private static com.ash.messaging.pravaha.api.ConfigurationException mismatch(CdcOptions options, String message) {
        return new com.ash.messaging.pravaha.api.ConfigurationException(
                CdcErrors.SCHEMA_MISMATCH, "plugin '" + options.instanceName() + "': " + message);
    }
}

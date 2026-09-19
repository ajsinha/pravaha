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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * A sink's table as the database describes it, checked against the sink's declaration.
 *
 * <p>Every refusal here is one the first write would otherwise make an hour into a run, as a driver
 * message about SQL rather than a sentence about configuration -- or, worse, would not make at all:
 * a {@code REAL} column accepts a double and rounds it, a {@code NUMERIC(10,2)} accepts a value of
 * scale 9 and rounds that. So a column that cannot hold what the declaration says it will be sent is
 * refused by name when the sink opens ({@link JdbcErrors#SINK_TABLE_MISMATCH}).
 *
 * <p><strong>Identifiers are found, then quoted.</strong> PostgreSQL folds an unquoted name to lower
 * case and H2 to upper, so {@code orders} names {@code orders} in one and {@code ORDERS} in the
 * other. The table and each column are looked up in the catalogue -- exactly first, then ignoring
 * case when that is unambiguous -- and every statement uses the name the catalogue returned, quoted.
 * Quoting the configured spelling instead would fail on one database or the other; not quoting
 * would let a column called {@code order} break the statement.
 */
final class JdbcSinkTable {

    /** One table column the sink writes, in declaration order. */
    record Column(String name, String quoted, int sqlType, String typeName, boolean withTimeZone) {}

    private final String qualified;
    private final List<Column> columns;
    private final Set<String> uniqueKeys;

    private JdbcSinkTable(String qualified, List<Column> columns, Set<String> uniqueKeys) {
        this.qualified = qualified;
        this.columns = List.copyOf(columns);
        this.uniqueKeys = Set.copyOf(uniqueKeys);
    }

    /** The table's name as statements should spell it: found in the catalogue, quoted. */
    String qualified() {
        return qualified;
    }

    /** The columns, by ordinal of the declared schema. */
    List<Column> columns() {
        return columns;
    }

    /**
     * Whether a primary key or unique index covers exactly these columns, compared as catalogue names.
     *
     * <p>{@code ON CONFLICT (...)} needs one; without it PostgreSQL refuses the statement at the first
     * write with "no unique or exclusion constraint matching".
     */
    boolean hasUniqueKeyOn(List<String> catalogueNames) {
        return uniqueKeys.contains(keyOf(catalogueNames));
    }

    /**
     * Looks the table up and checks it against the declaration.
     *
     * @param configured the {@code table} setting: {@code name} or {@code schema.name}
     */
    static JdbcSinkTable resolve(Connection connection, String configured, StreamSchema declared) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            quote = "\"";
        }
        String schemaPart = null;
        String tablePart = configured.strip();
        int dot = tablePart.lastIndexOf('.');
        if (dot > 0) {
            schemaPart = tablePart.substring(0, dot).strip();
            tablePart = tablePart.substring(dot + 1).strip();
        }
        String[] found = findTable(connection, metadata, schemaPart, tablePart, configured);
        String catalog = found[0];
        String schema = found[1];
        String table = found[2];

        Map<String, ResultRow> byName = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getColumns(catalog, escape(metadata, schema), escape(metadata, table), "%")) {
            while (rs.next()) {
                // A pattern that escaped cleanly still matches only this table, but a driver that
                // ignores the escape would not -- so the table name is checked as well.
                if (!table.equals(rs.getString("TABLE_NAME"))) {
                    continue;
                }
                ResultRow row = new ResultRow(
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls,
                        rs.getString("COLUMN_DEF") != null
                                || "YES".equalsIgnoreCase(optional(rs, "IS_AUTOINCREMENT"))
                                || "YES".equalsIgnoreCase(optional(rs, "IS_GENERATEDCOLUMN")));
                byName.put(row.name(), row);
            }
        }

        List<Column> columns = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int ordinal = 0; ordinal < declared.fieldCount(); ordinal++) {
            Field field = declared.field(ordinal);
            ResultRow column = findColumn(byName, field.name(), configured);
            requireHolds(configured, field, column);
            used.add(column.name());
            columns.add(new Column(
                    column.name(),
                    quote(quote, column.name()),
                    column.dataType(),
                    column.typeName(),
                    column.dataType() == Types.TIMESTAMP_WITH_TIMEZONE
                            || (column.typeName() != null
                                    && column.typeName()
                                            .toLowerCase(Locale.ROOT)
                                            .matches(".*(timestamptz|with time zone).*"))));
        }
        for (ResultRow other : byName.values()) {
            if (!used.contains(other.name()) && other.notNull() && !other.hasDefault()) {
                throw new PravahaException(
                        JdbcErrors.SINK_TABLE_MISMATCH,
                        "table '" + configured + "' has column '" + other.name() + "', which is NOT NULL, has no "
                                + "default and is not in the sink's schema -- so every insert would fail. Add it to the "
                                + "schema (and the query's SELECT list), give it a default, or make it nullable.");
            }
        }

        String qualified = (schema == null || schema.isEmpty() ? "" : quote(quote, schema) + ".") + quote(quote, table);
        return new JdbcSinkTable(qualified, columns, uniqueKeys(metadata, catalog, schema, table));
    }

    private record ResultRow(
            String name, int dataType, String typeName, int size, int digits, boolean notNull, boolean hasDefault) {}

    /** {catalog, schema, table} as the catalogue spells them. */
    private static String[] findTable(
            Connection connection, DatabaseMetaData metadata, String schemaPart, String tablePart, String configured)
            throws SQLException {
        String schemaPattern = schemaPart;
        if (schemaPattern == null) {
            // The session's own schema, so `orders` means the orders a plain SELECT would read, not
            // whichever of several schemas' orders the catalogue lists first.
            try {
                schemaPattern = connection.getSchema();
            } catch (SQLException | AbstractMethodError unsupported) {
                schemaPattern = null;
            }
        }
        List<String[]> matches = new ArrayList<>();
        for (String candidate : spellings(tablePart)) {
            for (String schemaCandidate : schemaPattern == null ? List.<String>of() : spellings(schemaPattern)) {
                matches = tables(metadata, schemaCandidate, candidate);
                if (!matches.isEmpty()) {
                    break;
                }
            }
            if (matches.isEmpty() && schemaPart == null) {
                matches = tables(metadata, null, candidate);
            }
            if (!matches.isEmpty()) {
                break;
            }
        }
        if (matches.isEmpty()) {
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "table '" + configured + "' does not exist, or this connection's user cannot see it. The sink "
                            + "writes into a table you create; it does not create one, because the column types, the "
                            + "key and the indexes are decisions about your database, not about this query.");
        }
        if (matches.size() > 1) {
            List<String> where = matches.stream().map(m -> m[1] + "." + m[2]).toList();
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "table '" + configured + "' is ambiguous: " + where + ". Name it as schema.table.");
        }
        return matches.get(0);
    }

    private static List<String[]> tables(DatabaseMetaData metadata, String schema, String table) throws SQLException {
        List<String[]> found = new ArrayList<>();
        try (ResultSet rs = metadata.getTables(null, escape(metadata, schema), escape(metadata, table), null)) {
            while (rs.next()) {
                String type = rs.getString("TABLE_TYPE");
                String name = rs.getString("TABLE_NAME");
                if (!table.equals(name) || (schema != null && !schema.equals(rs.getString("TABLE_SCHEM")))) {
                    continue;
                }
                if (type != null && !type.toUpperCase(Locale.ROOT).contains("TABLE")) {
                    continue;
                }
                found.add(new String[] {rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), name});
            }
        }
        return found;
    }

    /** The name as written, then as a database that folds to upper or to lower would store it. */
    private static List<String> spellings(String name) {
        List<String> out = new ArrayList<>();
        for (String each : new String[] {name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)}) {
            if (!out.contains(each)) {
                out.add(each);
            }
        }
        return out;
    }

    private static String escape(DatabaseMetaData metadata, String name) throws SQLException {
        if (name == null) {
            return null;
        }
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static String optional(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException absent) {
            // JDBC 4.1 columns; an older driver has none, and "unknown" is not "yes".
            return null;
        }
    }

    private static ResultRow findColumn(Map<String, ResultRow> byName, String declared, String table) {
        ResultRow exact = byName.get(declared);
        if (exact != null) {
            return exact;
        }
        List<ResultRow> folded = byName.values().stream()
                .filter(c -> c.name().equalsIgnoreCase(declared))
                .toList();
        if (folded.size() == 1) {
            return folded.get(0);
        }
        if (folded.size() > 1) {
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "column '" + declared + "' matches "
                            + folded.stream().map(ResultRow::name).toList() + " in table '" + table
                            + "', which differ only in case. Declare it with the exact spelling.");
        }
        throw new PravahaException(
                JdbcErrors.SINK_TABLE_MISMATCH,
                "table '" + table + "' has no column '" + declared + "'. Its columns are " + byName.keySet()
                        + ". The sink's schema names the columns it writes, so each must exist in the table.");
    }

    /** Refuses a column that cannot hold every value of the declared type. */
    private static void requireHolds(String table, Field field, ResultRow column) {
        TypeName declared = field.type().typeName();
        boolean holds =
                switch (declared) {
                    case BOOLEAN -> is(column, Types.BOOLEAN, Types.BIT);
                    case INT8 ->
                        is(column, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT)
                                || integerDigits(column) >= 3;
                    case INT16 -> is(column, Types.SMALLINT, Types.INTEGER, Types.BIGINT) || integerDigits(column) >= 5;
                    case INT32 -> is(column, Types.INTEGER, Types.BIGINT) || integerDigits(column) >= 10;
                    case INT64 -> is(column, Types.BIGINT) || integerDigits(column) >= 19;
                    case FLOAT32 -> is(column, Types.REAL, Types.FLOAT, Types.DOUBLE);
                    case FLOAT64 -> is(column, Types.FLOAT, Types.DOUBLE);
                    case DECIMAL -> holdsDecimal(column, (DecimalType) field.type());
                    case STRING ->
                        is(
                                column,
                                Types.CHAR,
                                Types.VARCHAR,
                                Types.LONGVARCHAR,
                                Types.NCHAR,
                                Types.NVARCHAR,
                                Types.LONGNVARCHAR,
                                Types.CLOB,
                                Types.NCLOB);
                    case BYTES -> is(column, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB);
                    case DATE -> is(column, Types.DATE);
                    case TIME -> is(column, Types.TIME);
                    case TIMESTAMP_LTZ -> is(column, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE);
                    default -> false;
                };
        if (!holds) {
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "column '" + column.name() + "' of table '" + table + "' is " + describe(column)
                            + ", which cannot hold the " + describeDeclared(field) + " the sink's schema declares for '"
                            + field.name() + "'. A narrower column would reject some values at write time or round "
                            + "them silently; widen the column or declare the type the column really holds.");
        }
        if (field.type().nullable() && column.notNull()) {
            throw new PravahaException(
                    JdbcErrors.SINK_TABLE_MISMATCH,
                    "'" + field.name() + "' is declared nullable, and column '" + column.name() + "' of table '" + table
                            + "' is NOT NULL, so the first null would fail the write. Make the column nullable or "
                            + "drop the ? from the declaration.");
        }
    }

    private static String describeDeclared(Field field) {
        if (field.type() instanceof DecimalType decimal) {
            return "DECIMAL(" + decimal.precision() + "," + decimal.scale() + ")";
        }
        return field.type().typeName().name();
    }

    private static boolean is(ResultRow column, int... types) {
        for (int type : types) {
            if (column.dataType() == type) {
                return true;
            }
        }
        return false;
    }

    /** Whole-number digits a NUMERIC column holds; zero for anything else. */
    private static int integerDigits(ResultRow column) {
        if (!is(column, Types.NUMERIC, Types.DECIMAL)) {
            return 0;
        }
        if (unconstrained(column)) {
            return Integer.MAX_VALUE;
        }
        return column.size() - Math.max(0, column.digits());
    }

    /**
     * An unconstrained {@code NUMERIC} -- PostgreSQL reports its size as 131089 or 0 -- holds any
     * value this engine can produce.
     */
    private static boolean unconstrained(ResultRow column) {
        return column.size() <= 0 || column.size() >= 1000;
    }

    private static boolean holdsDecimal(ResultRow column, DecimalType declared) {
        if (!is(column, Types.NUMERIC, Types.DECIMAL)) {
            return false;
        }
        if (unconstrained(column)) {
            return true;
        }
        int scale = Math.max(0, column.digits());
        return scale >= declared.scale() && column.size() - scale >= declared.precision() - declared.scale();
    }

    private static String describe(ResultRow column) {
        String type = column.typeName() == null ? "JDBC type " + column.dataType() : column.typeName();
        if (is(column, Types.NUMERIC, Types.DECIMAL) && !unconstrained(column)) {
            return type + "(" + column.size() + "," + Math.max(0, column.digits()) + ")";
        }
        return type;
    }

    private static Set<String> uniqueKeys(DatabaseMetaData metadata, String catalog, String schema, String table)
            throws SQLException {
        Set<String> keys = new HashSet<>();
        List<String> primary = new ArrayList<>();
        try (ResultSet rs = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primary.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (!primary.isEmpty()) {
            keys.add(keyOf(primary));
        }
        Map<String, List<String>> indexes = new HashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(catalog, schema, table, true, true)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index != null && column != null && !rs.getBoolean("NON_UNIQUE")) {
                    indexes.computeIfAbsent(index, ignored -> new ArrayList<>()).add(column);
                }
            }
        }
        indexes.values().forEach(columns -> keys.add(keyOf(columns)));
        return keys;
    }

    private static String keyOf(List<String> columns) {
        return String.join("\u0000", new TreeSet<>(columns));
    }

    static String quote(String quote, String identifier) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}

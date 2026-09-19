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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * The captured table's primary key, and the two pieces of SQL an initial snapshot orders by it.
 *
 * <p><strong>PostgreSQL does every comparison.</strong> A snapshot reads the table in key order a
 * chunk at a time, and after a restart it also has to say which changes in the log fall at or below
 * the key it had reached. Both questions are asked of the database, with the same operator, the same
 * column type and the same collation: a Java comparison of a {@code text} key would sort by code
 * point while an {@code en_US} index sorts by locale, and the two would disagree about exactly the
 * rows at the boundary -- the rows a resume must get right. Keys travel as PostgreSQL's own text for
 * them and are cast back to the column's type, which round-trips for every type a primary key can
 * have.
 *
 * @param columns the key's columns in index order
 */
record SnapshotKey(List<Column> columns) {

    /**
     * One key column.
     *
     * @param type the column's type as {@code format_type} spells it, ready to cast to
     * @param collation the column's collation, qualified and quoted, or null for a type without one
     */
    record Column(String name, String type, String collation) {}

    SnapshotKey {
        columns = List.copyOf(columns);
    }

    /** Reads the primary key, refusing a table without one: a snapshot needs an order it can resume. */
    static SnapshotKey load(Connection connection, CdcOptions options) throws SQLException {
        String sql = "SELECT a.attname, format_type(a.atttypid, a.atttypmod), CASE WHEN a.attcollation = 0 "
                + "THEN NULL ELSE quote_ident(n.nspname) || '.' || quote_ident(c.collname) END "
                + "FROM pg_index i "
                + "CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum "
                + "LEFT JOIN pg_collation c ON c.oid = a.attcollation "
                + "LEFT JOIN pg_namespace n ON n.oid = c.collnamespace "
                + "WHERE i.indrelid = ?::regclass AND i.indisprimary ORDER BY k.ord";
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, options.quotedTable());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new Column(rows.getString(1), rows.getString(2), rows.getString(3)));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new ConfigurationException(
                    CdcErrors.NOT_CAPTURABLE,
                    "plugin '" + options.instanceName() + "': an initial snapshot of " + options.qualifiedTable()
                            + " needs a primary key, and it has none. The snapshot reads the table in key order a "
                            + "chunk at a time and resumes after a restart from the last key it delivered; without a "
                            + "key there is no order to resume in. Add one (ALTER TABLE " + options.qualifiedTable()
                            + " ADD PRIMARY KEY (...);) or use snapshot.mode: never.");
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT has_table_privilege(?, 'SELECT')")) {
            statement.setString(1, options.quotedTable());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && !rows.getBoolean(1)) {
                    throw new ConfigurationException(
                            CdcErrors.NOT_CAPTURABLE,
                            "plugin '" + options.instanceName() + "': an initial snapshot reads "
                                    + options.qualifiedTable() + ", and this role cannot SELECT from it. The "
                                    + "REPLICATION attribute reads the log, not the table. Run: GRANT SELECT ON "
                                    + options.qualifiedTable() + " TO <role>;");
                }
            }
        }
        return new SnapshotKey(columns);
    }

    int size() {
        return columns.size();
    }

    List<String> names() {
        return columns.stream().map(Column::name).toList();
    }

    /** {@code CAST(expr AS type) COLLATE collation}: a value compared exactly as the column is. */
    private String typed(int index, String expression) {
        Column column = columns.get(index);
        String cast = "CAST(" + expression + " AS " + column.type() + ")";
        return column.collation() == null ? cast : cast + " COLLATE " + column.collation();
    }

    /**
     * A column's value as PostgreSQL's text for it -- the type's output function, which is what
     * {@code pgoutput} sends -- or NULL. {@code format('%s', ...)} rather than {@code ::text}: the
     * cast is not always the output function ({@code true::text} is {@code true}, the stream's
     * {@code t}; a {@code char(n)} cast to text loses its padding).
     */
    static String text(String column) {
        String quoted = CdcOptions.quote(column);
        return "CASE WHEN " + quoted + " IS NULL THEN NULL ELSE format('%s', " + quoted + ") END";
    }

    /**
     * One chunk: the stream's columns as text, then the key's, of the first {@code limit} rows above
     * the key bound as parameters 1..n -- or from the start of the table when {@code fromStart}.
     */
    String chunkSql(CdcOptions options, List<String> streamColumns, boolean fromStart, int limit) {
        StringJoiner select = new StringJoiner(", ");
        streamColumns.forEach(column -> select.add(text(column)));
        names().forEach(column -> select.add(text(column)));
        StringJoiner order = new StringJoiner(", ");
        StringJoiner keys = new StringJoiner(", ", "(", ")");
        StringJoiner bound = new StringJoiner(", ", "(", ")");
        for (int i = 0; i < size(); i++) {
            String quoted = CdcOptions.quote(columns.get(i).name());
            order.add(quoted);
            keys.add(quoted);
            bound.add(typed(i, "?"));
        }
        return "SELECT " + select + " FROM " + options.quotedTable()
                + (fromStart ? "" : " WHERE " + keys + " > " + bound)
                + " ORDER BY " + order + " LIMIT " + limit;
    }

    /**
     * Which of {@code keys} sort at or below {@code frontier} in the key's own order, decided by
     * PostgreSQL in one query.
     *
     * @return one flag per key, in order
     */
    boolean[] atOrBelow(Connection connection, List<List<String>> keys, List<String> frontier) throws SQLException {
        StringJoiner arrays = new StringJoiner(", ");
        StringJoiner aliases = new StringJoiner(", ");
        StringJoiner left = new StringJoiner(", ", "(", ")");
        StringJoiner right = new StringJoiner(", ", "(", ")");
        for (int i = 0; i < size(); i++) {
            arrays.add("?::text[]");
            aliases.add("k" + i);
            left.add(typed(i, "u.k" + i));
            right.add(typed(i, "?"));
        }
        String sql = "SELECT u.ord FROM unnest(" + arrays + ") WITH ORDINALITY AS u(" + aliases + ", ord) WHERE " + left
                + " <= " + right;
        boolean[] below = new boolean[keys.size()];
        List<Array> bound = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < size(); i++) {
                String[] column = new String[keys.size()];
                for (int row = 0; row < keys.size(); row++) {
                    column[row] = keys.get(row).get(i);
                }
                Array array = connection.createArrayOf("text", column);
                bound.add(array);
                statement.setArray(i + 1, array);
            }
            for (int i = 0; i < size(); i++) {
                statement.setString(size() + i + 1, frontier.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    below[(int) rows.getLong(1) - 1] = true;
                }
            }
        } finally {
            for (Array array : bound) {
                array.free();
            }
        }
        return below;
    }
}

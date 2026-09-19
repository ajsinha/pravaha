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

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * How a sink spells an upsert on a given database.
 *
 * <p>Standard SQL has {@code MERGE}, and it is the one statement whose support is least standard:
 * PostgreSQL gained it only in 15 and does not let it be the target of a conflict-free upsert, H2
 * has its own {@code MERGE ... KEY}, and many databases have neither. So there are three spellings,
 * picked from {@link java.sql.DatabaseMetaData#getDatabaseProductName()} unless the {@code dialect}
 * setting names one:
 *
 * <ul>
 *   <li>{@link #POSTGRESQL}: {@code INSERT ... ON CONFLICT (key) DO UPDATE SET c = EXCLUDED.c}, one
 *       atomic statement, which needs a unique index or primary key on exactly the key.
 *   <li>{@link #H2}: {@code MERGE INTO ... KEY (key) VALUES (...)}.
 *   <li>{@link #PORTABLE}: {@code UPDATE ... WHERE key}, and an {@code INSERT} for every row the update
 *       found nothing to change. Two statements and correct only because the sink is the table's one
 *       writer and runs them inside one transaction; a concurrent writer inserting the same key
 *       between them makes the insert fail on the unique index, if there is one, and duplicates the
 *       row if there is not.
 * </ul>
 *
 * <p>Every statement is a {@link java.sql.PreparedStatement} with one parameter per value and every
 * identifier quoted: a value is never part of the SQL text.
 */
enum JdbcDialect {
    POSTGRESQL,
    H2,
    PORTABLE;

    /** The dialect a {@code dialect} setting names, or {@code null} for {@code auto}. */
    static JdbcDialect named(String setting) {
        return switch (setting.strip().toLowerCase(Locale.ROOT)) {
            case "", "auto" -> null;
            case "postgresql", "postgres" -> POSTGRESQL;
            case "h2" -> H2;
            case "portable", "generic" -> PORTABLE;
            default ->
                throw new ConfigurationException(
                        JdbcErrors.BAD_CONFIGURATION,
                        "dialect '" + setting + "' is not one of auto, postgresql, h2, portable");
        };
    }

    /** The dialect for a database product name. */
    static JdbcDialect forProduct(String productName) {
        String product = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            return POSTGRESQL;
        }
        if (product.equals("h2")) {
            return H2;
        }
        return PORTABLE;
    }

    /**
     * The single statement that inserts or replaces a row, with parameters in column order; {@code
     * null} for {@link #PORTABLE}, which has none and uses {@link #update} then {@link #insert}.
     */
    String upsert(String table, List<String> columns, List<String> keys) {
        return switch (this) {
            case POSTGRESQL -> {
                List<String> rest =
                        columns.stream().filter(c -> !keys.contains(c)).toList();
                String action = rest.isEmpty()
                        ? "DO NOTHING"
                        : "DO UPDATE SET "
                                + rest.stream().map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "));
                yield insert(table, columns) + " ON CONFLICT (" + String.join(", ", keys) + ") " + action;
            }
            case H2 ->
                "MERGE INTO " + table + " (" + String.join(", ", columns) + ") KEY (" + String.join(", ", keys)
                        + ") VALUES (" + marks(columns.size()) + ")";
            case PORTABLE -> null;
        };
    }

    /**
     * {@code UPDATE table SET rest WHERE key}: parameters are the non-key columns in column order,
     * then the key columns in key order. A table that is all key sets its first key column to itself,
     * so the update still counts the row it found.
     */
    static String update(String table, List<String> columns, List<String> keys) {
        List<String> rest = columns.stream().filter(c -> !keys.contains(c)).toList();
        List<String> set = rest.isEmpty() ? List.of(keys.get(0)) : rest;
        return "UPDATE " + table + " SET " + set.stream().map(c -> c + " = ?").collect(Collectors.joining(", "))
                + " WHERE " + where(keys);
    }

    static String insert(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + marks(columns.size()) + ")";
    }

    static String delete(String table, List<String> keys) {
        return "DELETE FROM " + table + " WHERE " + where(keys);
    }

    /** The column type of the staging table's payload: a byte string with no small limit. */
    String payloadType() {
        return this == POSTGRESQL ? "BYTEA" : "BLOB";
    }

    private static String where(List<String> keys) {
        return keys.stream().map(k -> k + " = ?").collect(Collectors.joining(" AND "));
    }

    private static String marks(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}

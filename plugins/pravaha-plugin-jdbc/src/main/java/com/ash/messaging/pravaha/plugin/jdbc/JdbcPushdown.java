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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;

/**
 * Turns the engine's pushdown request into a SQL fragment the database can index on.
 *
 * <p>This is where a filter stops costing bandwidth. Without it a query over a million-row table
 * reads a million rows and the engine throws away all but a few; with it the database uses whatever
 * index it has and sends what was asked for.
 *
 * <p>Two rules, both about not being clever.
 *
 * <p><strong>Values are bound, never interpolated.</strong> A literal spliced into SQL is an
 * injection whenever the value came from anywhere but a constant, and quoting it correctly means
 * knowing the dialect's escaping, its date format and its numeric precision. A parameter marker
 * needs none of that and is what the query planner wants anyway -- an interpolated literal defeats
 * statement caching in most databases.
 *
 * <p><strong>Column names are checked against the schema, not quoted into shape.</strong> A name
 * cannot be a parameter, so the only safe name is one that came from the database's own catalogue.
 * A filter naming something else is dropped, which costs a little bandwidth; accepting it would be
 * accepting arbitrary SQL from whatever produced the name.
 *
 * @param sql a boolean expression with parameter markers, or empty if nothing was pushable
 * @param values what to bind to those markers, in order
 */
record JdbcPushdown(String sql, List<Object> values) {

    static final JdbcPushdown NOTHING = new JdbcPushdown("", List.of());

    JdbcPushdown {
        values = List.copyOf(values);
    }

    boolean isEmpty() {
        return sql.isBlank();
    }

    /** Builds the fragment, silently dropping any filter this cannot express exactly. */
    static JdbcPushdown of(ReadRequest request, StreamSchema schema) {
        if (request == null || request.isEmpty()) {
            return NOTHING;
        }
        StringBuilder sql = new StringBuilder();
        List<Object> values = new ArrayList<>();

        for (ReadRequest.Filter filter : request.filters()) {
            if (!hasColumn(schema, filter.column())) {
                continue;
            }
            if (!sql.isEmpty()) {
                sql.append(" AND ");
            }
            sql.append(filter.column()).append(' ').append(filter.comparison().sql());
            if (filter.comparison() != ReadRequest.Comparison.IS_NULL
                    && filter.comparison() != ReadRequest.Comparison.IS_NOT_NULL) {
                sql.append(" ?");
                values.add(filter.value());
            }
        }
        return sql.isEmpty() ? NOTHING : new JdbcPushdown(sql.toString(), values);
    }

    private static boolean hasColumn(StreamSchema schema, String name) {
        return schema.fields().stream().anyMatch(field -> field.name().equals(name));
    }
}

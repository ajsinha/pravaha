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
package com.ash.messaging.pravaha.pgwire;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * What a {@code Parse} produced, kept under its statement name until {@code Close} or the session
 * ends.
 *
 * <p>Six kinds, because {@code PgWireConnection.simpleQuery} already answers six different shapes
 * of statement text with six different code paths -- a view query, a {@code pg_catalog} question,
 * a {@code SET}, transaction control, a {@code SHOW}, and an empty string -- and the extended
 * protocol is the same six shapes with a name attached and the planning and the execution pulled
 * apart. This is that same classification,
 * made once at {@code Parse} and reused at {@code Describe}, {@code Bind} and {@code Execute} rather
 * than re-decided at each one.
 */
sealed interface PgStatement {

    /** The columns a {@code Describe} on this statement reports, or empty for one that returns none. */
    java.util.Optional<StreamSchema> resultSchema();

    /** The placeholders a caller must bind, in order. Only {@link ForView} ever has any. */
    default List<TypeName> parameterTypes() {
        return List.of();
    }

    /**
     * An ordinary query over a view, already planned and authorized by {@link ViewQuery#prepare}.
     *
     * @param limit the statement's trailing top-level {@code LIMIT}, which {@link PgTrailingLimit}
     *     took off before planning and the gateway applies to the answer, or {@link
     *     PgTrailingLimit#NONE}
     */
    record ForView(ViewQuery.Prepared prepared, long limit) implements PgStatement {
        @Override
        public java.util.Optional<StreamSchema> resultSchema() {
            return java.util.Optional.of(prepared.resultSchema());
        }

        @Override
        public List<TypeName> parameterTypes() {
            return prepared.parameters().types();
        }
    }

    /**
     * A {@code pg_catalog} question {@link PgCatalogShim} answers.
     *
     * <p>{@code sql} still carries its {@code $n} placeholders, unlike {@link ForView}: {@code
     * PgCatalogShim} recognises a query by matching its text, filter literal included, and has no
     * placeholder syntax of its own to bind against, so a real bound parameter (a driver's own {@code
     * getColumns()} binds its table-name filter as {@code $1} rather than inlining it) is substituted
     * back into this same text at {@code Bind}/{@code Execute} time -- see {@link
     * PgParameterSyntax#substituteLiterals}.
     *
     * <p>The schema is captured once, at {@code Parse}, by running the query with a wildcard for
     * every placeholder (the shape of the answer does not depend on the filter's value) -- {@link
     * PgExtendedSession} runs it again, with the real bound values, for the actual rows at {@code
     * Execute} time, so what a client reads is never older than its own {@code Execute}. A malformed
     * catalog query is still refused where a real backend would refuse it: at {@code Parse}, not
     * three messages later.
     */
    record ForCatalog(String sql, int parameterCount, StreamSchema schema) implements PgStatement {
        @Override
        public java.util.Optional<StreamSchema> resultSchema() {
            return java.util.Optional.of(schema);
        }

        @Override
        public List<TypeName> parameterTypes() {
            // Every placeholder a real catalog query has ever bound is a LIKE pattern -- a string.
            return java.util.Collections.nCopies(parameterCount, TypeName.STRING);
        }
    }

    /** A {@code SET} on {@link PgSessionSet}'s allow-list. Answers {@code CommandComplete("SET")}, no rows. */
    record ForSet(String sql) implements PgStatement {
        @Override
        public java.util.Optional<StreamSchema> resultSchema() {
            return java.util.Optional.empty();
        }
    }

    /**
     * Transaction control -- {@code BEGIN}, {@code COMMIT}, a savepoint verb, {@code SET TRANSACTION}.
     * Run at {@code Execute}, not {@code Parse}: pgjdbc parses {@code BEGIN} and {@code COMMIT} once and
     * executes them many times. See {@link PgTransactionBlock}.
     */
    record ForTransaction(PgTransactionBlock.Command command) implements PgStatement {
        @Override
        public java.util.Optional<StreamSchema> resultSchema() {
            return java.util.Optional.empty();
        }
    }

    /** A {@code SHOW} {@link PgShow} answers: one fixed row, known at {@code Parse}. Tagged {@code SHOW}. */
    record ForShow(ViewQuery.Result answer) implements PgStatement {
        @Override
        public java.util.Optional<StreamSchema> resultSchema() {
            return java.util.Optional.of(answer.schema());
        }
    }

    /** A query string that is empty, or entirely comments and semicolons. Answers {@code EmptyQueryResponse}. */
    record Empty() implements PgStatement {
        @Override
        public java.util.Optional<StreamSchema> resultSchema() {
            return java.util.Optional.empty();
        }
    }
}

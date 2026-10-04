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
package com.ash.messaging.pravaha.embedded;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.serving.Retention;

/**
 * A continuous query as an application declares it: a name, the SQL, the columns its view is keyed
 * by, and optionally how long the view remembers and a sink its changes are also written to.
 *
 * <p>Key columns are named rather than numbered. The registry keys a view by output ordinal, and an
 * ordinal is the thing that silently changes meaning when somebody adds a column to the SELECT list;
 * a name either still resolves or is refused at registration.
 *
 * <pre>{@code
 * engine.register(ContinuousQuery.named("spend")
 *         .sql("SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id")
 *         .keyedBy("user_id")
 *         .build());
 * }</pre>
 *
 * @param name the view's name, as a later {@code SELECT ... FROM <name>} will say it
 * @param sql the continuous query
 * @param keyColumns the output columns the view is keyed by; at least one
 * @param retention how long the view remembers, or empty for the engine's default
 * @param sink a bound sink the query's changelog is also written to, or empty for the view only
 */
public record ContinuousQuery(
        String name, String sql, List<String> keyColumns, Optional<Retention> retention, Optional<String> sink) {

    public ContinuousQuery {
        if (name == null || name.isBlank()) {
            throw malformed("a continuous query needs a name: it is what its view is read by");
        }
        if (sql == null || sql.isBlank()) {
            throw malformed("continuous query '" + name + "' has no SQL");
        }
        keyColumns = keyColumns == null ? List.of() : List.copyOf(keyColumns);
        if (keyColumns.isEmpty()) {
            throw malformed("continuous query '" + name + "' needs at least one key column: a "
                    + "view with no key is a log, and a point read against it has nothing to look up. A "
                    + "global aggregate, which has one row, may be keyed by any of its own columns");
        }
        retention = retention == null ? Optional.empty() : retention;
        sink = sink == null ? Optional.empty() : sink.filter(s -> !s.isBlank());
        if (sink.isPresent() && retention.isPresent()) {
            // The registry writes a sink-bound view with no age limit: a view that forgot a row the
            // sink still holds would retract nothing when that row changed, and the two would part.
            throw malformed("continuous query '" + name + "' writes to sink '" + sink.get()
                    + "' and asks for a retention; a query writing to a sink keeps its view for ever, so "
                    + "that the view and the sink cannot disagree about what exists");
        }
    }

    /**
     * A declaration this engine cannot register, coded as the statement's own refusal ({@code
     * PRV-2070}) -- {@code CREATE CONTINUOUS QUERY} without {@code KEYED BY} is refused with it. It
     * was an uncoded {@code IllegalArgumentException} (UNCODEDAPI-1).
     */
    private static com.ash.messaging.pravaha.api.PravahaException malformed(String message) {
        return new com.ash.messaging.pravaha.api.PravahaException(
                com.ash.messaging.pravaha.sql.SqlErrors.STATEMENT_MALFORMED, message);
    }

    /** Starts a declaration. */
    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Builds a {@link ContinuousQuery} one named part at a time. */
    public static final class Builder {

        private final String name;
        private @Nullable String sql;
        private List<String> keyColumns = List.of();
        private @Nullable Retention retention;
        private @Nullable String sink;

        private Builder(String name) {
            this.name = name;
        }

        public Builder sql(@Nullable String sql) {
            this.sql = sql;
            return this;
        }

        public Builder keyedBy(String... columns) {
            this.keyColumns = Arrays.asList(Objects.requireNonNull(columns, "columns"));
            return this;
        }

        public Builder keyedBy(List<String> columns) {
            this.keyColumns = columns;
            return this;
        }

        /** Forgets rows whose event time is older than {@code maxAge}. */
        public Builder retaining(Duration maxAge) {
            this.retention = Retention.ofAge(maxAge);
            return this;
        }

        public Builder retaining(Retention retention) {
            this.retention = retention;
            return this;
        }

        /** Also writes the query's changelog to the sink bound under {@code sinkName}. */
        public Builder writingTo(String sinkName) {
            this.sink = sinkName;
            return this;
        }

        @SuppressWarnings("NullAway") // a missing SQL is the constructor's to refuse, with PRV-2070
        public ContinuousQuery build() {
            return new ContinuousQuery(
                    name, sql, keyColumns, Optional.ofNullable(retention), Optional.ofNullable(sink));
        }
    }
}

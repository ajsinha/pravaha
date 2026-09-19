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
package com.ash.messaging.pravaha.sql;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * One of the statements that register and manage continuous queries, as {@link
 * ContinuousStatements} recognised it.
 *
 * <p>These are control statements, not questions: nothing here is planned by Calcite except the
 * {@code SELECT} a {@link Create} carries, which is planned exactly as a registration argument would
 * be. The grammar, and why it is the one it is, is in {@code docs/CONTINUOUS_QUERIES.md} section 3.
 */
public sealed interface ContinuousStatement
        permits ContinuousStatement.Create,
                ContinuousStatement.Drop,
                ContinuousStatement.Pause,
                ContinuousStatement.Resume,
                ContinuousStatement.Show {

    /** The statement's own words, for a message or an audit record: {@code CREATE CONTINUOUS QUERY}. */
    String verb();

    /**
     * How long the view keeps rows, in event time: an age, or for ever.
     *
     * @param age empty for {@code RETAIN FOREVER}
     */
    record Retain(Optional<Duration> age) {

        public Retain {
            Objects.requireNonNull(age, "age");
            age.ifPresent(value -> {
                if (value.isZero() || value.isNegative()) {
                    throw new IllegalArgumentException("a retention must be a positive age, not " + value);
                }
            });
        }

        public static Retain forever() {
            return new Retain(Optional.empty());
        }

        public static Retain of(Duration age) {
            return new Retain(Optional.of(age));
        }
    }

    /**
     * {@code CREATE CONTINUOUS QUERY name KEYED BY (...) [WRITING TO sink] [RETAIN ...] AS select}.
     *
     * @param keyColumns the key, by output column name; resolved to ordinals by {@link
     *     #keyOrdinals(StreamSchema)} once the {@code SELECT} has been planned
     * @param select the query itself, exactly as written between {@code AS} and the end (less any
     *     trailing {@code EMIT CHANGES} or semicolon), so the text a listing shows is the user's
     */
    record Create(
            String name,
            List<String> keyColumns,
            Optional<String> sink,
            Optional<Retain> retain,
            String select,
            boolean orReplace,
            java.util.Map<String, String> options)
            implements ContinuousStatement {

        public Create {
            Objects.requireNonNull(name, "name");
            keyColumns = List.copyOf(keyColumns);
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(retain, "retain");
            Objects.requireNonNull(select, "select");
            options = options == null ? java.util.Map.of() : java.util.Map.copyOf(options);
            if (!orReplace && !options.isEmpty()) {
                throw new IllegalArgumentException("only CREATE OR REPLACE takes options");
            }
        }

        /** A plain {@code CREATE}: no replacement, no options. */
        public Create(
                String name, List<String> keyColumns, Optional<String> sink, Optional<Retain> retain, String select) {
            this(name, keyColumns, sink, retain, select, false, java.util.Map.of());
        }

        @Override
        public String verb() {
            return orReplace ? "CREATE OR REPLACE CONTINUOUS QUERY" : "CREATE CONTINUOUS QUERY";
        }

        /**
         * The key, as the output ordinals the registry takes.
         *
         * <p>A name matches the column of exactly that name; failing that, the one column whose name
         * differs only in case, because an unquoted {@code KEYED BY (USER_ID)} over a column the
         * query calls {@code user_id} has one meaning and refusing it would be pedantry. Two
         * candidates differing only in case is ambiguous and refused.
         *
         * <p>The refusal does not list the query's columns. It is raised before the registry has
         * decided whether this principal may read what the query reads, and a list of columns
         * would describe a stream to somebody who may be about to be refused it.
         *
         * @throws PravahaException {@code PRV-2071} for a name the query does not produce, or one
         *     named twice
         */
        public List<Integer> keyOrdinals(StreamSchema output) {
            List<Integer> ordinals = new ArrayList<>();
            for (String column : keyColumns) {
                int ordinal = ordinalOf(output, column);
                if (ordinals.contains(ordinal)) {
                    throw new PravahaException(
                            SqlErrors.KEY_COLUMN_UNKNOWN,
                            "KEYED BY names '" + column + "' twice; a key names each column once.");
                }
                ordinals.add(ordinal);
            }
            return List.copyOf(ordinals);
        }

        private int ordinalOf(StreamSchema output, String column) {
            for (int ordinal = 0; ordinal < output.fieldCount(); ordinal++) {
                if (output.field(ordinal).name().equals(column)) {
                    return ordinal;
                }
            }
            int found = -1;
            for (int ordinal = 0; ordinal < output.fieldCount(); ordinal++) {
                if (output.field(ordinal).name().equalsIgnoreCase(column)) {
                    if (found >= 0) {
                        throw new PravahaException(
                                SqlErrors.KEY_COLUMN_UNKNOWN,
                                "KEYED BY names '" + column + "', and the query produces more than one column "
                                        + "by that name in different cases. Quote the one you mean, exactly as "
                                        + "the SELECT list spells it.");
                    }
                    found = ordinal;
                }
            }
            if (found < 0) {
                throw new PravahaException(
                        SqlErrors.KEY_COLUMN_UNKNOWN,
                        "KEYED BY names '" + column + "', which query '" + name + "' does not produce. A key "
                                + "column is named as the SELECT list names it -- by its alias where it has "
                                + "one, so SUM(amount) AS total is 'total'.");
            }
            return found;
        }
    }

    /** {@code DROP CONTINUOUS QUERY name}. */
    record Drop(String name) implements ContinuousStatement {
        public Drop {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "DROP CONTINUOUS QUERY";
        }
    }

    /** {@code PAUSE CONTINUOUS QUERY name}. */
    record Pause(String name) implements ContinuousStatement {
        public Pause {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "PAUSE CONTINUOUS QUERY";
        }
    }

    /** {@code RESUME CONTINUOUS QUERY name}. */
    record Resume(String name) implements ContinuousStatement {
        public Resume {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "RESUME CONTINUOUS QUERY";
        }
    }

    /** {@code SHOW CONTINUOUS QUERIES}. */
    record Show() implements ContinuousStatement {
        @Override
        public String verb() {
            return "SHOW CONTINUOUS QUERIES";
        }
    }
}

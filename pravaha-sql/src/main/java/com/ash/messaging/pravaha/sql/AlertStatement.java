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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A statement about an alert (ADR-057), as {@link AlertStatements} reads it. Nothing here is planned:
 * an alert watches a registered view, and its condition is a conjunction of comparisons of the view's
 * own columns with literals, judged against the view's schema when the statement runs.
 */
public sealed interface AlertStatement
        permits AlertStatement.Create,
                AlertStatement.Alter,
                AlertStatement.Drop,
                AlertStatement.Pause,
                AlertStatement.Resume,
                AlertStatement.Snooze,
                AlertStatement.Ack,
                AlertStatement.Show {

    /** The statement's own words, for a message or an audit record. */
    String verb();

    /**
     * One comparison of the condition: {@code column op literal}, or {@code column IS [NOT] NULL}.
     *
     * @param operator one of {@code = != < <= > >= IS_NULL IS_NOT_NULL}; {@code <>} is read as {@code !=}
     * @param literal the value as written: a string's contents, a number's digits, {@code TRUE} or
     *     {@code FALSE}; null for the two {@code IS} forms
     * @param quoted whether the literal was a string, so {@code '10'} and {@code 10} can be told apart
     */
    record Condition(
            String column, String operator, @Nullable String literal, boolean quoted) {
        public Condition {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(operator, "operator");
        }

        @Override
        public String toString() {
            return switch (operator) {
                case "IS_NULL" -> column + " IS NULL";
                case "IS_NOT_NULL" -> column + " IS NOT NULL";
                default ->
                    column + " " + operator + " "
                            + (quoted ? "'" + Objects.requireNonNull(literal).replace("'", "''") + "'" : literal);
            };
        }
    }

    /**
     * {@code CREATE ALERT [IF NOT EXISTS] name ON view [WHERE ...] NOTIFY channel [, channel]... [WITH
     * (...)]}.
     *
     * @param view the view as written: {@code name}, or {@code namespace.name} under the catalogue
     */
    record Create(
            String name,
            boolean ifNotExists,
            String view,
            List<Condition> where,
            List<String> channels,
            Map<String, String> options)
            implements AlertStatement {
        public Create {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(view, "view");
            where = List.copyOf(where);
            channels = List.copyOf(channels);
            options = Map.copyOf(options);
        }

        @Override
        public String verb() {
            return "CREATE ALERT";
        }
    }

    /**
     * {@code ALTER ALERT name SET (option = value, ...)} or {@code ALTER ALERT name NOTIFY channel, ...}.
     *
     * @param channels the new channels, or empty to keep them
     * @param options the options to change; the rest keep their values
     */
    record Alter(String name, List<String> channels, Map<String, String> options) implements AlertStatement {
        public Alter {
            Objects.requireNonNull(name, "name");
            channels = List.copyOf(channels);
            options = Map.copyOf(options);
        }

        @Override
        public String verb() {
            return "ALTER ALERT";
        }
    }

    /** {@code DROP ALERT [IF EXISTS] name}. */
    record Drop(String name, boolean ifExists) implements AlertStatement {
        public Drop {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "DROP ALERT";
        }
    }

    /** {@code PAUSE ALERT name}. */
    record Pause(String name) implements AlertStatement {
        public Pause {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "PAUSE ALERT";
        }
    }

    /** {@code RESUME ALERT name}: also ends a snooze. */
    record Resume(String name) implements AlertStatement {
        public Resume {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "RESUME ALERT";
        }
    }

    /** {@code SNOOZE ALERT name FOR duration}; the duration as written ({@code '1h'}, {@code PT1H}). */
    record Snooze(String name, String duration) implements AlertStatement {
        public Snooze {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(duration, "duration");
        }

        @Override
        public String verb() {
            return "SNOOZE ALERT";
        }
    }

    /** {@code ACK ALERT name}: acknowledges every key firing now, which stops their reminders. */
    record Ack(String name) implements AlertStatement {
        public Ack {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String verb() {
            return "ACK ALERT";
        }
    }

    /** {@code SHOW ALERTS}. */
    record Show() implements AlertStatement {
        @Override
        public String verb() {
            return "SHOW ALERTS";
        }
    }
}

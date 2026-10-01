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
 * be. The grammar, and why it is the one it is, is in {@code docs/guides/CONTINUOUS_QUERIES.md} section 3.
 */
public sealed interface ContinuousStatement
        permits ContinuousStatement.Create,
                ContinuousStatement.Drop,
                ContinuousStatement.Pause,
                ContinuousStatement.Resume,
                ContinuousStatement.Show,
                ContinuousStatement.Governance,
                ContinuousStatement.Alert {

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
     * {@code CREATE CONTINUOUS QUERY name KEYED BY (...) [RANGE (...)] [INDEX (...)] [WRITING TO
     * sink] [RETAIN ...] [WITH (...)] AS select}.
     *
     * @param keyColumns the key, by output column name; resolved to ordinals by {@link
     *     #keyOrdinals(StreamSchema)} once the {@code SELECT} has been planned. When {@code
     *     rangeColumn} is present it is the last of these, because that is what makes an ordered
     *     index over it useful: the columns before it are probed, and it is scanned between bounds
     * @param rangeColumn the key's ordered column, from {@code RANGE (column)} -- design section
     *     17.2's "ordered index over the key when declared {@code INDEXED BY RANGE}"
     * @param indexColumn the column an equality index is kept over, from {@code INDEX (column)}:
     *     value to keys, maintained in the view's own commit (ADR-055)
     * @param select the query itself, exactly as written between {@code AS} and the end (less any
     *     trailing {@code EMIT CHANGES} or semicolon), so the text a listing shows is the user's
     */
    record Create(
            String name,
            List<String> keyColumns,
            Optional<String> rangeColumn,
            Optional<String> indexColumn,
            Optional<String> sink,
            Optional<Retain> retain,
            String select,
            boolean orReplace,
            java.util.Map<String, String> options)
            implements ContinuousStatement {

        public Create {
            Objects.requireNonNull(name, "name");
            keyColumns = List.copyOf(keyColumns);
            Objects.requireNonNull(rangeColumn, "rangeColumn");
            Objects.requireNonNull(indexColumn, "indexColumn");
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(retain, "retain");
            Objects.requireNonNull(select, "select");
            // Insertion-ordered, so an option list reads back in the order it was written: the
            // order is what a refusal names first and what a listing would show.
            options = options == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(options));
            List<String> key = keyColumns;
            rangeColumn.ifPresent(column -> {
                if (key.isEmpty() || !key.get(key.size() - 1).equals(column)) {
                    throw new IllegalArgumentException(
                            "RANGE names '" + column + "', which must be the key's last column; the key is " + key);
                }
            });
        }

        /** A plain {@code CREATE}: no range index, no replacement, no options. */
        public Create(
                String name, List<String> keyColumns, Optional<String> sink, Optional<Retain> retain, String select) {
            this(name, keyColumns, Optional.empty(), Optional.empty(), sink, retain, select, false, java.util.Map.of());
        }

        /** This statement with its key taken from somewhere else -- a {@code WITH (keys = ...)} option. */
        public Create withKeyColumns(List<String> columns) {
            return new Create(name, columns, rangeColumn, indexColumn, sink, retain, select, orReplace, options);
        }

        /** This statement with the sink an option named. */
        public Create withSink(String sinkName) {
            return new Create(
                    name,
                    keyColumns,
                    rangeColumn,
                    indexColumn,
                    Optional.ofNullable(sinkName),
                    retain,
                    select,
                    orReplace,
                    options);
        }

        /** This statement with the equality index an option named. */
        public Create withIndexColumn(String column) {
            return new Create(
                    name,
                    keyColumns,
                    rangeColumn,
                    Optional.ofNullable(column),
                    sink,
                    retain,
                    select,
                    orReplace,
                    options);
        }

        @Override
        public String verb() {
            return orReplace ? "CREATE OR REPLACE CONTINUOUS QUERY" : "CREATE CONTINUOUS QUERY";
        }

        /**
         * The ordinal of the key column an ordered index is to be kept over, or empty when the
         * statement asked for none.
         *
         * <p>The type is checked here, against the columns the view will actually have rather than
         * against the text, because "this engine cannot order that column" is a fact about the
         * planned output and is worth knowing at registration rather than at the first range read.
         *
         * @throws PravahaException {@code PRV-2071} for a column the query does not produce;
         *     {@code PRV-2073} for one whose type this engine has no total order for
         */
        public Optional<Integer> rangeOrdinal(StreamSchema output) {
            if (rangeColumn.isEmpty()) {
                return Optional.empty();
            }
            int ordinal = ordinalOf(output, rangeColumn.get());
            com.ash.messaging.pravaha.api.data.TypeName type =
                    output.field(ordinal).type().typeName();
            if (!ORDERED_FOR_INDEX.contains(type)) {
                throw new PravahaException(
                        SqlErrors.RANGE_NOT_ORDERED,
                        "RANGE (" + rangeColumn.get() + ") asks for an ordered index over a " + type
                                + " column, and this engine has no total order for one. " + whyNot(type)
                                + " The key still works as a key -- point reads and full-key lookups are "
                                + "unaffected -- so drop the RANGE, or range-scan a column that is one of "
                                + ORDERED_FOR_INDEX + ".");
            }
            return Optional.of(ordinal);
        }

        private static String whyNot(com.ash.messaging.pravaha.api.data.TypeName type) {
            return switch (type) {
                case STRING ->
                    "Ordering text needs a collation, and guessing one gives wrong answers that look "
                            + "right -- which is why '<' and '>' on text are refused in a WHERE clause too.";
                case FLOAT32, FLOAT64 ->
                    "Floating-point comparison here is IEEE 754 (TY-3), under which NaN is neither less "
                            + "than, equal to nor greater than anything -- so there is no order for an index "
                            + "to be sorted in.";
                case DECIMAL ->
                    "A DECIMAL's compareTo disagrees with its equals: 1.0 and 1.00 compare equal and are "
                            + "not equal, so they would be one entry in the index and two rows in the view.";
                case BYTES -> "Bytes have no declared collation here, so there is no order to sort them in.";
                case BOOLEAN -> "There are two values; a range over them is the whole column or one of them.";
                default -> "";
            };
        }

        /**
         * The ordinal of the column an equality index is to be kept over, or empty when the
         * statement asked for none (ADR-055).
         *
         * <p>Checked against the columns the view will actually have, as {@link #rangeOrdinal} is,
         * so that an index this engine cannot keep is refused at registration rather than
         * discovered at the first read that wanted it.
         *
         * @throws PravahaException {@code PRV-2071} for a column the query does not produce;
         *     {@code PRV-2074} for one whose values this engine cannot compare by equality the way
         *     the filter does, or one that is the view's whole key
         */
        public Optional<Integer> indexOrdinal(StreamSchema output) {
            if (indexColumn.isEmpty()) {
                return Optional.empty();
            }
            String column = indexColumn.get();
            int ordinal = ordinalOf(output, column);
            com.ash.messaging.pravaha.api.data.TypeName type =
                    output.field(ordinal).type().typeName();
            if (!EQUATABLE_FOR_INDEX.contains(type)) {
                throw new PravahaException(
                        SqlErrors.INDEX_UNUSABLE,
                        "INDEX (" + column + ") asks for an equality index over a " + type + " column, and "
                                + "this engine keeps one only where two values the filter calls equal are "
                                + "the same stored value. " + whyNotEqual(type) + " Drop the INDEX, or index "
                                + "a column that is one of " + EQUATABLE_FOR_INDEX + ".");
            }
            List<Integer> key = keyOrdinals(output);
            if (key.size() == 1 && key.get(0) == ordinal) {
                throw new PravahaException(
                        SqlErrors.INDEX_UNUSABLE,
                        "INDEX (" + column + ") names the view's whole key. A lookup by the whole key is "
                                + "already a hash probe on every view, so a second structure over the same "
                                + "column would cost memory and answer nothing faster. Drop the INDEX.");
            }
            return Optional.of(ordinal);
        }

        private static String whyNotEqual(com.ash.messaging.pravaha.api.data.TypeName type) {
            return switch (type) {
                case FLOAT32, FLOAT64 ->
                    "Under IEEE 754, 0.0 and -0.0 are equal to the filter and different stored values, "
                            + "and NaN is equal to nothing, so an index would file one value under two "
                            + "entries.";
                case DECIMAL ->
                    "1.0 and 1.00 are equal to the filter and different stored values, so an index "
                            + "would find one of them and miss the other.";
                case BYTES -> "Bytes are compared by content and stored as arrays, which have no value equality.";
                default -> "A value of this type has no equality here that an index could be filed by.";
            };
        }

        /**
         * The types an equality index may be kept over: the ones whose stored value is equal
         * exactly when the filter says two values are, so a probe by the literal finds every row
         * the predicate keeps.
         */
        private static final java.util.Set<com.ash.messaging.pravaha.api.data.TypeName> EQUATABLE_FOR_INDEX =
                java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
                        com.ash.messaging.pravaha.api.data.TypeName.INT8,
                        com.ash.messaging.pravaha.api.data.TypeName.INT16,
                        com.ash.messaging.pravaha.api.data.TypeName.INT32,
                        com.ash.messaging.pravaha.api.data.TypeName.INT64,
                        com.ash.messaging.pravaha.api.data.TypeName.DATE,
                        com.ash.messaging.pravaha.api.data.TypeName.TIME,
                        com.ash.messaging.pravaha.api.data.TypeName.TIMESTAMP_LTZ,
                        com.ash.messaging.pravaha.api.data.TypeName.STRING,
                        com.ash.messaging.pravaha.api.data.TypeName.BOOLEAN));

        /**
         * The types an ordered index may be kept over: whole numbers and the temporal types, whose
         * order is the one every reader expects and is the same order the engine's own comparisons
         * use.
         */
        private static final java.util.Set<com.ash.messaging.pravaha.api.data.TypeName> ORDERED_FOR_INDEX =
                java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
                        com.ash.messaging.pravaha.api.data.TypeName.INT8,
                        com.ash.messaging.pravaha.api.data.TypeName.INT16,
                        com.ash.messaging.pravaha.api.data.TypeName.INT32,
                        com.ash.messaging.pravaha.api.data.TypeName.INT64,
                        com.ash.messaging.pravaha.api.data.TypeName.DATE,
                        com.ash.messaging.pravaha.api.data.TypeName.TIME,
                        com.ash.messaging.pravaha.api.data.TypeName.TIMESTAMP_LTZ));

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

    /**
     * A statement that reads or changes the catalogue (ADR-059): {@code GRANT}, {@code REVOKE},
     * {@code CREATE NAMESPACE}, {@code COMMENT ON}, {@code ALTER ... SET TAGS | OWNER TO}, {@code SHOW
     * GRANTS} and the rest, parsed by {@link com.ash.messaging.pravaha.catalog.CatalogStatements}. Carried
     * here so that every surface that runs these statements runs those too, through one door.
     */
    record Governance(com.ash.messaging.pravaha.catalog.CatalogStatement statement) implements ContinuousStatement {
        public Governance {
            Objects.requireNonNull(statement, "statement");
        }

        @Override
        public String verb() {
            return statement.verb();
        }
    }

    /**
     * A statement about an alert (ADR-057): {@code CREATE ALERT}, {@code ALTER ALERT}, {@code DROP
     * ALERT}, {@code PAUSE}, {@code RESUME}, {@code SNOOZE}, {@code ACK ALERT} and {@code SHOW ALERTS},
     * read by {@link AlertStatements}. Carried here so every surface that runs these statements runs
     * those too, through one door.
     */
    record Alert(AlertStatement statement) implements ContinuousStatement {
        public Alert {
            Objects.requireNonNull(statement, "statement");
        }

        @Override
        public String verb() {
            return statement.verb();
        }
    }
}

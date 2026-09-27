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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.plugin.ReadRequest;

/**
 * Which of a query's filters Cassandra can apply itself without {@code ALLOW FILTERING}, and the CQL
 * that applies them.
 *
 * <p>CQL answers two shapes without reading every partition. <strong>Equality on the whole
 * partition key</strong> names partitions, so Cassandra reads only those; an OR of such keys -- what a
 * reader shared by several queries is asked for, one key per query -- is several of them.
 * <strong>Given that, restrictions on the clustering columns</strong>, in their declared order:
 * equality on a prefix, then at most a range on the next one, which Cassandra answers by slicing the
 * partition. Anything else -- a filter on a regular column, a partition key pinned only in part, a
 * clustering restriction out of order -- is left with the engine, as it was: CQL would need {@code
 * ALLOW FILTERING}, which reads every partition anyway.
 *
 * <p><strong>Wider, never narrower.</strong> The engine applies every filter itself whatever a
 * source does, so a pushed restriction may return rows the query does not want and must never omit
 * one it does. Where a value cannot be carried exactly it is not pushed: a number outside the
 * column's CQL range, a non-ASCII string for an {@code ascii} column, a column whose CQL type is not
 * one of the few below. A {@code timestamp} bound is widened to the millisecond Cassandra stores, and
 * an equality on one only pushed when it is a whole millisecond. Text is pushed by equality only: a
 * range would need a collation, and the engine's planner does not produce one anyway.
 *
 * <p>Pure: the column types come from the table's metadata, read by the plugin when it opens, so
 * every decision here is testable without a server.
 */
final class CassandraPushdown {

    /** At most this many partitions are read by key; an OR of more reads the table as it did. */
    static final int MAX_READS = 256;

    /** The CQL types a restriction is pushed on, and how a filter's value is carried to each. */
    enum ColumnType {
        TINYINT(true),
        SMALLINT(true),
        INT(true),
        BIGINT(true),
        TIMESTAMP(true),
        TEXT(false),
        ASCII(false),
        BOOLEAN(false);

        /** Whether a range on the column means the same to Cassandra as to the engine. */
        final boolean ordered;

        ColumnType(boolean ordered) {
            this.ordered = ordered;
        }
    }

    /** One CQL relation: {@code column op ?}, with the value bound to it. */
    record Restriction(String column, String op, Object value) {}

    /** One partition read: the partition key by equality, then any clustering restrictions. */
    record KeyRead(List<Restriction> restrictions) {

        /** {@code " WHERE a = ? AND b = ? AND c >= ?"}. */
        String where() {
            StringBuilder cql = new StringBuilder(" WHERE ");
            for (int i = 0; i < restrictions.size(); i++) {
                if (i > 0) {
                    cql.append(" AND ");
                }
                cql.append(restrictions.get(i).column())
                        .append(' ')
                        .append(restrictions.get(i).op())
                        .append(" ?");
            }
            return cql.toString();
        }

        Object[] values() {
            return restrictions.stream().map(Restriction::value).toArray();
        }
    }

    /**
     * What a reader asks Cassandra for.
     *
     * @param reads the partitions to read, or null when the table is scanned by token range as before;
     *     empty when the filters cannot all hold, so no partition can match
     * @param description in words, for the query's feed description
     */
    record Plan(List<KeyRead> reads, String description) {

        boolean pushed() {
            return reads != null;
        }
    }

    private CassandraPushdown() {}

    /**
     * @param partitionKey the table's partition key columns, in CQL order
     * @param clustering the table's clustering columns, in CQL order
     * @param types the CQL type of every column a restriction may be pushed on; a column not here is
     *     never pushed
     * @param maxReads the most partition reads the plan may name before it scans instead
     */
    static Plan plan(
            ReadRequest request,
            List<String> partitionKey,
            List<String> clustering,
            Map<String, ColumnType> types,
            int maxReads) {
        if (request == null
                || (request.filters().isEmpty() && request.alternatives().isEmpty())) {
            return scan("no filter was offered");
        }
        List<List<ReadRequest.Filter>> conjunctions = new ArrayList<>();
        if (request.alternatives().isEmpty()) {
            conjunctions.add(request.filters());
        } else {
            for (List<ReadRequest.Filter> alternative : request.alternatives()) {
                List<ReadRequest.Filter> all = new ArrayList<>(request.filters());
                all.addAll(alternative);
                conjunctions.add(all);
            }
        }
        Set<KeyRead> reads = new LinkedHashSet<>();
        for (List<ReadRequest.Filter> conjunction : conjunctions) {
            Map<String, List<Object>> equal = new LinkedHashMap<>();
            Map<String, List<ReadRequest.Filter>> ranges = new LinkedHashMap<>();
            for (ReadRequest.Filter filter : conjunction) {
                ColumnType type = types.get(filter.column());
                if (type == null) {
                    continue;
                }
                if (filter.comparison() == ReadRequest.Comparison.EQ) {
                    Object value = exact(type, filter.value());
                    if (value != null) {
                        equal.computeIfAbsent(filter.column(), c -> new ArrayList<>())
                                .add(value);
                    }
                } else if (type.ordered && isRange(filter.comparison())) {
                    ranges.computeIfAbsent(filter.column(), c -> new ArrayList<>())
                            .add(filter);
                }
            }
            List<Restriction> restrictions = new ArrayList<>();
            boolean satisfiable = true;
            for (String column : partitionKey) {
                List<Object> values = equal.get(column);
                if (values == null) {
                    return scan("the filters do not pin the whole partition key " + partitionKey + " by equality"
                            + (conjunctions.size() > 1 ? " in every alternative" : ""));
                }
                satisfiable &= values.stream().distinct().count() == 1;
                restrictions.add(new Restriction(column, "=", values.get(0)));
            }
            satisfiable &= clusteringInto(restrictions, clustering, types, equal, ranges);
            if (satisfiable) {
                reads.add(new KeyRead(List.copyOf(restrictions)));
            }
            if (reads.size() > maxReads) {
                return scan("the filters name more than " + maxReads + " partitions");
            }
        }
        return new Plan(List.copyOf(reads), describe(List.copyOf(reads), partitionKey));
    }

    /**
     * Adds the clustering restrictions: equality down a prefix, then the range on the next column.
     *
     * @return false when they cannot all hold -- two different equal values, an empty range
     */
    private static boolean clusteringInto(
            List<Restriction> restrictions,
            List<String> clustering,
            Map<String, ColumnType> types,
            Map<String, List<Object>> equal,
            Map<String, List<ReadRequest.Filter>> ranges) {
        for (String column : clustering) {
            List<Object> values = equal.get(column);
            if (values != null) {
                restrictions.add(new Restriction(column, "=", values.get(0)));
                if (values.stream().distinct().count() > 1) {
                    return false;
                }
                continue;
            }
            List<ReadRequest.Filter> bounds = ranges.get(column);
            if (bounds != null) {
                return rangeInto(restrictions, column, types.get(column), bounds);
            }
            // CQL restricts a clustering column only when every one before it is pinned by equality.
            return true;
        }
        return true;
    }

    /** The tightest lower and upper bound on one column, widened where the column is coarser. */
    private static boolean rangeInto(
            List<Restriction> restrictions, String column, ColumnType type, List<ReadRequest.Filter> bounds) {
        Restriction lower = null;
        Restriction upper = null;
        for (ReadRequest.Filter filter : bounds) {
            boolean isLower = filter.comparison() == ReadRequest.Comparison.GT
                    || filter.comparison() == ReadRequest.Comparison.GE;
            Restriction candidate = bound(type, column, filter.comparison(), filter.value(), isLower);
            if (candidate == null) {
                continue;
            }
            if (isLower) {
                lower = lower == null || tighter(candidate, lower, true) ? candidate : lower;
            } else {
                upper = upper == null || tighter(candidate, upper, false) ? candidate : upper;
            }
        }
        if (lower != null && upper != null) {
            int order = compare(lower.value(), upper.value());
            boolean strict = lower.op().equals(">") || upper.op().equals("<");
            if (order > 0 || (order == 0 && strict)) {
                return false;
            }
        }
        if (lower != null) {
            restrictions.add(lower);
        }
        if (upper != null) {
            restrictions.add(upper);
        }
        return true;
    }

    /** One bound as CQL can carry it, or null when it cannot be carried without narrowing. */
    private static Restriction bound(
            ColumnType type, String column, ReadRequest.Comparison comparison, Object value, boolean isLower) {
        String op = comparison == ReadRequest.Comparison.GT
                ? ">"
                : comparison == ReadRequest.Comparison.GE ? ">=" : comparison == ReadRequest.Comparison.LT ? "<" : "<=";
        if (type == ColumnType.TIMESTAMP) {
            if (!(value instanceof Long nanos)) {
                return null;
            }
            // Cassandra keeps milliseconds. A bound on the millisecond at or outside the engine's is
            // at least as wide, and inclusive so a row on that millisecond is never lost.
            long millis = isLower ? Math.floorDiv(nanos, 1_000_000L) : Math.floorDiv(nanos + 999_999L, 1_000_000L);
            return new Restriction(column, isLower ? ">=" : "<=", Instant.ofEpochMilli(millis));
        }
        Object carried = exact(type, value);
        return carried == null ? null : new Restriction(column, op, carried);
    }

    private static boolean tighter(Restriction candidate, Restriction current, boolean lower) {
        int order = compare(candidate.value(), current.value());
        if (order == 0) {
            // x > v is tighter than x >= v, and x < v than x <= v.
            return candidate.op().length() == 1;
        }
        return lower ? order > 0 : order < 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) {
        return ((Comparable) left).compareTo(right);
    }

    /** The filter's value as the driver binds it to a column of {@code type}, or null when it cannot be exact. */
    static Object exact(ColumnType type, Object value) {
        switch (type) {
            case TINYINT, SMALLINT, INT, BIGINT -> {
                if (!(value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long)) {
                    return null;
                }
                long number = ((Number) value).longValue();
                return switch (type) {
                    case TINYINT -> number == (byte) number ? Byte.valueOf((byte) number) : null;
                    case SMALLINT -> number == (short) number ? Short.valueOf((short) number) : null;
                    case INT -> number == (int) number ? Integer.valueOf((int) number) : null;
                    default -> Long.valueOf(number);
                };
            }
            case TEXT -> {
                return value instanceof String text ? text : null;
            }
            case ASCII -> {
                return value instanceof String text && text.chars().allMatch(c -> c < 128) ? text : null;
            }
            case BOOLEAN -> {
                return value instanceof Boolean flag ? flag : null;
            }
            case TIMESTAMP -> {
                // The engine keeps nanoseconds and Cassandra milliseconds: only a whole millisecond
                // can be equal to anything Cassandra holds.
                return value instanceof Long nanos && nanos % 1_000_000L == 0
                        ? Instant.ofEpochMilli(nanos / 1_000_000L)
                        : null;
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean isRange(ReadRequest.Comparison comparison) {
        return comparison == ReadRequest.Comparison.GT
                || comparison == ReadRequest.Comparison.GE
                || comparison == ReadRequest.Comparison.LT
                || comparison == ReadRequest.Comparison.LE;
    }

    private static Plan scan(String why) {
        return new Plan(null, "no filter pushed to Cassandra: " + why + ", which CQL would need ALLOW FILTERING for");
    }

    /** "partition key id = 7, clustering day = 3 and ts >= ..." or "4 partitions by key (id)". */
    private static String describe(List<KeyRead> reads, List<String> partitionKey) {
        if (reads.isEmpty()) {
            return "pushed to Cassandra: no partition, because the filters cannot all hold";
        }
        if (reads.size() == 1) {
            List<Restriction> restrictions = reads.get(0).restrictions();
            StringBuilder text = new StringBuilder("pushed to Cassandra: partition key ");
            text.append(render(restrictions.subList(0, partitionKey.size())));
            if (restrictions.size() > partitionKey.size()) {
                text.append(", clustering ")
                        .append(render(restrictions.subList(partitionKey.size(), restrictions.size())));
            }
            return text.toString();
        }
        boolean clustered = reads.stream().anyMatch(read -> read.restrictions().size() > partitionKey.size());
        return "pushed to Cassandra: " + reads.size() + " partitions by key " + partitionKey
                + (clustered ? ", with clustering restrictions" : "");
    }

    private static String render(List<Restriction> restrictions) {
        List<String> parts = new ArrayList<>();
        for (Restriction restriction : restrictions) {
            Object value = restriction.value();
            String shown = value instanceof String text ? "'" + text + "'" : String.valueOf(value);
            parts.add(restriction.column() + " " + restriction.op() + " " + shown);
        }
        return String.join(" and ", parts);
    }
}

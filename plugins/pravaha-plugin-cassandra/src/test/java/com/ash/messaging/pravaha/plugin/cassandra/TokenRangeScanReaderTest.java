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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.cql.Row;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CASS-1: a token-range reader stopped part way through a partition does not skip the rest of it.
 *
 * <p>A wide partition is several clustering rows under one token. The reader's offset is the last
 * token it consumed, and it resumed with {@code token(pk) > token}: stopped after the first of a
 * partition's rows -- a checkpoint, a restart, a page that failed -- it went on from the next
 * partition, and the rest of that one waited for the next full pass. It now resumes with {@code >=},
 * re-reading the partition from its first row, which every pass of this reader does to every row.
 */
class TokenRangeScanReaderTest {

    private static final StreamSchema SCHEMA = CassandraSchemas.parse("events", "id:INT64,seq:INT64");

    /** Token to the partition's rows in clustering order. */
    private final TreeMap<Long, List<Map<String, Object>>> table = new TreeMap<>();

    /** How each pass was opened: its floor and whether it was inclusive. */
    private final List<String> opened = new ArrayList<>();

    /** Rows a pass hands over before its next page fails, or -1 for none. */
    private int failAfter = -1;

    TokenRangeScanReaderTest() {
        put(10, 1, 1);
        put(20, 2, 1);
        put(20, 2, 2);
        put(20, 2, 3);
        put(30, 3, 1);
    }

    @Test
    void aReaderResumedFromTheMiddleOfAWidePartitionReadsTheRestOfIt() {
        TokenRangeScanReader first = reader(SourceOffset.BEGINNING);
        WeightedCollector out = new WeightedCollector(SCHEMA);
        assertThat(first.poll(out, 2)).isEqualTo(2);
        SourceOffset stoppedAt = first.position();
        assertThat(stoppedAt.token()).isEqualTo("token=20");

        TokenRangeScanReader resumed = reader(stoppedAt);
        WeightedCollector rest = new WeightedCollector(SCHEMA);
        resumed.poll(rest, 100);

        assertThat(opened.get(1)).as("the partition it stopped in, again").isEqualTo(">=20");
        Set<List<Object>> seen = new HashSet<>();
        out.rows.forEach(row -> seen.add(row.values()));
        rest.rows.forEach(row -> seen.add(row.values()));
        assertThat(seen)
                .as("every row of the pass, the partition it stopped in included")
                .containsExactlyInAnyOrder(
                        List.of(1L, 1L), List.of(2L, 1L), List.of(2L, 2L), List.of(2L, 3L), List.of(3L, 1L));
    }

    @Test
    void aPageThatFailsPartWayThroughAPartitionIsResumedFromThatPartition() {
        TokenRangeScanReader reader = reader(SourceOffset.BEGINNING);
        WeightedCollector out = new WeightedCollector(SCHEMA);
        failAfter = 2;
        assertThatThrownBy(() -> reader.poll(out, 100)).hasMessageContaining("failed while paging");

        reader.poll(out, 100);

        assertThat(opened).containsExactly(">=" + Long.MIN_VALUE, ">=20");
        assertThat(out.rows)
                .extracting(WeightedCollector.Emitted::values)
                .contains(List.of(2L, 2L), List.of(2L, 3L), List.of(3L, 1L));
    }

    @Test
    void aFreshPassStartsFromTheRangesOwnBound() {
        reader(SourceOffset.BEGINNING).poll(new WeightedCollector(SCHEMA), 100);
        assertThat(opened).containsExactly(">=" + Long.MIN_VALUE);
    }

    private TokenRangeScanReader reader(@Nullable SourceOffset from) {
        return new TokenRangeScanReader(
                this::pass, new boolean[] {true, true}, SCHEMA, "", Long.MIN_VALUE, Long.MAX_VALUE, true, 0, from);
    }

    private void put(long token, long id, long seq) {
        table.computeIfAbsent(token, t -> new ArrayList<>())
                .add(Map.of("id", id, "seq", seq, CassandraSourcePlugin.TOKEN_ALIAS, token));
    }

    private Iterator<Row> pass(long floor, boolean inclusive) {
        opened.add((inclusive ? ">=" : ">") + floor);
        List<Row> rows = new ArrayList<>();
        (inclusive ? table.tailMap(floor, true) : table.tailMap(floor, false))
                .values()
                .forEach(each -> each.forEach(values -> rows.add(row(values))));
        int failing = failAfter;
        failAfter = -1;
        Iterator<Row> all = rows.iterator();
        return new Iterator<>() {
            private int handed;

            @Override
            public boolean hasNext() {
                if (failing >= 0 && handed == failing) {
                    throw new DriverTimeoutException("the next page timed out");
                }
                return all.hasNext();
            }

            @Override
            public Row next() {
                handed++;
                return all.next();
            }
        };
    }

    private static Row row(Map<String, Object> values) {
        return (Row) Proxy.newProxyInstance(
                Row.class.getClassLoader(), new Class<?>[] {Row.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isNull") && args[0] instanceof String name) {
                        return values.get(name) == null;
                    }
                    if (method.getName().startsWith("get")
                            && args != null
                            && args.length == 1
                            && args[0] instanceof String name) {
                        return values.get(name);
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }
}

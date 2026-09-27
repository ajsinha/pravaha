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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the partitions a filter names: each read's rows, kept to this reader's token range, a
 * pass per interval, and a position that always restarts the pass.
 */
class KeyedScanReaderTest {

    private static final StreamSchema SCHEMA = CassandraSchemas.parse("orders", "id:INT64,status:STRING");

    private static final CassandraPushdown.KeyRead ONE =
            new CassandraPushdown.KeyRead(List.of(new CassandraPushdown.Restriction("id", "=", 1L)));

    private static final CassandraPushdown.KeyRead TWO =
            new CassandraPushdown.KeyRead(List.of(new CassandraPushdown.Restriction("id", "=", 2L)));

    /** id to token and the rows under it. */
    private final Map<Long, List<Row>> partitions = new HashMap<>();

    private final List<Object> asked = new ArrayList<>();

    private void put(long id, long token, String status) {
        partitions
                .computeIfAbsent(id, k -> new ArrayList<>())
                .add(row(Map.of("id", id, "status", status, CassandraSourcePlugin.TOKEN_ALIAS, token)));
    }

    private Iterator<Row> run(CassandraPushdown.KeyRead read) {
        Object id = read.values()[0];
        asked.add(id);
        return partitions.getOrDefault((Long) id, List.of()).iterator();
    }

    private KeyedScanReader reader(long lower, long upper, boolean inclusiveLower, int interval, SourceOffset from) {
        return new KeyedScanReader(
                List.of(ONE, TWO),
                this::run,
                new boolean[] {true, true},
                SCHEMA,
                "",
                lower,
                upper,
                inclusiveLower,
                interval,
                from);
    }

    @Test
    void eachReadsRowsAreKeptToTheReadersOwnTokenRange() {
        put(1, 10, "a");
        put(1, 10, "b");
        put(2, 20, "c");

        try (CassandraCollector low = new CassandraCollector(SCHEMA);
                CassandraCollector high = new CassandraCollector(SCHEMA)) {
            KeyedScanReader first = reader(Long.MIN_VALUE, 10, true, 60_000, null);
            KeyedScanReader second = reader(10, Long.MAX_VALUE, false, 60_000, SourceOffset.BEGINNING);

            assertThat(first.poll(low, 100)).isEqualTo(2);
            assertThat(second.poll(high, 100)).isEqualTo(1);
            assertThat(first.poll(low, 100)).as("the pass is over").isZero();

            assertThat(low.rows()).extracting(row -> row.getString(1)).containsExactly("a", "b");
            assertThat(high.rows()).extracting(row -> row.getString(1)).containsExactly("c");
            assertThat(asked).as("every reader runs every read").containsExactly(1L, 2L, 1L, 2L);
            assertThat(first.position()).isEqualTo(SourceOffset.BEGINNING);
            assertThat(first.passCount()).isEqualTo(1);
            first.close();
        }
    }

    @Test
    void maxRecordsSplitsAPassAndTheNextPassWaitsForTheInterval() {
        put(1, 10, "a");
        put(2, 20, "b");

        try (CassandraCollector out = new CassandraCollector(SCHEMA)) {
            KeyedScanReader reader = reader(Long.MIN_VALUE, Long.MAX_VALUE, true, 60_000, null);
            assertThat(reader.poll(out, 1)).isEqualTo(1);
            assertThat(reader.poll(out, 1)).isEqualTo(1);
            assertThat(reader.poll(out, 1)).as("the end of the pass").isZero();
            assertThat(reader.poll(out, 10)).as("too soon for another").isZero();
            assertThat(out.rows()).hasSize(2);

            KeyedScanReader eager = reader(Long.MIN_VALUE, Long.MAX_VALUE, true, 0, null);
            eager.poll(out, 10);
            eager.poll(out, 10);
            assertThat(eager.poll(out, 10))
                    .as("no interval: the next pass at once")
                    .isEqualTo(2);
        }
    }

    @Test
    void pausedReadsNothing() {
        put(1, 10, "a");
        try (CassandraCollector out = new CassandraCollector(SCHEMA)) {
            KeyedScanReader reader = reader(Long.MIN_VALUE, Long.MAX_VALUE, true, 0, null);
            reader.pause();
            assertThat(reader.poll(out, 10)).isZero();
            reader.resume();
            assertThat(reader.poll(out, 10)).isEqualTo(1);
        }
    }

    @Test
    void aTokenPositionRestartsThePassAndAnotherPluginsOffsetIsRefused() {
        put(1, 10, "a");
        try (CassandraCollector out = new CassandraCollector(SCHEMA)) {
            KeyedScanReader resumed = reader(Long.MIN_VALUE, Long.MAX_VALUE, true, 0, new SourceOffset("token=99"));
            assertThat(resumed.poll(out, 10))
                    .as("from the start of the pass, not past token 99")
                    .isEqualTo(1);
        }
        assertThatThrownBy(() -> reader(0, 1, true, 0, new SourceOffset("orders/3@4")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5089");
        assertThatThrownBy(() -> reader(0, 1, true, 0, new SourceOffset("deletes=r/3")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("deletes: detect");
    }

    @Test
    void aReadThatFailsIsNamedAndTheNextPollStartsAgain() {
        put(1, 10, "a");
        boolean[] fail = {true};
        KeyedScanReader reader = new KeyedScanReader(
                List.of(ONE),
                read -> {
                    if (fail[0]) {
                        fail[0] = false;
                        throw new DriverTimeoutException("read timed out");
                    }
                    return run(read);
                },
                new boolean[] {true, true},
                SCHEMA,
                "",
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                true,
                0,
                null);
        try (CassandraCollector out = new CassandraCollector(SCHEMA)) {
            assertThatThrownBy(() -> reader.poll(out, 10))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5086")
                    .hasMessageContaining("read timed out");
            assertThat(reader.poll(out, 10)).isEqualTo(1);
        }
    }

    @Test
    void aPageThatFailsMidReadIsNamed() {
        Iterator<Row> failing = new Iterator<>() {
            @Override
            public boolean hasNext() {
                throw new DriverTimeoutException("the next page timed out");
            }

            @Override
            public Row next() {
                throw new AssertionError();
            }
        };
        KeyedScanReader reader = new KeyedScanReader(
                List.of(ONE),
                read -> failing,
                new boolean[] {true, true},
                SCHEMA,
                "",
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                true,
                0,
                null);
        try (CassandraCollector out = new CassandraCollector(SCHEMA)) {
            assertThatThrownBy(() -> reader.poll(out, 10))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5086")
                    .hasMessageContaining("the next page timed out");
        }
    }

    @Test
    void theRangeFilterIsAnIteratorOfItsOwn() {
        put(1, 10, "a");
        Iterator<Row> rows = KeyedScanReader.inRange(List.of(ONE, TWO), this::run, 0, 100, false, "(0, 100]");
        assertThat(rows.hasNext()).isTrue();
        assertThat(rows.next().getString("status")).isEqualTo("a");
        assertThat(rows.hasNext()).isFalse();
        assertThatThrownBy(rows::next).isInstanceOf(java.util.NoSuchElementException.class);
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

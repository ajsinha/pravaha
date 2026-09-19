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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.cql.Row;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code deletes: detect} over a token range, against rows handed over in token order as the driver
 * would: inserts, updates, deletes and re-inserts; several rows under one token; a failure part way
 * through a pass; restarts between passes, mid-pass and behind later rows; the ceiling; and seeded
 * random histories in which the emitted rows must always sum to the table.
 */
class DetectingTokenRangeReaderTest {

    private static final StreamSchema SCHEMA = CassandraSchemas.parse("orders", "id:INT64,status:STRING,amount:INT64");

    @TempDir
    Path stateDir;

    /** Token to the rows under it, in token order: the order a token-range pass returns them in. */
    private final TreeMap<Long, List<Map<String, Object>>> table = new TreeMap<>();

    /** Rows a pass hands over before it fails, or -1 for a pass that does not. */
    private int failAfter = -1;

    private void put(long token, long id, String status, long amount) {
        List<Map<String, Object>> rows = table.computeIfAbsent(token, t -> new ArrayList<>());
        rows.removeIf(row -> row.get("id").equals(id));
        rows.add(Map.of("id", id, "status", status, "amount", amount, CassandraSourcePlugin.TOKEN_ALIAS, token));
    }

    private void delete(long token, long id) {
        List<Map<String, Object>> rows = table.get(token);
        rows.removeIf(row -> row.get("id").equals(id));
        if (rows.isEmpty()) {
            table.remove(token);
        }
    }

    private Iterator<Row> pass() {
        List<Row> rows = new ArrayList<>();
        table.values().forEach(each -> each.forEach(values -> rows.add(row(values))));
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

    private DetectingTokenRangeReader reader(SourceOffset from) {
        return reader(from, 1_000_000);
    }

    private DetectingTokenRangeReader reader(SourceOffset from, long maxKeys) {
        return new DetectingTokenRangeReader(
                this::pass,
                new boolean[] {true, true, true},
                SCHEMA,
                "",
                "[" + Long.MIN_VALUE + "," + Long.MAX_VALUE + "]",
                3,
                0,
                from,
                stateDir,
                maxKeys);
    }

    private Map<List<Object>, Long> tableAsView() {
        Map<List<Object>, Long> view = new HashMap<>();
        table.values()
                .forEach(rows -> rows.forEach(values -> view.merge(
                        java.util.Arrays.asList(values.get("id"), values.get("status"), values.get("amount")),
                        1L,
                        Long::sum)));
        return view;
    }

    private static List<Object> row(long id, String status, long amount) {
        return java.util.Arrays.asList(id, status, amount);
    }

    @Test
    void aPassEmitsOnlyWhatChangedAndAPassThatFindsNothingNewEmitsNothing() {
        put(-50, 1, "NEW", 10);
        put(7, 2, "NEW", 20);
        put(90, 3, "NEW", 30);
        try (DetectingTokenRangeReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            assertThat(out.rows).hasSize(3).allMatch(e -> e.weight() == 1L);
            out.clearRows();
            out.drain(reader);
            assertThat(out.rows)
                    .as("a plain pass would add all three again; this one adds nothing")
                    .isEmpty();

            put(7, 2, "SHIPPED", 25);
            delete(-50, 1);
            put(12, 4, "NEW", 40);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(
                            Tuple.tuple(row(1, "NEW", 10), -1L),
                            Tuple.tuple(row(2, "NEW", 20), -1L),
                            Tuple.tuple(row(2, "SHIPPED", 25), 1L),
                            Tuple.tuple(row(4, "NEW", 40), 1L));
            assertThat(out.view).isEqualTo(tableAsView());

            out.clearRows();
            delete(90, 3);
            put(90, 3, "AGAIN", 33);
            put(-50, 1, "BACK", 11);
            out.drain(reader);
            assertThat(out.view).isEqualTo(tableAsView());
        }
    }

    @Test
    void rowsSharingATokenAreComparedAsAMultiset() {
        put(5, 1, "NEW", 10);
        put(5, 2, "NEW", 20);
        put(5, 3, "NEW", 30);
        try (DetectingTokenRangeReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            out.clearRows();
            delete(5, 2);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(Tuple.tuple(row(2, "NEW", 20), -1L));
            assertThat(out.view).isEqualTo(tableAsView());
        }
    }

    @Test
    void aPassThatFailsPartWayRetractsNothingItDidNotReach() {
        for (long id = 1; id <= 6; id++) {
            put(id * 10, id, "NEW", id);
        }
        try (DetectingTokenRangeReader reader = reader(null)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            out.clearRows();
            failAfter = 2;
            assertThatThrownBy(() -> out.drain(reader))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e ->
                            assertThat(((PravahaException) e).errorCode()).isEqualTo(CassandraErrors.OPERATION_FAILED));
            assertThat(out.rows)
                    .as("four rows the failed pass never reached are not four deletes")
                    .isEmpty();
            delete(60, 6);
            out.drain(reader);
            assertThat(out.rows).extracting(WeightedCollector.Emitted::weight).containsExactly(-1L);
            assertThat(out.view).isEqualTo(tableAsView());
        }
    }

    @Test
    void restartsMidPassBetweenPassesAndBehindLaterRowsLeaveTheViewEqualToTheTable() {
        for (long id = 1; id <= 8; id++) {
            put(id * 100, id, "NEW", id);
        }
        WeightedCollector out = new WeightedCollector(SCHEMA);
        SourceOffset midPass;
        try (DetectingTokenRangeReader reader = reader(null)) {
            assertThat(reader.poll(out, 3)).as("part of the first pass").isBetween(1, 3);
            midPass = reader.position();
        }
        delete(100, 1);
        delete(800, 8);

        WeightedCollector resumed = WeightedCollector.restoredFrom(SCHEMA, out.view);
        SourceOffset betweenPasses;
        Map<List<Object>, Long> viewBetweenPasses;
        try (DetectingTokenRangeReader reader = reader(midPass)) {
            resumed.drain(reader);
            assertThat(resumed.view).isEqualTo(tableAsView());
            betweenPasses = reader.position();
            viewBetweenPasses = new HashMap<>(resumed.view);
            delete(200, 2); // retracted after the checkpoint, then the process dies
            resumed.drain(reader);
            reader.position();
        }
        assertThat(resumed.rows.stream().filter(e -> e.weight() < 0).count())
                .as("of 1 and 8 only 1 had been emitted before the checkpoint; the restored reader must retract "
                        + "it and not invent a retraction of 8, then retract 2 when it goes")
                .isEqualTo(2);

        delete(300, 3);
        WeightedCollector again = WeightedCollector.restoredFrom(SCHEMA, viewBetweenPasses);
        try (DetectingTokenRangeReader reader = reader(betweenPasses)) {
            again.drain(reader);
        }
        assertThat(again.rows)
                .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                .containsExactly(Tuple.tuple(row(2, "NEW", 2), -1L), Tuple.tuple(row(3, "NEW", 3), -1L));
        assertThat(again.view).isEqualTo(tableAsView());
    }

    @Test
    void theCeilingRefusesByCodeDuringAPassAndAtRestore() {
        put(1, 1, "NEW", 1);
        put(2, 2, "NEW", 2);
        SourceOffset checkpoint;
        try (DetectingTokenRangeReader reader = reader(null, 2)) {
            WeightedCollector out = new WeightedCollector(SCHEMA).drain(reader);
            checkpoint = reader.position();
            out.clearRows();
            put(3, 3, "NEW", 3);
            assertThatThrownBy(() -> out.drain(reader))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e -> assertThat(((PravahaException) e).errorCode())
                            .isEqualTo(CassandraErrors.DELETE_STATE_FULL));
            assertThat(out.rows).isEmpty();
        }
        assertThatThrownBy(() -> reader(checkpoint, 1))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(CassandraErrors.DELETE_STATE_FULL));
    }

    @Test
    void anOffsetFromTheOtherModeIsRefusedEitherWay() {
        assertThatThrownBy(() -> reader(new SourceOffset("token=5")))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(CassandraErrors.BAD_CONFIGURATION));
        assertThatThrownBy(() -> reader(new SourceOffset("lut=5")))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(CassandraErrors.MALFORMED_OFFSET));
        assertThatThrownBy(() -> reader(new SourceOffset(DetectingTokenRangeReader.PREFIX + "nobody/3")))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(CassandraErrors.DELETE_STATE_FAILED));
    }

    /**
     * Random writes -- tokens chosen from a small range so rows collide on them -- random poll sizes,
     * checkpoints at random points, and restarts that restore the view to its last checkpoint: after
     * every pass, the emitted rows sum to the table.
     */
    @Test
    void emittedRowsSumToTheTableThroughRandomWritesAndRestarts() {
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            table.clear();
            WeightedCollector out = new WeightedCollector(SCHEMA);
            DetectingTokenRangeReader reader = reader(null);
            SourceOffset checkpoint = reader.position();
            Map<List<Object>, Long> viewAtCheckpoint = new HashMap<>();
            for (int round = 0; round < 40; round++) {
                for (int write = random.nextInt(6); write > 0; write--) {
                    long id = random.nextInt(14);
                    long token = id % 5 - 2;
                    if (random.nextInt(3) == 0) {
                        if (table.containsKey(token)) {
                            delete(token, id);
                        }
                    } else {
                        put(token, id, random.nextBoolean() ? "NEW" : "DONE", random.nextInt(3));
                    }
                }
                int action = random.nextInt(4);
                if (action == 0) {
                    reader.poll(out, 1 + random.nextInt(3));
                    checkpoint = reader.position();
                    viewAtCheckpoint = new HashMap<>(out.view);
                    reader.checkpointed(checkpoint);
                } else if (action == 1) {
                    reader.close();
                    reader = reader(checkpoint);
                    out = WeightedCollector.restoredFrom(SCHEMA, viewAtCheckpoint);
                }
                out.drain(reader);
                assertThat(out.view).as("seed " + seed + " round " + round).isEqualTo(tableAsView());
            }
            reader.close();
        }
    }
}

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
import java.util.List;
import java.util.Map;

import com.datastax.oss.driver.api.core.type.DataTypes;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.ReadRequest.Comparison;
import com.ash.messaging.pravaha.api.plugin.ReadRequest.Filter;
import com.ash.messaging.pravaha.plugin.cassandra.CassandraPushdown.ColumnType;
import com.ash.messaging.pravaha.plugin.cassandra.CassandraPushdown.KeyRead;
import com.ash.messaging.pravaha.plugin.cassandra.CassandraPushdown.Plan;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CQL a query's filters become: what is pushed, what is left with the engine, and that nothing
 * pushed can return fewer rows than the filter allows.
 */
class CassandraPushdownTest {

    /** {@code PRIMARY KEY ((tenant, id), day, ts)} with a regular column {@code status}. */
    private static final List<String> KEY = List.of("tenant", "id");

    private static final List<String> CLUSTERING = List.of("day", "ts");

    private static final Map<String, ColumnType> TYPES = Map.of(
            "tenant", ColumnType.TEXT,
            "id", ColumnType.BIGINT,
            "day", ColumnType.INT,
            "ts", ColumnType.TIMESTAMP);

    private static Plan plan(Filter... filters) {
        return CassandraPushdown.plan(new ReadRequest(List.of(filters)), KEY, CLUSTERING, TYPES, 256);
    }

    private static Filter f(String column, Comparison comparison, Object value) {
        return new Filter(column, comparison, value);
    }

    @Test
    void theWholePartitionKeyByEqualityIsOnePartitionRead() {
        Plan plan = plan(f("tenant", Comparison.EQ, "acme"), f("id", Comparison.EQ, 7L));

        assertThat(plan.pushed()).isTrue();
        assertThat(plan.reads()).hasSize(1);
        KeyRead read = plan.reads().get(0);
        assertThat(read.where()).isEqualTo(" WHERE tenant = ? AND id = ?");
        assertThat(read.values()).containsExactly("acme", 7L);
        assertThat(plan.description()).isEqualTo("pushed to Cassandra: partition key tenant = 'acme' and id = 7");
    }

    @Test
    void clusteringIsEqualityDownAPrefixThenOneRange() {
        Plan plan = plan(
                f("tenant", Comparison.EQ, "acme"),
                f("id", Comparison.EQ, 7),
                f("day", Comparison.EQ, 3),
                f("ts", Comparison.GT, 5_000_000L),
                f("ts", Comparison.LE, 9_000_000L),
                f("status", Comparison.EQ, "DONE"));

        KeyRead read = plan.reads().get(0);
        assertThat(read.where()).isEqualTo(" WHERE tenant = ? AND id = ? AND day = ? AND ts >= ? AND ts <= ?");
        assertThat(read.values())
                .as("an int filter on a bigint key widens exactly; a timestamp bound is on its millisecond")
                .containsExactly("acme", 7L, 3, Instant.ofEpochMilli(5), Instant.ofEpochMilli(9));
        assertThat(plan.description())
                .isEqualTo("pushed to Cassandra: partition key tenant = 'acme' and id = 7, clustering day = 3 "
                        + "and ts >= 1970-01-01T00:00:00.005Z and ts <= 1970-01-01T00:00:00.009Z");
    }

    @Test
    void aRangeOnAClusteringColumnStopsTheRestrictionsAndOutOfOrderOnesAreLeftToTheEngine() {
        Plan skipped = plan(f("tenant", Comparison.EQ, "a"), f("id", Comparison.EQ, 1L), f("ts", Comparison.GT, 0L));
        assertThat(skipped.reads().get(0).where())
                .as("ts cannot be restricted while day is not pinned: CQL would need ALLOW FILTERING")
                .isEqualTo(" WHERE tenant = ? AND id = ?");

        Plan ranged = plan(
                f("tenant", Comparison.EQ, "a"),
                f("id", Comparison.EQ, 1L),
                f("day", Comparison.GE, 2),
                f("day", Comparison.GT, 2),
                f("day", Comparison.LT, 9),
                f("day", Comparison.LT, 7),
                f("ts", Comparison.EQ, 0L));
        assertThat(ranged.reads().get(0).where())
                .as("the tightest bound each way, and nothing after the range")
                .isEqualTo(" WHERE tenant = ? AND id = ? AND day > ? AND day < ?");
        assertThat(ranged.reads().get(0).values()).containsExactly("a", 1L, 2, 7);
    }

    @Test
    void aPartitionKeyPinnedOnlyInPartIsAScan() {
        Plan plan = plan(f("tenant", Comparison.EQ, "acme"), f("day", Comparison.EQ, 3));

        assertThat(plan.pushed()).isFalse();
        assertThat(plan.reads()).isNull();
        assertThat(plan.description())
                .isEqualTo("no filter pushed to Cassandra: the filters do not pin the whole partition key "
                        + "[tenant, id] by equality, which CQL would need ALLOW FILTERING for");
        assertThat(plan(f("tenant", Comparison.EQ, "a"), f("id", Comparison.GT, 1L))
                        .pushed())
                .as("a range on the partition key is not a partition")
                .isFalse();
        assertThat(CassandraPushdown.plan(ReadRequest.NOTHING, KEY, CLUSTERING, TYPES, 256)
                        .description())
                .contains("no filter was offered");
        assertThat(CassandraPushdown.plan(null, KEY, CLUSTERING, TYPES, 256).pushed())
                .isFalse();
    }

    @Test
    void anOrOfKeysIsOneReadPerKeyAndAnyAlternativeWithoutAKeyMakesItAScan() {
        ReadRequest shared = new ReadRequest(
                List.of(f("tenant", Comparison.EQ, "acme")),
                List.of(),
                List.of(),
                List.of(
                        List.of(f("id", Comparison.EQ, 1L)),
                        List.of(f("id", Comparison.EQ, 2L), f("day", Comparison.EQ, 4)),
                        List.of(f("id", Comparison.EQ, 1L))));
        Plan plan = CassandraPushdown.plan(shared, KEY, CLUSTERING, TYPES, 256);

        assertThat(plan.reads())
                .extracting(KeyRead::where)
                .as("the repeated key is read once")
                .containsExactly(" WHERE tenant = ? AND id = ?", " WHERE tenant = ? AND id = ? AND day = ?");
        assertThat(plan.description())
                .isEqualTo("pushed to Cassandra: 2 partitions by key [tenant, id], with clustering restrictions");

        ReadRequest widened = new ReadRequest(
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        List.of(f("tenant", Comparison.EQ, "a"), f("id", Comparison.EQ, 1L)),
                        List.of(f("status", Comparison.EQ, "x"))));
        assertThat(CassandraPushdown.plan(widened, KEY, CLUSTERING, TYPES, 256).description())
                .contains("in every alternative");

        assertThat(CassandraPushdown.plan(shared, KEY, CLUSTERING, TYPES, 1).description())
                .as("more partitions than the bound is a scan")
                .contains("more than 1 partitions");
    }

    @Test
    void filtersThatCannotAllHoldReadNoPartition() {
        Plan conflicting =
                plan(f("tenant", Comparison.EQ, "a"), f("tenant", Comparison.EQ, "b"), f("id", Comparison.EQ, 1L));
        assertThat(conflicting.pushed()).isTrue();
        assertThat(conflicting.reads()).isEmpty();
        assertThat(conflicting.description()).contains("no partition, because the filters cannot all hold");

        assertThat(plan(
                                f("tenant", Comparison.EQ, "a"),
                                f("id", Comparison.EQ, 1L),
                                f("day", Comparison.EQ, 1),
                                f("day", Comparison.EQ, 2))
                        .reads())
                .isEmpty();
        assertThat(plan(
                                f("tenant", Comparison.EQ, "a"),
                                f("id", Comparison.EQ, 1L),
                                f("day", Comparison.GT, 5),
                                f("day", Comparison.LT, 5))
                        .reads())
                .isEmpty();
        assertThat(plan(
                                f("tenant", Comparison.EQ, "a"),
                                f("id", Comparison.EQ, 1L),
                                f("day", Comparison.GE, 5),
                                f("day", Comparison.LE, 5))
                        .reads())
                .as("a range of one value holds")
                .hasSize(1);
        assertThat(plan(f("tenant", Comparison.EQ, "a"), f("tenant", Comparison.EQ, "a"), f("id", Comparison.EQ, 1L))
                        .reads())
                .as("the same value twice is one restriction")
                .hasSize(1);
    }

    @Test
    void aValueIsPushedOnlyWhereItIsExact() {
        assertThat(CassandraPushdown.exact(ColumnType.INT, 1L << 40))
                .as("outside int")
                .isNull();
        assertThat(CassandraPushdown.exact(ColumnType.INT, 12L)).isEqualTo(12);
        assertThat(CassandraPushdown.exact(ColumnType.TINYINT, 300)).isNull();
        assertThat(CassandraPushdown.exact(ColumnType.TINYINT, (short) 3)).isEqualTo((byte) 3);
        assertThat(CassandraPushdown.exact(ColumnType.SMALLINT, 70_000)).isNull();
        assertThat(CassandraPushdown.exact(ColumnType.SMALLINT, (byte) 3)).isEqualTo((short) 3);
        assertThat(CassandraPushdown.exact(ColumnType.BIGINT, 3)).isEqualTo(3L);
        assertThat(CassandraPushdown.exact(ColumnType.BIGINT, 3.0))
                .as("never a double")
                .isNull();
        assertThat(CassandraPushdown.exact(ColumnType.TEXT, "é")).isEqualTo("é");
        assertThat(CassandraPushdown.exact(ColumnType.TEXT, 1)).isNull();
        assertThat(CassandraPushdown.exact(ColumnType.ASCII, "plain")).isEqualTo("plain");
        assertThat(CassandraPushdown.exact(ColumnType.ASCII, "é"))
                .as("not ascii")
                .isNull();
        assertThat(CassandraPushdown.exact(ColumnType.BOOLEAN, true)).isEqualTo(true);
        assertThat(CassandraPushdown.exact(ColumnType.BOOLEAN, "true")).isNull();
        assertThat(CassandraPushdown.exact(ColumnType.TIMESTAMP, 7_000_000L)).isEqualTo(Instant.ofEpochMilli(7));
        assertThat(CassandraPushdown.exact(ColumnType.TIMESTAMP, 7_000_001L))
                .as("no millisecond holds it")
                .isNull();
        assertThat(CassandraPushdown.exact(ColumnType.TIMESTAMP, 7)).isNull();
    }

    @Test
    void aTimestampRangeIsWidenedToWholeMillisecondsAndNeverNarrowed() {
        Plan plan = plan(
                f("tenant", Comparison.EQ, "a"),
                f("id", Comparison.EQ, 1L),
                f("day", Comparison.EQ, 1),
                f("ts", Comparison.GE, -1_500_001L),
                f("ts", Comparison.LT, 2_000_001L));

        assertThat(plan.reads().get(0).values())
                .as("floor for the lower bound, ceiling for the upper, both inclusive")
                .containsExactly("a", 1L, 1, Instant.ofEpochMilli(-2), Instant.ofEpochMilli(3));

        Plan text = plan(
                f("tenant", Comparison.EQ, "a"),
                f("id", Comparison.EQ, 1L),
                f("day", Comparison.EQ, 1),
                f("ts", Comparison.GT, "x"));
        assertThat(text.reads().get(0).where())
                .as("a bound whose value cannot be carried is left with the engine")
                .isEqualTo(" WHERE tenant = ? AND id = ? AND day = ?");
    }

    @Test
    void textIsPushedByEqualityOnlyAndAColumnOfAnotherTypeNotAtAll() {
        Map<String, ColumnType> types = Map.of("name", ColumnType.TEXT, "flag", ColumnType.BOOLEAN);
        Plan onText = CassandraPushdown.plan(
                new ReadRequest(List.of(f("name", Comparison.EQ, "x"), f("flag", Comparison.EQ, true))),
                List.of("name"),
                List.of("flag"),
                types,
                256);
        assertThat(onText.reads().get(0).where()).isEqualTo(" WHERE name = ? AND flag = ?");

        Plan ranged = CassandraPushdown.plan(
                new ReadRequest(List.of(f("name", Comparison.EQ, "x"), f("flag", Comparison.GT, false))),
                List.of("name"),
                List.of("flag"),
                types,
                256);
        assertThat(ranged.reads().get(0).where()).isEqualTo(" WHERE name = ?");

        Plan uuid = CassandraPushdown.plan(
                new ReadRequest(List.of(f("u", Comparison.EQ, "0000"))), List.of("u"), List.of(), Map.of(), 256);
        assertThat(uuid.pushed()).as("a key column of a type not pushed").isFalse();

        Plan ne = plan(f("tenant", Comparison.EQ, "a"), f("id", Comparison.NE, 1L));
        assertThat(ne.pushed()).isFalse();
    }

    @Test
    void theDriversTypesMapOntoTheOnesPushed() {
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.TINYINT)).isEqualTo(ColumnType.TINYINT);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.SMALLINT)).isEqualTo(ColumnType.SMALLINT);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.INT)).isEqualTo(ColumnType.INT);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.BIGINT)).isEqualTo(ColumnType.BIGINT);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.TIMESTAMP)).isEqualTo(ColumnType.TIMESTAMP);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.TEXT)).isEqualTo(ColumnType.TEXT);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.ASCII)).isEqualTo(ColumnType.ASCII);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.BOOLEAN)).isEqualTo(ColumnType.BOOLEAN);
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.UUID)).isNull();
        assertThat(CassandraSourcePlugin.pushdownType(DataTypes.DOUBLE)).isNull();
    }
}

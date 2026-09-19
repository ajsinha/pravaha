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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-039 item 6, in the JDBC plugin: a projection is a SELECT list, a shared reader's OR is a
 * disjunction in the WHERE clause, and a {@code COUNT}/{@code SUM} partial is a GROUP BY over one
 * keyset page -- each checked against H2 for what it sends and what it declines.
 */
class JdbcProjectionAndPartialTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private String url;
    private Connection admin;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:pushdown" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        admin = DriverManager.getConnection(url);
        execute("CREATE TABLE sales (id BIGINT NOT NULL, region INT, qty BIGINT, status VARCHAR(16), "
                + "updated_at BIGINT NOT NULL)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        admin.close();
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private void insert(long id, Integer region, Long qty, String status, long updatedAt) throws SQLException {
        execute("INSERT INTO sales VALUES (" + id + ", " + region + ", " + qty + ", "
                + (status == null ? "NULL" : "'" + status + "'") + ", " + updatedAt + ")");
    }

    private JdbcSourcePlugin open(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "url", url, "table", "sales", "watermark.column", "UPDATED_AT", "key.column", "ID", "stream", "sales"));
        config.putAll(extra);
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("sales", config));
        plugin.open();
        return plugin;
    }

    private static List<RowView> drain(PartitionReader reader, JdbcCollector collector, int batch) {
        while (reader.poll(collector, batch) > 0) {
            // each poll returns what is ready
        }
        return collector.rows();
    }

    private static ReadRequest.Filter filter(String column, ReadRequest.Comparison comparison, Object value) {
        return new ReadRequest.Filter(column, comparison, value);
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    void aKeyedSourceDeclaresFilterProjectAndPartialAggregate() {
        assertThat(open(Map.of()).capabilities().pushdown())
                .containsExactlyInAnyOrder(PushdownKind.FILTER, PushdownKind.PROJECT, PushdownKind.PARTIAL_AGGREGATE);
    }

    @Test
    void aKeylessSourceDoesNotDeclareAPartialAggregate() {
        // Keyless mode resumes by counting rows at a tied watermark, and a page's end cannot be
        // named without a total order -- so there is no "(offset, end]" for a GROUP BY to cover.
        Map<String, String> keyless = new HashMap<>(
                Map.of("url", url, "table", "sales", "watermark.column", "UPDATED_AT", "stream", "sales"));
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("sales", keyless));
        plugin.open();
        assertThat(plugin.capabilities().pushdown())
                .containsExactlyInAnyOrder(PushdownKind.FILTER, PushdownKind.PROJECT);
    }

    @Test
    void anOperatorCanTurnPartialAggregatesOff() {
        assertThat(open(Map.of("pushdown.partial.aggregate", "false"))
                        .capabilities()
                        .pushdown())
                .doesNotContain(PushdownKind.PARTIAL_AGGREGATE);
    }

    // ------------------------------------------------------------------ projection

    @Test
    void aPushedProjectionSelectsOnlyTheColumnsAskedForPlusTheReadersOwn() throws SQLException {
        insert(1, 7, 30L, "DONE", 100);
        insert(2, 8, 40L, "OPEN", 101);
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(List.of(), List.of("QTY"), List.of());

        // QTY for the engine; UPDATED_AT and ID because the reader orders and resumes by them.
        assertThat(plugin.pollQueryFor(request)).startsWith("SELECT ID, QTY, UPDATED_AT FROM sales");

        JdbcCollector collector = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), null, request)) {
            List<RowView> rows = drain(reader, collector, 64);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getLong(2)).isEqualTo(30L);
            assertThat(rows.get(1).getLong(2)).isEqualTo(40L);
            // Not selected, so not sent: written as null where the column allows it.
            assertThat(rows.get(0).isNull(1)).isTrue();
            assertThat(rows.get(0).isNull(3)).isTrue();
        }
    }

    @Test
    void aProjectionNamingAColumnTheSourceDoesNotHaveReadsEverything() {
        JdbcSourcePlugin plugin = open(Map.of());
        assertThat(plugin.pollQueryFor(new ReadRequest(List.of(), List.of("QTY", "NOPE"), List.of())))
                .startsWith("SELECT * FROM sales");
    }

    // ------------------------------------------------------------------ a shared reader's OR

    @Test
    void alternativesBecomeAnOrTheDatabaseEvaluates() throws SQLException {
        for (long id = 1; id <= 30; id++) {
            insert(id, (int) (id % 3), id, "S", id);
        }
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(
                List.of(filter("REGION", ReadRequest.Comparison.EQ, 1)),
                List.of(),
                List.of(),
                List.of(
                        List.of(filter("QTY", ReadRequest.Comparison.LT, 5L)),
                        List.of(filter("QTY", ReadRequest.Comparison.GT, 25L))));

        assertThat(plugin.pollQueryFor(request)).contains("WHERE REGION = ? AND ((QTY < ?) OR (QTY > ?))");

        JdbcCollector collector = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), null, request)) {
            assertThat(drain(reader, collector, 64).stream()
                            .map(row -> row.getLong(0))
                            .toList())
                    .containsExactly(1L, 4L, 28L);
        }
    }

    @Test
    void anAlternativeWithNothingExpressibleMakesTheWholeOrTrueRatherThanNarrowingIt() {
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        List.of(filter("QTY", ReadRequest.Comparison.LT, 5L)),
                        List.of(filter("NOT_A_COLUMN", ReadRequest.Comparison.EQ, 1L))));
        // Dropping the second alternative would lose every row only it wanted; the OR is true.
        assertThat(plugin.pollQueryFor(request)).doesNotContain("WHERE");
    }

    // ------------------------------------------------------------------ partial aggregates

    private static ReadRequest partialRequest(List<String> groupBy, List<ReadRequest.Filter> filters) {
        return new ReadRequest(
                filters,
                List.of(),
                List.of(new ReadRequest.PartialAggregate(
                        groupBy,
                        List.of(
                                new ReadRequest.PartialAggregate.AggregateCall(
                                        ReadRequest.PartialAggregate.Kind.COUNT, null, "N"),
                                new ReadRequest.PartialAggregate.AggregateCall(
                                        ReadRequest.PartialAggregate.Kind.SUM, "QTY", "TOTAL"),
                                new ReadRequest.PartialAggregate.AggregateCall(
                                        ReadRequest.PartialAggregate.Kind.COUNT, "QTY", "NONNULL")))));
    }

    private static StreamSchema grouped() {
        return StreamSchema.builder("partial")
                .field("REGION", Types.int32().withNullable(true))
                .field("N", Types.int64())
                .field("TOTAL", Types.int64())
                .field("NONNULL", Types.int64())
                .build();
    }

    @Test
    void aGroupedPartialOverManyPagesSumsToWhatTheRowsWould() throws SQLException {
        Map<Integer, long[]> expected = new TreeMap<>();
        for (long id = 1; id <= 97; id++) {
            int region = (int) (id % 4);
            Long qty = id % 11 == 0 ? null : id * 3;
            insert(id, region, qty, "DONE", id / 2); // watermarks tie in pairs
            if (id <= 10) {
                continue; // ID > 10 is pushed
            }
            long[] sums = expected.computeIfAbsent(region, r -> new long[3]);
            sums[0]++;
            sums[1] += qty == null ? 0 : qty;
            sums[2] += qty == null ? 0 : 1;
        }
        JdbcSourcePlugin plugin = open(Map.of("fetch.size", "7"));
        ReadRequest request = partialRequest(List.of("REGION"), List.of(filter("ID", ReadRequest.Comparison.GT, 10L)));

        Map<Integer, long[]> actual = new TreeMap<>();
        SourceOffset midway;
        JdbcCollector first = new JdbcCollector(grouped());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), null, request)) {
            assertThat(reader.deliversPartialAggregate()).isTrue();
            // Several pages, then stop: a restore resumes from here and must neither repeat nor
            // skip a page.
            for (int i = 0; i < 4; i++) {
                reader.poll(first, 5);
            }
            midway = reader.position();
            // A page is at most as many rows as the lane can take partials for: five a poll, so
            // every partial of a page is written in the poll that computed it.
            assertThat(((JdbcPartialAggregateReader) reader).pages()).isEqualTo(4);
            assertThat(((JdbcPartialAggregateReader) reader).rowsSummarised()).isEqualTo(20);
        }
        JdbcCollector rest = new JdbcCollector(grouped());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), midway, request)) {
            drain(reader, rest, 5);
        }
        for (List<RowView> rows : List.of(first.rows(), rest.rows())) {
            for (RowView row : rows) {
                assertThat(row.weight()).isEqualTo(1L);
                long[] sums = actual.computeIfAbsent(row.getInt(0), r -> new long[3]);
                sums[0] += row.getLong(1);
                sums[1] += row.getLong(2);
                sums[2] += row.getLong(3);
            }
        }
        assertThat(first.rows())
                .as("four polls of at most five rows each is several partials")
                .isNotEmpty();
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((region, sums) ->
                assertThat(actual.get(region)).as("region %d", region).containsExactly(sums));
    }

    @Test
    void aGlobalPartialOverNothingWritesNothing() throws SQLException {
        insert(1, 1, 5L, "DONE", 1);
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = partialRequest(List.of(), List.of(filter("QTY", ReadRequest.Comparison.GT, 100L)));
        StreamSchema global = StreamSchema.builder("partial")
                .field("N", Types.int64())
                .field("TOTAL", Types.int64())
                .field("NONNULL", Types.int64())
                .build();

        JdbcCollector collector = new JdbcCollector(global);
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), null, request)) {
            // COUNT(*) with no GROUP BY answers (0, 0) over no rows; writing it would tell the
            // aggregate something arrived when nothing did.
            assertThat(drain(reader, collector, 64)).isEmpty();
        }
    }

    @Test
    void aFilterTheSqlCannotCarryMeansRowsNotAPartial() {
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request =
                partialRequest(List.of("REGION"), List.of(filter("NOT_A_COLUMN", ReadRequest.Comparison.EQ, 1L)));
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), null, request)) {
            assertThat(reader.deliversPartialAggregate()).isFalse();
        }
    }

    @Test
    void textIsNeverComparedOrGroupedInTheDatabaseUnlessTheCollationIsDeclaredBinary() {
        ReadRequest byText =
                partialRequest(List.of("REGION"), List.of(filter("STATUS", ReadRequest.Comparison.EQ, "DONE")));
        ReadRequest groupedByText = partialRequest(List.of("STATUS"), List.of());

        JdbcSourcePlugin cautious = open(Map.of());
        try (PartitionReader a =
                        cautious.createReader(cautious.partitions("sales").get(0), null, byText);
                PartitionReader b =
                        cautious.createReader(cautious.partitions("sales").get(0), null, groupedByText)) {
            // A case-insensitive collation matches 'done' and groups it with 'DONE'; the engine
            // does neither, and a partial leaves nothing downstream to notice the difference.
            assertThat(a.deliversPartialAggregate()).isFalse();
            assertThat(b.deliversPartialAggregate()).isFalse();
        }
        JdbcSourcePlugin binary = open(Map.of("collation.binary", "true"));
        try (PartitionReader a = binary.createReader(binary.partitions("sales").get(0), null, byText);
                PartitionReader b =
                        binary.createReader(binary.partitions("sales").get(0), null, groupedByText)) {
            assertThat(a.deliversPartialAggregate()).isTrue();
            assertThat(b.deliversPartialAggregate()).isTrue();
        }
    }
}

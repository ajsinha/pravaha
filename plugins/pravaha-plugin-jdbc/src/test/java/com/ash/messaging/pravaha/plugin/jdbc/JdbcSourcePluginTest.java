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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JDBC incremental-poll source, against H2.
 *
 * <p>H2 rather than a container because the contract being tested is plain JDBC -- metadata,
 * ordering, fetch limits, null handling -- and none of it is dialect-specific. A PostgreSQL
 * integration test proves the driver works, which is Postgres's job; this proves the plugin works.
 *
 * <p>{@link #rowsSharingAWatermarkAreNeitherDuplicatedNorLost} is the one that matters. Watermark
 * columns are not unique in practice, and every naive implementation is wrong at the boundary in
 * one direction or the other.
 */
class JdbcSourcePluginTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private String url;
    private Connection admin;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:pravaha" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        admin = DriverManager.getConnection(url);
        execute(
                "CREATE TABLE orders (id BIGINT NOT NULL, name VARCHAR(64), amount DOUBLE, updated_at BIGINT NOT NULL)");
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

    private void insert(long id, String name, Double amount, long updatedAt) throws SQLException {
        execute("INSERT INTO orders VALUES (" + id + ", '" + name + "', " + (amount == null ? "NULL" : amount) + ", "
                + updatedAt + ")");
    }

    private JdbcSourcePlugin open(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "url",
                url,
                "table",
                "orders",
                "watermark.column",
                "UPDATED_AT",
                // A unique tie-breaker, without which rows sharing a watermark have no defined
                // order and the boundary cannot be resumed. See JdbcOffset.
                "key.column",
                "ID",
                "stream",
                "orders"));
        config.putAll(extra);
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        return plugin;
    }

    private static List<RowView> drain(PartitionReader reader, JdbcCollector collector) {
        while (reader.poll(collector, 64) > 0) {
            // each poll returns what is ready
        }
        return collector.rows();
    }

    @Test
    void derivesItsSchemaFromTheDatabase() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        // Asked, not declared: the database holds the authoritative definition, so restating it by
        // hand would only create a second version that can disagree with the first.
        assertThat(plugin.schema().fieldCount()).isEqualTo(4);
        assertThat(plugin.schema().field(0).name()).isEqualTo("ID");
        assertThat(plugin.schema().field(0).type().nullable())
                .as("NOT NULL in the database is NOT NULL in the stream")
                .isFalse();
        assertThat(plugin.schema().field(1).type().nullable()).isTrue();
    }

    /**
     * T-5. The watermark column was stamped on every row as its event time, raw.
     *
     * <p>That column is a monotone cursor and need not be a time at all; where it is one, it is in
     * whatever unit the table keeps, and the engine counts nanoseconds (ADR-012). So an
     * {@code updated_at BIGINT} of epoch milliseconds gave an event time out by a factor of a
     * million — a watermark stuck in 1970, windows that never close, and nothing anywhere saying
     * so. {@code watermark.unit} is how a deployment says which it is.
     */
    @Test
    void t5_theWatermarkColumnIsAnEventTimeOnlyWhenItsUnitIsDeclared() throws SQLException {
        insert(1, "ann", 10.5, 1_700_000_000_000L);

        JdbcSourcePlugin millis = open(Map.of("watermark.unit", "millis"));
        try (JdbcCollector collector = new JdbcCollector(millis.schema());
                PartitionReader reader =
                        millis.createReader(millis.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector).get(0).eventTimestampNanos())
                    .as("epoch millis, converted once, here")
                    .isEqualTo(1_700_000_000_000L * 1_000_000L);
        }
        millis.close();

        JdbcSourcePlugin nanos = open(Map.of("watermark.unit", "nanos"));
        try (JdbcCollector collector = new JdbcCollector(nanos.schema());
                PartitionReader reader =
                        nanos.createReader(nanos.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector).get(0).eventTimestampNanos())
                    .as("the one unit that needs no conversion, and what the code used to assume")
                    .isEqualTo(1_700_000_000_000L);
        }
        nanos.close();
    }

    /**
     * T-5, the other way the unit can be wrong: declared too coarse, so the value overflows.
     *
     * <p>A column of epoch milliseconds read as {@code seconds} multiplies by a billion and leaves
     * the range of a long. Wrapping it would put the watermark before the epoch and close every
     * window at once, which is the silent failure this whole finding is about.
     */
    @Test
    void t5_aWatermarkThatOverflowsItsDeclaredUnitIsRefusedRatherThanWrapped() throws SQLException {
        insert(1, "ann", 10.5, 1_700_000_000_000L);

        JdbcSourcePlugin seconds = open(Map.of("watermark.unit", "seconds"));
        try (JdbcCollector collector = new JdbcCollector(seconds.schema());
                PartitionReader reader =
                        seconds.createReader(seconds.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThatThrownBy(() -> drain(reader, collector))
                    .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                    .hasMessageContaining("watermark.unit")
                    .hasMessageContaining("epoch milliseconds read");
        }
        seconds.close();
    }

    /** T-5's refusal: a unit this plugin does not know is named rather than guessed at. */
    @Test
    void t5_anUnknownWatermarkUnitIsRefusedByName() {
        assertThatThrownBy(() -> open(Map.of("watermark.unit", "milliseconds-ish")))
                .isInstanceOf(com.ash.messaging.pravaha.api.ConfigurationException.class)
                .hasMessageContaining("watermark.unit")
                .hasMessageContaining("none, nanos, micros, millis, seconds");
    }

    @Test
    void readsRowsInWatermarkOrderAndPreservesNulls() throws SQLException {
        insert(3, "cat", 30.0, 300);
        insert(1, "ann", 10.5, 100);
        insert(2, "bob", null, 200);

        JdbcSourcePlugin plugin = open(Map.of());
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(reader, collector);

            assertThat(rows).hasSize(3);
            assertThat(rows.stream().map(r -> r.getLong(0))).containsExactly(1L, 2L, 3L);
            assertThat(rows.get(1).isNull(2))
                    .as("a SQL NULL is a null, not a zero")
                    .isTrue();
            assertThat(rows.get(0).eventTimestampNanos())
                    .as("T-5: the watermark column is a cursor, not a time, until `watermark.unit` "
                            + "says what its numbers mean. It used to be stamped raw, so an epoch-millis "
                            + "column produced an event time out by a factor of a million")
                    .isZero();
        }
    }

    @Test
    void onlyNewRowsArriveOnALaterPoll() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector)).hasSize(1);

            insert(2, "bob", 20.0, 200);
            assertThat(drain(reader, collector).stream().map(r -> r.getLong(0)))
                    .as("the first row must not be re-read")
                    .containsExactly(1L, 2L);
        }
    }

    @Test
    void rowsSharingAWatermarkAreNeitherDuplicatedNorLost() throws SQLException {
        // A batch job stamping a thousand rows with one timestamp is the normal case, not an edge
        // one. Strictly-greater loses rows that commit late at the boundary; greater-or-equal
        // re-emits the boundary on every poll; and counting rows at the boundary only works if the
        // database returns tied rows in a stable order, which it does not promise -- this test read
        // rows 3 and 2 where it expected 1 and 2 until the key column made the sort total.
        insert(1, "a", 1.0, 100);
        insert(2, "b", 2.0, 100);
        insert(3, "c", 3.0, 100);

        JdbcSourcePlugin plugin = open(Map.of());
        SourceOffset checkpoint;
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
            assertThat(collector.rows().stream().map(r -> r.getLong(0))).containsExactly(1L, 2L);
        }

        // A fourth row lands at the same watermark after the checkpoint, which is exactly the case
        // a strictly-greater comparison would drop on the floor.
        insert(4, "d", 4.0, 100);

        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader resumed =
                        plugin.createReader(plugin.partitions("orders").get(0), checkpoint)) {
            assertThat(drain(resumed, collector).stream().map(r -> r.getLong(0)))
                    .as("the boundary is resumed exactly: nothing repeated, nothing dropped")
                    .containsExactly(3L, 4L);
        }
    }

    @Test
    void anArbitraryQueryCanBeThePolledSource() throws SQLException {
        insert(1, "ann", 10.5, 100);
        insert(2, "bob", 20.0, 200);

        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "big",
                Map.of(
                        "url", url,
                        "query", "SELECT id, updated_at FROM orders WHERE amount > 15",
                        "watermark.column", "UPDATED_AT",
                        "key.column", "ID",
                        "stream", "big")));
        plugin.open();

        assertThat(plugin.schema().fieldCount()).isEqualTo(2);
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("big").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector).stream().map(r -> r.getLong(0))).containsExactly(2L);
        }
    }

    @Test
    void withoutAKeyColumnTheSourceDeclaresItselfNonReplayable() throws SQLException {
        // The honest half of the finding above. Without a tie-breaker the resume is right only when
        // the database happens to be consistent about tied rows, and "usually right" is not a
        // guarantee -- so the capability says so instead of hoping.
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "orders", Map.of("url", url, "table", "orders", "watermark.column", "UPDATED_AT", "stream", "orders")));
        plugin.open();

        assertThat(plugin.capabilities().replayableOffsets()).isFalse();
        assertThat(open(Map.of()).capabilities().replayableOffsets())
                .as("with a key column the sort is total and the resume is exact")
                .isTrue();
    }

    @Test
    void capabilitiesRefuseToClaimWhatPollingCannotDo() throws SQLException {
        insert(1, "ann", 10.5, 100);
        var capabilities = open(Map.of()).capabilities();

        assertThat(capabilities.pushdown())
                .as("a WHERE clause, a SELECT list, and -- keyed -- a GROUP BY over one keyset page (ADR-039 item 6); "
                        + "JdbcProjectionAndPartialTest covers what each declines")
                .containsExactlyInAnyOrder(
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.FILTER,
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.PROJECT,
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.PARTIAL_AGGREGATE);

        assertThat(capabilities.emitsDeletes())
                .as("a deleted row is simply absent from the next result set, which is "
                        + "indistinguishable from a row that never existed")
                .isFalse();
        assertThat(capabilities.emitsBeforeImage()).isFalse();
        assertThat(capabilities.guarantee())
                .as("resumption is exact, but an update seen once with its new value is not a changelog")
                .isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
    }

    @Test
    void anUpdatedRowIsReadAgainAndTheSourceSaysItRepeatsRows() throws SQLException {
        // SCAN-1. A watermark column an update moves brings the row back as a second +1 with nothing
        // retracting the first: a COUNT over it counts two. The plugin cannot tell such a column from
        // one set once on insert, so it declares the repeat unless told otherwise.
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector)).hasSize(1);
            execute("UPDATE orders SET amount = 11.5, updated_at = 150 WHERE id = 1");
            assertThat(drain(reader, collector).stream().map(r -> r.getLong(0)))
                    .as("the same row, read twice")
                    .containsExactly(1L, 1L);
        }
        assertThat(plugin.capabilities().repeatsRows()).isTrue();
    }

    @Test
    void onlyAnInsertOnlyWatermarkWithAKeyDeclaresThatNoRowRepeats() throws SQLException {
        insert(1, "ann", 10.5, 100);
        assertThat(open(Map.of("watermark.moves.on.update", "false"))
                        .capabilities()
                        .repeatsRows())
                .as("the operator vouches the column is set once, and the key makes the resume exact")
                .isFalse();

        JdbcSourcePlugin keyless = new JdbcSourcePlugin();
        keyless.configure(new Ctx(
                "orders",
                Map.of(
                        "url", url,
                        "table", "orders",
                        "watermark.column", "UPDATED_AT",
                        "stream", "orders",
                        "watermark.moves.on.update", "false")));
        assertThat(keyless.capabilities().repeatsRows())
                .as("without a key, rows tied on the watermark are resumed by counting, and re-read when the "
                        + "database returns them in another order")
                .isTrue();

        assertThatThrownBy(() -> open(Map.of("watermark.moves.on.update", "flase")))
                .isInstanceOf(com.ash.messaging.pravaha.api.ConfigurationException.class)
                .hasMessageContaining("PRV-5074")
                .hasMessageContaining("true or false");
    }

    @Test
    void aNullTextColumnIsReadAsNullRatherThanThrowing() {
        // The polling source had this bug too and no test had ever fed it a null string: JDBC
        // returns null for a text column, the row writer will not take one, and the "write it, then
        // ask wasNull" protocol that works for a long throws before it can ask. It surfaced in the
        // lookup plugin first, on the same shared decoder.
        assertThatCode(() -> {
                    execute("INSERT INTO orders VALUES (1, NULL, 5.0, 100)");
                    JdbcSourcePlugin plugin = open(Map.of());
                    JdbcCollector collector = new JdbcCollector(plugin.schema());
                    try (PartitionReader reader =
                            plugin.createReader(plugin.partitions("orders").get(0), null)) {
                        assertThat(drain(reader, collector).get(0).isNull(1)).isTrue();
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    void aPushedFilterBecomesAWhereClauseTheDatabaseEvaluates() throws SQLException {
        for (long id = 1; id <= 50; id++) {
            insert(id, "n" + id, (double) id, id);
        }
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(List.of(new ReadRequest.Filter("ID", ReadRequest.Comparison.GT, 45L)));

        JdbcCollector collector = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            List<RowView> rows = drain(reader, collector);

            // Five rows crossed the boundary instead of fifty. That number is the entire point: the
            // engine would have filtered the other forty-five out, having already paid to read,
            // decode and copy every one of them.
            assertThat(rows).hasSize(5);
            assertThat(rows.stream().map(row -> row.getLong(0)).toList()).containsExactly(46L, 47L, 48L, 49L, 50L);
        }
    }

    @Test
    void aPushedValueIsBoundRatherThanSplicedIntoTheSql() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(
                List.of(new ReadRequest.Filter("NAME", ReadRequest.Comparison.EQ, "o'brien'); DROP TABLE orders; --")));

        // The value never reaches the SQL text, so there is nothing to escape and nothing to
        // inject. The marker does.
        assertThat(plugin.pollQueryFor(request)).contains("NAME = ?").doesNotContain("DROP TABLE");

        JdbcCollector collector = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            assertThat(drain(reader, collector)).isEmpty();
        }
        // And the table is still there.
        assertThat(open(Map.of()).schema().fields()).isNotEmpty();
    }

    @Test
    void aFilterOnAColumnTheSourceDoesNotHaveIsDroppedRatherThanSent() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(List.of(
                new ReadRequest.Filter("ID", ReadRequest.Comparison.GE, 1L),
                new ReadRequest.Filter("NOT_A_COLUMN", ReadRequest.Comparison.EQ, 7L)));

        // A name cannot be a bound parameter, so the only safe name is one the database's own
        // catalogue produced. Anything else is dropped -- costing bandwidth, not correctness.
        String sql = plugin.pollQueryFor(request);
        assertThat(sql).contains("ID >= ?").doesNotContain("NOT_A_COLUMN");

        JdbcCollector collector = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            assertThat(drain(reader, collector)).hasSize(1);
        }
    }

    @Test
    void pushingFiltersDoesNotDisturbResumption() throws SQLException {
        for (long id = 1; id <= 20; id++) {
            insert(id, "n" + id, (double) id, id);
        }
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(List.of(new ReadRequest.Filter("ID", ReadRequest.Comparison.GT, 10L)));

        // The filter's markers sit after the resume predicate's. Bound in the wrong order they
        // would be read as a watermark, and the reader would resume from a filter value -- which
        // does not fail, it just reads from the wrong place.
        SourceOffset midpoint;
        JdbcCollector first = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            reader.poll(first, 5);
            midpoint = reader.position();
        }
        assertThat(first.rows().stream().map(row -> row.getLong(0)).toList()).containsExactly(11L, 12L, 13L, 14L, 15L);

        JdbcCollector rest = new JdbcCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), midpoint, request)) {
            assertThat(drain(reader, rest).stream().map(row -> row.getLong(0)).toList())
                    .containsExactly(16L, 17L, 18L, 19L, 20L);
        }
    }

    @Test
    void aReaderWithoutARequestReadsEverything() throws SQLException {
        for (long id = 1; id <= 10; id++) {
            insert(id, "n" + id, (double) id, id);
        }
        JdbcSourcePlugin plugin = open(Map.of());
        JdbcCollector collector = new JdbcCollector(plugin.schema());

        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null)) {
            assertThat(drain(reader, collector)).hasSize(10);
        }
    }

    @Test
    void aMisconfiguredSourceSaysWhatToFix() {
        assertThatThrownBy(() -> open(Map.of("query", "SELECT 1")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("exactly one of table or query");

        assertThatThrownBy(() -> open(Map.of("watermark.column", "NOT_A_COLUMN")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("is not in the result");
    }

    @Test
    void anUnreachableDatabaseSaysWhereToLook() {
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "gone", Map.of("url", "jdbc:h2:tcp://127.0.0.1:1/nope", "table", "orders", "watermark.column", "X")));

        assertThatThrownBy(plugin::open)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("classpath")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(JdbcErrors.CONNECT_FAILED);
    }

    @Test
    void anOffsetThisPluginDidNotWriteIsRefused() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        assertThatThrownBy(() ->
                        plugin.createReader(plugin.partitions("orders").get(0), new SourceOffset("v=3;p=ADDS;f=0;r=0")))
                .isInstanceOf(PravahaException.class)
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(JdbcErrors.MALFORMED_OFFSET);
    }

    @Test
    void healthReportsTheConnection() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());
        assertThat(plugin.health().isUsable()).isTrue();

        plugin.close();
        assertThat(plugin.health().isUsable()).isFalse();
    }
}

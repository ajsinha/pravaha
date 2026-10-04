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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code snapshot.mode: initial} against a real PostgreSQL 16: the rows already in a table, spliced
 * into its change stream at the point an exported snapshot is consistent with, and resumed exactly
 * from checkpoints taken half-way through.
 *
 * <p>The property every test here comes back to: the Z-set the engine was handed -- each distinct
 * row with the sum of its weights -- equals the table, with every row at exactly {@code +1}. A row
 * lost is missing from it; a row delivered twice is at {@code +2}; a retraction of a row never
 * inserted is left at {@code -1}.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PostgresCdcSnapshotTest {

    private final List<String> slots = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : open.reversed()) {
            closeable.close();
        }
        slots.forEach(PgServer::dropSlotQuietly);
    }

    /** The two shapes of key a snapshot is ordered by. */
    enum Shape {
        /** {@code id BIGINT PRIMARY KEY}. */
        BIGINT,
        /**
         * {@code PRIMARY KEY (code TEXT, n INT)}, {@code code} in ICU's root collation, where
         * {@code "a" < "B"} -- the opposite of code-point order. ICU, not the image's {@code en_US.utf8}:
         * on Alpine that is musl's, which collates by code point, and a test of the difference would
         * then test nothing. A frontier compared in Java would get these rows wrong.
         */
        COMPOSITE_TEXT;

        private static final String[] CODES = {"a", "B", "c", "D", "\u00e9", "Z", "_x", "a b", "A", "b"};

        String create(String table) {
            return switch (this) {
                case BIGINT -> "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, tier TEXT NOT NULL, amount INT)";
                case COMPOSITE_TEXT ->
                    "CREATE TABLE " + table
                            + " (code TEXT COLLATE \"und-x-icu\" NOT NULL, n INT NOT NULL, tier TEXT NOT NULL, amount INT, PRIMARY KEY (code, n))";
            };
        }

        String columns() {
            return this == BIGINT ? "id, tier, amount" : "code, n, tier, amount";
        }

        /** The key columns' values for key number {@code k}, as a SQL fragment of literals. */
        String values(long k) {
            return this == BIGINT
                    ? Long.toString(k)
                    : "'" + CODES[(int) (k % CODES.length)] + "', " + (k / CODES.length);
        }

        String where(long k) {
            return this == BIGINT
                    ? "id = " + k
                    : "code = '" + CODES[(int) (k % CODES.length)] + "' AND n = " + (k / CODES.length);
        }

        String set(long k) {
            return this == BIGINT
                    ? "id = " + k
                    : "code = '" + CODES[(int) (k % CODES.length)] + "', n = " + (k / CODES.length);
        }

        String seed(String table, long from, long to) {
            String key = this == BIGINT
                    ? "g"
                    : "(ARRAY['a','B','c','D','\u00e9','Z','_x','a b','A','b'])[(g % 10)::int + 1], (g / 10)::int";
            return "INSERT INTO " + table + " SELECT " + key + ", 'seed', (g % 97)::int FROM generate_series(" + from
                    + ", " + to + ") g";
        }
    }

    private String table(Shape shape) {
        String table = PgServer.unique("snap");
        PgServer.sql(shape.create(table), "ALTER TABLE " + table + " REPLICA IDENTITY FULL");
        slots.add(table);
        return table;
    }

    private PostgresCdcSourcePlugin plugin(String table, Map<String, String> extra) {
        Map<String, String> options = PgServer.options(table);
        options.put("snapshot.mode", "initial");
        options.put("heartbeat.interval", "200ms");
        options.putAll(extra);
        PostgresCdcSourcePlugin plugin = PgServer.open(options);
        open.add(plugin);
        return plugin;
    }

    private PartitionReader reader(PostgresCdcSourcePlugin plugin, String table, @Nullable SourceOffset from) {
        PartitionReader reader = plugin.createReader(new SourcePartition(table, 0, Map.of()), from);
        open.add(reader);
        return reader;
    }

    /** Each distinct row with the sum of its weights; rows summing to zero left out. */
    private static Map<String, Long> zset(List<Captured.Row> rows) {
        Map<String, Long> sums = new TreeMap<>();
        for (Captured.Row row : rows) {
            sums.merge(row.values().toString(), row.weight(), Long::sum);
        }
        sums.values().removeIf(weight -> weight == 0L);
        return sums;
    }

    /** The table, as the Z-set a correct stream sums to: every row at +1. */
    private static Map<String, Long> tableAsZset(String table, Shape shape) {
        Map<String, Long> rows = new TreeMap<>();
        try (Connection connection = PgServer.connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT " + shape.columns() + " FROM " + table)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<Object> values = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    values.add(result.getObject(i));
                }
                rows.merge(values.toString(), 1L, Long::sum);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /** Polls until the reader has finished any snapshot and passed the WAL as it stands now. */
    private static void drainToNow(PartitionReader reader, Captured sink, Random random) {
        long target = CdcOffset.parseLsn(Objects.requireNonNull(PgServer.scalar("SELECT pg_current_wal_lsn()::text")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            CdcOffset at = CdcOffset.parse(reader.position());
            if (!at.inSnapshot() && !at.isPartial() && at.lsn() >= target) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("reader stuck at " + at + ", wanted past " + CdcOffset.format(target));
            }
            if (reader.poll(sink, 1 + random.nextInt(64)) == 0) {
                PgServer.sleep(5);
            }
        }
    }

    @Test
    void rowsAlreadyThereArriveFromTheSnapshotInKeyOrderAndChangesAfterFromTheStreamEachOnce() {
        Shape shape = Shape.BIGINT;
        String table = table(shape);
        PgServer.sql(shape.seed(table, 1, 50));
        PostgresCdcSourcePlugin plugin = plugin(table, Map.of("snapshot.chunk.rows", "7"));
        // After the slot, before the reader: in the log before the snapshot's point, and in the
        // snapshot. Delivered once, from the snapshot, as these changes left the rows.
        PgServer.sql(
                shape.seed(table, 51, 60),
                "UPDATE " + table + " SET tier = 'gold' WHERE id = 1",
                "DELETE FROM " + table + " WHERE id = 2");

        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, table, null);
        drainToNow(reader, sink, new Random(1));
        assertThat(sink.rows()).hasSize(59);
        assertThat(sink.rows()).allSatisfy(row -> assertThat(row.weight()).isEqualTo(1L));
        assertThat(sink.rows())
                .extracting(row -> (Long) row.values().get(0))
                .as("the snapshot in primary-key order, id 2 deleted before it and so never delivered")
                .isSorted()
                .doesNotContain(2L)
                .startsWith(1L, 3L);
        assertThat(sink.rows().get(0).values()).containsExactly(1L, "gold", 1);

        PgServer.sql("UPDATE " + table + " SET tier = 'silver' WHERE id = 3");
        drainToNow(reader, sink, new Random(2));
        assertThat(sink.texts().subList(59, sink.rows().size()))
                .as("after the snapshot, the stream: the update as a retraction and an insertion")
                .containsExactly("-1 [3, seed, 3]", "+1 [3, silver, 3]");
        assertThat(zset(sink.rows())).isEqualTo(tableAsZset(table, shape));
        assertThat(CdcOffset.parse(reader.position()).inSnapshot()).isFalse();
    }

    @Test
    void anEmptyTableFinishesItsSnapshotAtOnceAndStreams() {
        Shape shape = Shape.BIGINT;
        String table = table(shape);
        PostgresCdcSourcePlugin plugin = plugin(table, Map.of());
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, table, null);
        assertThat(CdcOffset.parse(reader.position()).snapshot())
                .as("from nothing: a snapshot, not yet started")
                .isEqualTo(CdcOffset.Snapshot.START);
        drainToNow(reader, sink, new Random(3));
        assertThat(sink.rows()).isEmpty();
        PgServer.sql(shape.seed(table, 1, 3));
        drainToNow(reader, sink, new Random(4));
        assertThat(sink.texts()).containsExactly("+1 [1, seed, 1]", "+1 [2, seed, 2]", "+1 [3, seed, 3]");
    }

    @Test
    void aLargeTableIsReadInChunksNeverMoreThanTwoAheadAndReportsItsProgress() {
        Shape shape = Shape.BIGINT;
        String table = table(shape);
        PgServer.sql(shape.seed(table, 1, 20_000), "ANALYZE " + table);
        PostgresCdcSourcePlugin plugin = plugin(table, Map.of("snapshot.chunk.rows", "500"));
        Captured sink = new Captured(plugin.schema());
        PostgresCdcReader reader = (PostgresCdcReader) reader(plugin, table, null);

        long mostAhead = 0;
        String progress = "";
        long previous = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (CdcOffset.parse(reader.position()).inSnapshot()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            InitialSnapshot snapshot = reader.snapshot();
            if (snapshot != null) {
                mostAhead = Math.max(mostAhead, snapshot.fetched() - sink.rows().size());
            }
            if (sink.rows().size() > 5_000 && progress.isEmpty()) {
                PgServer.sleep(1_100); // past the health cache
                HealthStatus health = plugin.health();
                progress = health.detail();
                assertThat(health.state()).isEqualTo(HealthStatus.State.HEALTHY);
            }
            if (reader.poll(sink, 100) == 0) {
                PgServer.sleep(1);
            }
            assertThat(sink.rows().size()).isGreaterThanOrEqualTo((int) previous);
            previous = sink.rows().size();
        }
        assertThat(sink.rows()).hasSize(20_000);
        assertThat(sink.rows()).extracting(row -> (Long) row.values().get(0)).isSorted();
        assertThat(mostAhead)
                .as("bounded memory: the reading thread stays within two chunks of the engine")
                .isLessThanOrEqualTo(1_000);
        assertThat(progress).containsPattern("initial snapshot in progress: \\d+ rows delivered of about 20000");
        assertThat(zset(sink.rows())).isEqualTo(tableAsZset(table, shape));
    }

    @Test
    void aTableWithoutAPrimaryKeyIsRefusedASnapshotNamingTheFix() {
        String table = PgServer.unique("nokey");
        PgServer.sql(
                "CREATE TABLE " + table + " (id BIGINT, tier TEXT)", "ALTER TABLE " + table + " REPLICA IDENTITY FULL");
        Map<String, String> options = PgServer.options(table);
        options.put("snapshot.mode", "initial");
        assertThatThrownBy(() -> PgServer.open(options))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("needs a primary key")
                .hasMessageContaining("snapshot.mode: never");
    }

    @Test
    void anOffsetWrittenBeforeSnapshotsExistedResumesAsTheStreamItAlwaysWas() {
        Shape shape = Shape.BIGINT;
        String table = table(shape);
        PgServer.sql(shape.seed(table, 1, 10));
        PostgresCdcSourcePlugin plugin = plugin(table, Map.of());
        String confirmed = PgServer.scalar(
                "SELECT confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = '" + table + "'");
        PgServer.sql("INSERT INTO " + table + " VALUES (11, 'late', 0)");
        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, table, new SourceOffset("lsn=" + confirmed));
        drainToNow(reader, sink, new Random(5));
        assertThat(sink.texts())
                .as("a registration checkpointed before this version: no snapshot is taken for it")
                .containsExactly("+1 [11, late, 0]");
    }

    /**
     * A writer changing the table for as long as a snapshot takes -- inserts, updates, deletes, updates
     * that move a row to another key, several at a time in one transaction -- and the view still sums
     * to the table.
     */
    @ParameterizedTest
    @ValueSource(longs = {11, 12, 13, 14, 15})
    void writesDuringTheSnapshotLeaveTheViewEqualToTheTable(long seed) throws Exception {
        runAgainstAWriter(Shape.BIGINT, seed, 0);
    }

    /**
     * The same, with the reader killed three times -- mostly mid-snapshot, with writes going on while
     * it is down -- and restored each time from the last checkpoint, as a node restores a query:
     * whatever it delivered after that checkpoint is lost with it and must come again, exactly once.
     */
    @ParameterizedTest
    @ValueSource(longs = {21, 22, 23, 24, 25})
    void restartsMidSnapshotAreExactlyOnce(long seed) throws Exception {
        runAgainstAWriter(Shape.BIGINT, seed, 3);
    }

    /** As above, ordered by a composite key whose text sorts by locale rather than code point. */
    @ParameterizedTest
    @ValueSource(longs = {31, 32, 33})
    void restartsMidSnapshotAreExactlyOnceUnderACompositeCollatedKey(long seed) throws Exception {
        runAgainstAWriter(Shape.COMPOSITE_TEXT, seed, 3);
    }

    private void runAgainstAWriter(Shape shape, long seed, int crashes) throws Exception {
        Random random = new Random(seed);
        String table = table(shape);
        if (shape == Shape.COMPOSITE_TEXT) {
            assertThat(PgServer.scalar("SELECT string_agg(c, '' ORDER BY c COLLATE \"und-x-icu\") "
                            + "FROM unnest(ARRAY['B', 'a', '_x', 'Z']) AS c"))
                    .as("the key's collation really is not code-point order")
                    .isEqualTo("_xaBZ");
        }
        long keys = 4_000;
        PgServer.sql(shape.seed(table, 1, 2_500));
        PostgresCdcSourcePlugin plugin = plugin(table, Map.of("snapshot.chunk.rows", "120"));
        Writer writer = new Writer(shape, table, keys, seed);
        Thread writing = Thread.ofPlatform().daemon().start(writer);
        open.add(writer::stop);
        // Let changes land between the slot and the reader: the log before the snapshot's point.
        PgServer.sleep(100);

        Captured sink = new Captured(plugin.schema());
        PartitionReader reader = reader(plugin, table, null);
        SourceOffset checkpoint = reader.position();
        int checkpointedRows = 0;
        int crashed = 0;
        int crashedWithAFrontier = 0;
        int nextCrash = crashes == 0 ? Integer.MAX_VALUE : random.nextInt(20);
        int polls = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (crashed < crashes || CdcOffset.parse(reader.position()).inSnapshot()) {
            assertThat(System.nanoTime()).as("seed " + seed + " did not finish").isLessThan(deadline);
            if (reader.poll(sink, 1 + random.nextInt(40)) == 0) {
                PgServer.sleep(2);
            }
            polls++;
            if (random.nextInt(4) == 0) {
                // A checkpoint: the engine's state is everything delivered so far.
                checkpoint = reader.position();
                checkpointedRows = sink.rows().size();
                if (random.nextBoolean()) {
                    reader.checkpointed(checkpoint);
                }
            }
            if (crashed < crashes && polls >= nextCrash) {
                reader.close();
                // Lost with the process: what was delivered after the checkpoint.
                sink.rows().subList(checkpointedRows, sink.rows().size()).clear();
                CdcOffset from = CdcOffset.parse(checkpoint);
                CdcOffset.Snapshot inside = from.snapshot();
                if (inside != null && inside.started()) {
                    crashedWithAFrontier++;
                }
                PgServer.sleep(random.nextInt(300)); // the writer carries on while nothing reads
                reader = reader(plugin, table, checkpoint);
                crashed++;
                polls = 0;
                nextCrash = 5 + random.nextInt(40);
            }
        }
        writer.stop();
        writing.join(10_000);
        drainToNow(reader, sink, random);

        assertThat(writer.applied.get())
                .as("the writer really wrote during the run")
                .isGreaterThan(20);
        if (crashes > 0) {
            assertThat(crashedWithAFrontier)
                    .as("seed " + seed + ": at least one restore was from half-way through the snapshot")
                    .isPositive();
        }
        Map<String, Long> view = zset(sink.rows());
        Map<String, Long> expected = tableAsZset(table, shape);
        assertThat(view.values())
                .as("seed " + seed + ": every row at exactly +1 -- none twice, no retraction left over")
                .allMatch(weight -> weight == 1L);
        assertThat(view).as("seed " + seed + ": the view is the table").isEqualTo(expected);
    }

    /** Random changes to the table, one transaction at a time, until stopped. */
    private static final class Writer implements Runnable {

        private final Shape shape;
        private final String table;
        private final long keys;
        private final Random random;
        private final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicLong applied = new AtomicLong();

        Writer(Shape shape, String table, long keys, long seed) {
            this.shape = shape;
            this.table = table;
            this.keys = keys;
            this.random = new Random(seed * 7919);
        }

        void stop() {
            running.set(false);
        }

        @Override
        public void run() {
            try (Connection connection = PgServer.connect()) {
                while (running.get()) {
                    int statements = 1 + (random.nextInt(4) == 0 ? random.nextInt(4) : 0);
                    connection.setAutoCommit(statements == 1);
                    try (Statement statement = connection.createStatement()) {
                        for (int i = 0; i < statements; i++) {
                            statement.execute(next());
                        }
                        if (statements > 1) {
                            connection.commit();
                        }
                        applied.incrementAndGet();
                    } catch (SQLException conflict) {
                        // A key moved onto one already taken: that transaction did not happen.
                        if (statements > 1) {
                            connection.rollback();
                        }
                    }
                    if (random.nextInt(3) == 0) {
                        PgServer.sleep(1);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private String next() {
            long k = 1L + random.nextInt((int) keys);
            return switch (random.nextInt(5)) {
                case 0 ->
                    "INSERT INTO " + table + " (" + shape.columns() + ") VALUES (" + shape.values(k) + ", 'new', "
                            + random.nextInt(100) + ") ON CONFLICT DO NOTHING";
                case 1, 2 ->
                    "UPDATE " + table + " SET tier = 't" + random.nextInt(5) + "', amount = "
                            + (random.nextBoolean() ? "NULL" : Integer.toString(random.nextInt(100))) + " WHERE "
                            + shape.where(k);
                case 3 -> "DELETE FROM " + table + " WHERE " + shape.where(k);
                default ->
                    "UPDATE " + table + " SET " + shape.set(1 + random.nextInt((int) keys)) + " WHERE "
                            + shape.where(k);
            };
        }
    }

    @Test
    void aStreamOnlyBindingStillIgnoresRowsAlreadyThere() {
        Shape shape = Shape.BIGINT;
        String table = table(shape);
        PgServer.sql(shape.seed(table, 1, 5));
        PostgresCdcSourcePlugin plugin = plugin(table, new HashMap<>(Map.of("snapshot.mode", "never")));
        PgServer.sql("INSERT INTO " + table + " VALUES (6, 'late', 0)");
        Captured sink = new Captured(plugin.schema());
        drainToNow(reader(plugin, table, null), sink, new Random(6));
        assertThat(sink.texts()).containsExactly("+1 [6, late, 0]");
    }
}

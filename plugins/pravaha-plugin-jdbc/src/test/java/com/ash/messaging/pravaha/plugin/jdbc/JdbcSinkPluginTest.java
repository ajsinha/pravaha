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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JDBC sink against H2: the write path in both dialects H2 can stand in for, every type, the
 * refusals, and the transactional protocol including the crashes it exists for.
 *
 * <p>A "crash" is a sink instance closed without the call that would have followed, and a restart
 * is a new instance over the same database -- which is exactly what the engine's restore does: a
 * new process, a new connection, the handles the checkpoint recorded.
 */
class JdbcSinkPluginTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static final String TOTALS_SCHEMA = "user_id:STRING,total:INT64";

    private final SinkTestRows rows = new SinkTestRows();
    private final List<JdbcSinkPlugin> opened = new ArrayList<>();
    private String url;
    private Connection admin;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:sink" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        admin = DriverManager.getConnection(url);
        execute("CREATE TABLE totals (user_id VARCHAR(32) NOT NULL PRIMARY KEY, total BIGINT)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        opened.forEach(JdbcSinkPlugin::close);
        admin.close();
        rows.close();
    }

    // ---------------------------------------------------------------- upsert and retraction

    @Test
    void anUpsertInsertsARecordAndThenReplacesItByKey() throws SQLException {
        JdbcSinkPlugin sink = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();

        sink.write(List.of(row(schema, 1, "u1", 300L), row(schema, 1, "u2", 50L)));
        assertThat(table("totals")).containsExactly("u1|300", "u2|50");

        sink.write(List.of(row(schema, -1, "u1", 300L), row(schema, 1, "u1", 375L)));
        assertThat(table("totals")).containsExactly("u1|375", "u2|50");
        assertThat(sink.dialect()).isEqualTo(JdbcDialect.H2);
    }

    /**
     * A retraction deletes the record its key names; nothing else would keep the table equal to a
     * view the query has withdrawn a row from.
     */
    @Test
    void aRetractionDeletesTheRecordItsKeyNames() throws SQLException {
        JdbcSinkPlugin sink = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.write(List.of(row(schema, 1, "u1", 300L), row(schema, 1, "u2", 50L)));

        sink.write(List.of(row(schema, -1, "u2", 50L)));

        assertThat(table("totals")).containsExactly("u1|300");
    }

    @Test
    void theLastChangeToAKeyInABatchIsWhatTheTableHoldsWhateverCameBefore() throws SQLException {
        JdbcSinkPlugin sink = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();

        sink.write(List.of(
                row(schema, 1, "u1", 1L),
                row(schema, -1, "u1", 1L),
                row(schema, 1, "u1", 2L),
                row(schema, 1, "u2", 9L),
                row(schema, -1, "u2", 9L)));

        assertThat(table("totals")).containsExactly("u1|2");
    }

    @Test
    void aCompositeKeyDeletesOnlyTheRecordBothColumnsName() throws SQLException {
        execute("CREATE TABLE by_region (region VARCHAR(8) NOT NULL, user_id VARCHAR(8) NOT NULL, total BIGINT, "
                + "PRIMARY KEY (region, user_id))");
        JdbcSinkPlugin sink = open(
                "by_region",
                "region:STRING,user_id:STRING,total:INT64",
                Map.of("key.columns", "region, user_id", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();
        assertThat(sink.keyColumns()).containsExactly("region", "user_id");

        sink.write(List.of(
                row(schema, 1, "EU", "u1", 1L), row(schema, 1, "US", "u1", 2L), row(schema, 1, "EU", "u2", 3L)));
        sink.write(List.of(row(schema, -1, "EU", "u1", 1L), row(schema, 1, "US", "u1", 20L)));

        assertThat(table("by_region")).containsExactlyInAnyOrder("EU|u2|3", "US|u1|20");
    }

    /** The fallback: UPDATE, then INSERT where the update found nothing -- here run on H2 by name. */
    @Test
    void thePortableDialectUpdatesThenInsertsAndDeletesByKey() throws SQLException {
        JdbcSinkPlugin sink =
                open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transactional", "false", "dialect", "portable"));
        StreamSchema schema = sink.schema().orElseThrow();
        assertThat(sink.dialect()).isEqualTo(JdbcDialect.PORTABLE);

        sink.write(List.of(row(schema, 1, "u1", 300L), row(schema, 1, "u2", 50L)));
        sink.write(List.of(row(schema, -1, "u1", 300L), row(schema, 1, "u1", 375L), row(schema, 1, "u3", 7L)));
        sink.write(List.of(row(schema, -1, "u2", 50L)));

        assertThat(table("totals")).containsExactly("u1|375", "u3|7");
    }

    @Test
    void thePortableDialectHandlesATableThatIsAllKey() throws SQLException {
        execute("CREATE TABLE members (group_id BIGINT NOT NULL, user_id VARCHAR(8) NOT NULL, "
                + "PRIMARY KEY (group_id, user_id))");
        JdbcSinkPlugin sink = open(
                "members",
                "group_id:INT64,user_id:STRING",
                Map.of("key.columns", "group_id,user_id", "transactional", "false", "dialect", "portable"));
        StreamSchema schema = sink.schema().orElseThrow();

        sink.write(List.of(row(schema, 1, 1L, "a"), row(schema, 1, 1L, "b")));
        sink.write(List.of(row(schema, 1, 1L, "a"), row(schema, -1, 1L, "b")));

        assertThat(table("members")).containsExactly("1|a");
    }

    // ---------------------------------------------------------------- append

    @Test
    void appendModeInsertsEveryRowAndDeclaresOnlyAppend() throws SQLException {
        execute("CREATE TABLE events (user_id VARCHAR(32) NOT NULL, amount BIGINT)");
        JdbcSinkPlugin sink =
                open("events", "user_id:STRING,amount:INT64", Map.of("mode", "append", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();

        sink.write(List.of(row(schema, 1, "u1", 1L), row(schema, 1, "u1", 1L), row(schema, 2, "u2", 5L)));

        assertThat(table("events")).containsExactly("u1|1", "u1|1", "u2|5", "u2|5");
        SinkCapabilities capabilities = sink.capabilities();
        assertThat(capabilities.emitModes()).containsExactly(EmitMode.APPEND);
        assertThat(capabilities.idempotentUpsert()).isFalse();
        assertThat(sink.keyColumns()).isEmpty();
        assertThatThrownBy(() -> sink.write(List.of(row(schema, -1, "u1", 1L))))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("append-mode sink was sent a retraction");
    }

    @Test
    void appendModeRefusesAKeyAndUpsertModeRequiresOne() {
        execute("CREATE TABLE events (user_id VARCHAR(32) NOT NULL, amount BIGINT)");
        assertThatThrownBy(() -> configure(
                        "events", "user_id:STRING,amount:INT64", Map.of("mode", "append", "key.columns", "user_id")))
                .hasMessageContaining("append mode");
        assertThatThrownBy(() -> configure("totals", TOTALS_SCHEMA, Map.of()))
                .hasMessageContaining("needs key.columns");
        assertThatThrownBy(() -> configure("totals", TOTALS_SCHEMA, Map.of("key.columns", "nobody")))
                .hasMessageContaining("not in the declared schema");
        assertThatThrownBy(() -> configure("totals", "user_id:STRING,total:FLOAT64", Map.of("key.columns", "total")))
                .hasMessageContaining("floating-point key");
        assertThatThrownBy(() -> configure("totals", "user_id:STRING?,total:INT64", Map.of("key.columns", "user_id")))
                .hasMessageContaining("declared nullable");
        assertThatThrownBy(() -> configure("totals", TOTALS_SCHEMA, Map.of("key.columns", "user_id", "mode", "merge")))
                .hasMessageContaining("not upsert or append");
    }

    @Test
    void upsertModeDeclaresARevisingIdempotentTransactionalSinkBeforeItIsOpened() {
        JdbcSinkPlugin sink = configure("totals", TOTALS_SCHEMA, Map.of("key.columns", "USER_ID"));

        SinkCapabilities capabilities = sink.capabilities();
        assertThat(capabilities.emitModes()).containsExactlyInAnyOrder(EmitMode.UPSERT, EmitMode.RETRACT);
        assertThat(capabilities.transactional()).isTrue();
        assertThat(capabilities.idempotentUpsert()).isTrue();
        assertThat(sink.schema().orElseThrow().fieldCount()).isEqualTo(2);
        assertThat(sink.keyColumns()).containsExactly("USER_ID");
        assertThat(configure("totals", TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transactional", "false"))
                        .capabilities()
                        .transactional())
                .isFalse();
    }

    @Test
    void theSharedTlsOptionsAreRefusedAsTheSourceRefusesThem() {
        assertThatThrownBy(() ->
                        configure("totals", TOTALS_SCHEMA, Map.of("key.columns", "user_id", "tls.enabled", "true")))
                .hasMessageContaining("TLS settings in the URL");
    }

    // ---------------------------------------------------------------- types

    @Test
    void everySupportedTypeIsWrittenAsItsColumnHoldsItAndNullsAsNull() throws SQLException {
        execute("CREATE TABLE every_type (id BIGINT NOT NULL PRIMARY KEY, b BOOLEAN, i8 TINYINT, i16 SMALLINT, "
                + "i32 INTEGER, f32 REAL, f64 DOUBLE PRECISION, amt NUMERIC(12,2), s VARCHAR(64), raw VARBINARY(16), "
                + "d DATE, t TIME(9), ts TIMESTAMP(9), tstz TIMESTAMP(9) WITH TIME ZONE)");
        String spec = "id:INT64,b:BOOLEAN?,i8:INT8?,i16:INT16?,i32:INT32?,f32:FLOAT32?,f64:FLOAT64?,"
                + "amt:DECIMAL(12,2)?,s:STRING?,raw:BYTES?,d:DATE?,t:TIME?,ts:TIMESTAMP?,tstz:TIMESTAMP?";
        for (boolean transactional : new boolean[] {false, true}) {
            execute("DELETE FROM every_type");
            JdbcSinkPlugin sink = open(
                    "every_type", spec, Map.of("key.columns", "id", "transactional", String.valueOf(transactional)));
            StreamSchema schema = sink.schema().orElseThrow();
            long nanos = LocalDateTime.of(2026, 9, 19, 10, 15, 30, 123_456_789).toEpochSecond(ZoneOffset.UTC)
                            * 1_000_000_000L
                    + 123_456_789L;
            long timeOfDay = LocalTime.of(23, 59, 58, 987_654_321).toNanoOfDay();
            int day = (int) LocalDate.of(2026, 9, 19).toEpochDay();
            sink.beginTransaction(1);
            sink.write(List.of(
                    row(
                            schema,
                            1,
                            1L,
                            true,
                            (byte) -7,
                            (short) 30_000,
                            2_000_000_000,
                            1.5f,
                            2.25d,
                            new BigDecimal("1234567890.12"),
                            "héllo",
                            new byte[] {1, 2, 3},
                            day,
                            timeOfDay,
                            nanos,
                            nanos),
                    row(schema, 1, 2L, null, null, null, null, null, null, null, null, null, null, null, null, null)));
            sink.commit(sink.prepare(1));

            try (Statement statement = admin.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT * FROM every_type ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("b")).isTrue();
                assertThat(rs.getByte("i8")).isEqualTo((byte) -7);
                assertThat(rs.getShort("i16")).isEqualTo((short) 30_000);
                assertThat(rs.getInt("i32")).isEqualTo(2_000_000_000);
                assertThat(rs.getFloat("f32")).isEqualTo(1.5f);
                assertThat(rs.getDouble("f64")).isEqualTo(2.25d);
                assertThat(rs.getBigDecimal("amt")).isEqualByComparingTo("1234567890.12");
                assertThat(rs.getString("s")).isEqualTo("héllo");
                assertThat(rs.getBytes("raw")).containsExactly(1, 2, 3);
                assertThat(rs.getObject("d", LocalDate.class)).isEqualTo(LocalDate.of(2026, 9, 19));
                assertThat(rs.getObject("t", LocalTime.class)).isEqualTo(LocalTime.of(23, 59, 58, 987_654_321));
                assertThat(rs.getObject("ts", LocalDateTime.class))
                        .as("a zoneless column holds the UTC wall clock, whatever the session's zone")
                        .isEqualTo(LocalDateTime.of(2026, 9, 19, 10, 15, 30, 123_456_789));
                assertThat(rs.getObject("tstz", OffsetDateTime.class).toInstant())
                        .isEqualTo(LocalDateTime.of(2026, 9, 19, 10, 15, 30, 123_456_789)
                                .toInstant(ZoneOffset.UTC));
                assertThat(rs.next()).isTrue();
                for (String column :
                        List.of("b", "i8", "i16", "i32", "f32", "f64", "amt", "s", "raw", "d", "t", "ts", "tstz")) {
                    assertThat(rs.getObject(column)).as(column + " stays null").isNull();
                }
            }
            sink.close();
        }
    }

    /**
     * The registry compares a sink's schema with the query's by type name, not scale; the value is
     * read at the scale the row was written with, or every amount would be off by a power of ten.
     */
    @Test
    void aDecimalIsReadAtTheScaleTheRowWasWrittenWithNotTheDeclaredOne() throws SQLException {
        execute("CREATE TABLE ledger (id BIGINT NOT NULL PRIMARY KEY, amount NUMERIC(20,9))");
        JdbcSinkPlugin sink =
                open("ledger", "id:INT64,amount:DECIMAL(10,2)", Map.of("key.columns", "id", "transactional", "false"));
        StreamSchema written = StreamSchema.builder("q")
                .field("id", com.ash.messaging.pravaha.api.data.Types.int64())
                .field("amount", com.ash.messaging.pravaha.api.data.Types.decimal(38, 9))
                .build();

        sink.write(List.of(row(written, 1, 1L, new BigDecimal("12.345678901"))));

        assertThat(table("ledger")).containsExactly("1|12.345678901");
    }

    // ---------------------------------------------------------------- refusals at open

    @Test
    void aTableThatDoesNotExistIsRefusedWithACode() {
        assertThatThrownBy(() -> open("nowhere", TOTALS_SCHEMA, Map.of("key.columns", "user_id")))
                .isInstanceOf(PravahaException.class)
                .satisfies(
                        e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(JdbcErrors.SINK_TABLE_MISMATCH))
                .hasMessageContaining("'nowhere' does not exist");
    }

    @Test
    void aColumnThatDoesNotExistIsRefusedByName() {
        assertThatThrownBy(() -> open("user_id:STRING,amount:INT64", Map.of("key.columns", "user_id")))
                .isInstanceOf(PravahaException.class)
                .satisfies(
                        e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(JdbcErrors.SINK_TABLE_MISMATCH))
                .hasMessageContaining("no column 'amount'")
                .hasMessageContaining("TOTAL");
    }

    @Test
    void aColumnThatCannotHoldTheDeclaredTypeIsRefused() {
        execute("CREATE TABLE narrow (id INTEGER NOT NULL PRIMARY KEY, f REAL, amount NUMERIC(10,2), due DATE, "
                + "label VARCHAR(8) NOT NULL)");
        assertThatThrownBy(() -> open(
                        "narrow",
                        "id:INT64,f:FLOAT32,amount:DECIMAL(10,2),due:DATE,label:STRING",
                        Map.of("key.columns", "id")))
                .hasMessageContaining("column 'ID'")
                .hasMessageContaining("cannot hold the INT64");
        assertThatThrownBy(() -> open(
                        "narrow",
                        "id:INT32,f:FLOAT64,amount:DECIMAL(10,2),due:DATE,label:STRING",
                        Map.of("key.columns", "id")))
                .hasMessageContaining("column 'F'");
        assertThatThrownBy(() -> open(
                        "narrow",
                        "id:INT32,f:FLOAT32,amount:DECIMAL(12,4),due:DATE,label:STRING",
                        Map.of("key.columns", "id")))
                .hasMessageContaining("NUMERIC(10,2)");
        assertThatThrownBy(() -> open(
                        "narrow",
                        "id:INT32,f:FLOAT32,amount:DECIMAL(10,2),due:TIMESTAMP,label:STRING",
                        Map.of("key.columns", "id")))
                .hasMessageContaining("column 'DUE'");
        assertThatThrownBy(() -> open(
                        "narrow",
                        "id:INT32,f:FLOAT32,amount:DECIMAL(10,2),due:DATE,label:STRING?",
                        Map.of("key.columns", "id")))
                .hasMessageContaining("NOT NULL, so the first null would fail");
        assertThat(open(
                        "narrow",
                        "id:INT32,f:FLOAT32,amount:DECIMAL(8,1),due:DATE,label:STRING",
                        Map.of("key.columns", "id")))
                .as("narrower declarations fit")
                .isNotNull();
    }

    @Test
    void aNotNullColumnTheSchemaDoesNotWriteIsRefused() {
        execute("CREATE TABLE extra (id BIGINT NOT NULL PRIMARY KEY, owner VARCHAR(8) NOT NULL, note VARCHAR(8), "
                + "stamp VARCHAR(8) DEFAULT 'x' NOT NULL)");
        assertThatThrownBy(() -> open("extra", "id:INT64", Map.of("key.columns", "id")))
                .hasMessageContaining("'OWNER', which is NOT NULL, has no default");
    }

    @Test
    void identifiersAreFoundWhateverCaseTheyWereConfiguredInAndQuotedWhenWritten() throws SQLException {
        execute("CREATE TABLE \"Mixed Case\" (\"Key\" VARCHAR(8) NOT NULL PRIMARY KEY, \"order\" BIGINT)");
        JdbcSinkPlugin sink =
                open("Mixed Case", "key:STRING,ORDER:INT64", Map.of("key.columns", "key", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();

        sink.write(List.of(row(schema, 1, "a", 1L)));
        sink.write(List.of(row(schema, 1, "a", 2L)));

        assertThat(table("\"Mixed Case\"")).containsExactly("a|2");
        assertThat(open("PUBLIC.TOTALS", TOTALS_SCHEMA, Map.of("key.columns", "user_id")))
                .isNotNull();
    }

    // ---------------------------------------------------------------- the transactional protocol

    @Test
    void nothingIsVisibleUntilCommitAndThenAllOfItIs() throws SQLException {
        JdbcSinkPlugin sink = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id"));
        StreamSchema schema = sink.schema().orElseThrow();

        sink.beginTransaction(1);
        sink.write(List.of(row(schema, 1, "u1", 300L)));
        sink.write(List.of(row(schema, 1, "u2", 50L)));
        sink.flush();
        assertThat(table("totals")).as("written into the open transaction").isEmpty();
        assertThat(staged()).isEqualTo(2);

        String handle = sink.prepare(1);
        sink.beginTransaction(2);
        sink.write(List.of(row(schema, -1, "u1", 300L), row(schema, 1, "u1", 375L)));
        assertThat(table("totals")).as("prepared is not visible").isEmpty();

        sink.commit(handle);
        assertThat(table("totals")).containsExactly("u1|300", "u2|50");
        assertThat(staged()).as("the next transaction's batch is still staged").isEqualTo(1);

        sink.commit(sink.prepare(2));
        assertThat(table("totals")).containsExactly("u1|375", "u2|50");
        assertThat(staged()).isZero();
    }

    /** Idempotent commit, proven where a second application would show: an append-only table. */
    @Test
    void committingAHandleTwiceWritesItsRowsOnce() throws SQLException {
        execute("CREATE TABLE events (user_id VARCHAR(32) NOT NULL, amount BIGINT)");
        JdbcSinkPlugin sink = open("events", "user_id:STRING,amount:INT64", Map.of("mode", "append"));
        StreamSchema schema = sink.schema().orElseThrow();
        assertThat(sink.capabilities().transactional()).isTrue();

        sink.beginTransaction(1);
        sink.write(List.of(row(schema, 1, "u1", 1L), row(schema, 1, "u2", 2L)));
        String handle = sink.prepare(1);
        sink.commit(handle);
        sink.commit(handle);

        assertThat(table("events")).containsExactly("u1|1", "u2|2");
    }

    /**
     * The crash two-phase commit exists for: the checkpoint recording the handle is durable, and the
     * process dies before the commit reaches the database. A new process -- a new connection -- is
     * handed the handle by the restore and commits it; the restore may commit it again later, since
     * it cannot know the first arrived.
     */
    @Test
    void aCrashBetweenPrepareAndCommitLosesNothingAndRepeatsNothing() throws SQLException {
        execute("CREATE TABLE events (user_id VARCHAR(32) NOT NULL, amount BIGINT)");
        String spec = "user_id:STRING,amount:INT64";
        JdbcSinkPlugin before = open("events", spec, Map.of("mode", "append"));
        StreamSchema schema = before.schema().orElseThrow();
        before.beginTransaction(1);
        before.write(List.of(row(schema, 1, "u1", 300L), row(schema, 1, "u2", 50L)));
        String recorded = before.prepare(1);
        // And after the cut, into the next transaction: the replay will deliver this again.
        before.beginTransaction(2);
        before.write(List.of(row(schema, 1, "u3", 7L)));
        before.close();
        assertThat(table("events")).as("the process died before the commit").isEmpty();

        JdbcSinkPlugin after = open("events", spec, Map.of("mode", "append"));
        after.commit(recorded);
        after.abortAfter(1);
        assertThat(table("events")).containsExactly("u1|300", "u2|50");
        assertThat(staged()).as("the dead process's open transaction is gone").isZero();

        after.beginTransaction(2);
        after.write(List.of(row(schema, 1, "u3", 7L)));
        after.commit(recorded);
        after.commit(after.prepare(2));

        assertThat(table("events"))
                .as("every row exactly once: none lost with the dead process, none repeated by the replay")
                .containsExactly("u1|300", "u2|50", "u3|7");
    }

    /**
     * A commit that fails half way leaves nothing behind: the rows it applied and the staging rows it
     * deleted are rolled back together, so the next commit does all of it once.
     */
    @Test
    void aCommitThatFailsPartWayAppliesNothingAndTheNextCommitAppliesEverythingOnce() throws SQLException {
        execute("CREATE TABLE events (user_id VARCHAR(32) NOT NULL, amount BIGINT)");
        JdbcSinkPlugin sink = open("events", "user_id:STRING,amount:INT64", Map.of("mode", "append"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(1);
        sink.write(List.of(row(schema, 1, "u1", 1L), row(schema, 1, "u2", 2L)));
        sink.write(List.of(row(schema, 1, "u3", -3L)));
        String handle = sink.prepare(1);
        execute("ALTER TABLE events ADD CONSTRAINT positive CHECK (amount > 0)");

        assertThatThrownBy(() -> sink.commit(handle))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(JdbcErrors.WRITE_FAILED));
        assertThat(table("events"))
                .as("the first batch was applied and rolled back")
                .isEmpty();
        assertThat(staged()).isEqualTo(2);

        execute("ALTER TABLE events DROP CONSTRAINT positive");
        sink.commit(handle);
        assertThat(table("events")).containsExactly("u1|1", "u2|2", "u3|-3");
    }

    @Test
    void abortAfterDiscardsLaterLabelsAndAbortDiscardsOne() throws SQLException {
        JdbcSinkPlugin sink = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(3);
        sink.write(List.of(row(schema, 1, "u1", 1L)));
        String three = sink.prepare(3);
        sink.beginTransaction(4);
        sink.write(List.of(row(schema, 1, "u2", 2L)));
        String four = sink.prepare(4);
        sink.beginTransaction(5);
        sink.write(List.of(row(schema, 1, "u3", 3L)));

        sink.abortAfter(3);
        assertThat(staged()).isEqualTo(1);
        sink.commit(four);
        assertThat(table("totals")).as("an aborted transaction commits nothing").isEmpty();

        sink.abort(three);
        sink.commit(three);
        assertThat(table("totals")).isEmpty();
        assertThat(staged()).isZero();
    }

    @Test
    void aHandleFromAnotherTransactionIdIsRefused() {
        JdbcSinkPlugin one = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transaction.id", "one"));
        one.beginTransaction(1);
        String handle = one.prepare(1);
        JdbcSinkPlugin two = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "transaction.id", "two"));

        assertThatThrownBy(() -> two.commit(handle)).hasMessageContaining("belongs to transaction.id 'one'");
        assertThatThrownBy(() -> two.commit("somebody-else")).hasMessageContaining("not a handle this sink wrote");
    }

    @Test
    void beginningALabelDiscardsWhatADeadProcessLeftUnderIt() throws SQLException {
        JdbcSinkPlugin before = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id"));
        StreamSchema schema = before.schema().orElseThrow();
        before.beginTransaction(7);
        before.write(List.of(row(schema, 1, "ghost", 1L)));
        before.close();

        JdbcSinkPlugin after = open(TOTALS_SCHEMA, Map.of("key.columns", "user_id"));
        after.beginTransaction(7);
        after.write(List.of(row(schema, 1, "u1", 1L)));
        after.commit(after.prepare(7));

        assertThat(table("totals")).containsExactly("u1|1");
    }

    @Test
    void theStagingTableIsCreatedWhenMissingUnderTheConfiguredName() throws SQLException {
        open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "staging.table", "my_staging"));
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM my_staging")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        assertThatThrownBy(() -> configure(
                        "totals",
                        TOTALS_SCHEMA,
                        Map.of("key.columns", "user_id", "staging.table", "x; DROP TABLE totals")))
                .hasMessageContaining("plain name");
    }

    // ---------------------------------------------------------------- helpers

    @Test
    void preparedCommitModeIsRefusedWhereItCannotWork() {
        // H2 is not PostgreSQL: refused at open, before a row moves, with the mode that does work named.
        assertThatThrownBy(() -> open(TOTALS_SCHEMA, Map.of("key.columns", "user_id", "commit.mode", "prepared")))
                .hasMessageContaining("PRV-5074")
                .hasMessageContaining("commit.mode: staging");
        assertThatThrownBy(() -> configure(
                        "totals",
                        TOTALS_SCHEMA,
                        Map.of("mode", "append", "commit.mode", "prepared", "transactional", "false")))
                .hasMessageContaining("PRV-5074");
        assertThatThrownBy(
                        () -> configure("totals", TOTALS_SCHEMA, Map.of("mode", "append", "commit.mode", "twophase")))
                .hasMessageContaining("'staging' or 'prepared'");
    }

    private JdbcSinkPlugin configure(String table, String spec, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("url", url, "table", table, "schema", spec));
        config.putAll(extra);
        JdbcSinkPlugin sink = new JdbcSinkPlugin();
        sink.configure(new Ctx("totals_sink", config));
        return sink;
    }

    private JdbcSinkPlugin open(String spec, Map<String, String> extra) {
        return open("totals", spec, extra);
    }

    private JdbcSinkPlugin open(String table, String spec, Map<String, String> extra) {
        JdbcSinkPlugin sink = configure(table, spec, extra);
        sink.open();
        opened.add(sink);
        return sink;
    }

    private void execute(String sql) {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int staged() throws SQLException {
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + JdbcSinkPlugin.DEFAULT_STAGING_TABLE)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** The table's rows as {@code a|b|c}, ordered by every column. */
    private List<String> table(String name) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT * FROM " + name)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> values = new ArrayList<>();
                for (int c = 1; c <= columns; c++) {
                    Object value = rs.getObject(c);
                    values.add(value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value));
                }
                rows.add(String.join("|", values));
            }
        }
        rows.sort(null);
        return rows;
    }

    /** A real binary row, laid out the way the engine hands one to a sink. */
    private RowView row(StreamSchema schema, long weight, @Nullable Object... values) {
        return rows.row(schema, weight, values);
    }
}

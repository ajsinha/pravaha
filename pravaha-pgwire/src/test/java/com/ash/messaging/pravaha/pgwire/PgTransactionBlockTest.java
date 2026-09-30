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
package com.ash.messaging.pravaha.pgwire;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PGWIRE-TX-1: transaction control as accepted no-ops with PostgreSQL's protocol state -- the tag,
 * the {@code ReadyForQuery} status byte, and the failed-block rule -- over raw protocol bytes, on
 * both query protocols. {@code JdbcTransactionTest} is the same claim made by a real driver.
 */
class PgTransactionBlockTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private PravahaPgWireServer server;
    private PgTestClient client;

    @BeforeEach
    void setUp() throws IOException {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 100);
        view.commit(100);
        server = new PravahaPgWireServer(new ViewCatalog().register(view)).start("127.0.0.1", 0);
        client = new PgTestClient(server.port());
        client.startup(Map.of("user", "anyone", "database", "pravaha"));
        assertThat(status(client.readHandshake())).isEqualTo('I');
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.close();
    }

    // ------------------------------------------------------------------ simple protocol

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "BEGIN|BEGIN",
                "begin work|BEGIN",
                "BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED|BEGIN",
                "BEGIN ISOLATION LEVEL SERIALIZABLE, READ ONLY, DEFERRABLE|BEGIN",
                "BEGIN READ WRITE|BEGIN",
                "START TRANSACTION|START TRANSACTION",
                "START TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY NOT DEFERRABLE|START TRANSACTION",
            })
    void everyWayToOpenABlockAnswersItsTagAndStatusT(String sql, String tag) throws Exception {
        List<PgTestClient.Message> reply = run(sql);
        assertThat(tag(reply)).isEqualTo(tag);
        assertThat(status(reply)).isEqualTo('T');

        reply = run("SELECT total FROM user_volume");
        assertThat(PgTestClient.shape(reply)).isEqualTo("TDCZ");
        assertThat(status(reply)).isEqualTo('T');

        reply = run("COMMIT");
        assertThat(tag(reply)).isEqualTo("COMMIT");
        assertThat(status(reply)).isEqualTo('I');
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "COMMIT|COMMIT",
                "END|COMMIT",
                "commit work|COMMIT",
                "ROLLBACK|ROLLBACK",
                "ABORT|ROLLBACK",
                "ROLLBACK TRANSACTION|ROLLBACK",
                "COMMIT AND NO CHAIN|COMMIT"
            })
    void everyWayToEndABlockAnswersItsTagAndStatusI(String sql, String tag) throws Exception {
        run("BEGIN");
        List<PgTestClient.Message> reply = run(sql);
        assertThat(tag(reply)).isEqualTo(tag);
        assertThat(status(reply)).isEqualTo('I');
    }

    @Test
    void endingABlockThatIsNotOpenWarnsAsPostgresDoesAndStaysIdle() throws Exception {
        List<PgTestClient.Message> reply = run("COMMIT");
        assertThat(PgTestClient.shape(reply)).isEqualTo("NCZ");
        assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'N').get(0)))
                .containsEntry('S', "WARNING")
                .containsEntry('C', "25P01")
                .containsEntry('M', "there is no transaction in progress");
        assertThat(tag(reply)).isEqualTo("COMMIT");
        assertThat(status(reply)).isEqualTo('I');

        assertThat(tag(run("ROLLBACK"))).isEqualTo("ROLLBACK");
    }

    @Test
    void aSecondBeginWarnsAndStaysInTheBlock() throws Exception {
        run("BEGIN");
        List<PgTestClient.Message> reply = run("BEGIN");
        assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'N').get(0)))
                .containsEntry('C', "25001")
                .containsEntry('M', "there is already a transaction in progress");
        assertThat(status(reply)).isEqualTo('T');
    }

    @Test
    void anErrorInsideABlockFailsItUntilRollbackAndCommitThenReportsRollback() throws Exception {
        run("BEGIN");
        List<PgTestClient.Message> reply = run("SELECT * FROM no_such_view");
        assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
        assertThat(status(reply)).isEqualTo('E');

        // Anything but an exit is refused with 25P02, and the block stays failed -- a read, a SET, a
        // SHOW and a savepoint alike.
        for (String refused : List.of(
                "SELECT total FROM user_volume",
                "SET application_name = 'x'",
                "SHOW transaction_isolation",
                "SAVEPOINT a",
                "BEGIN")) {
            reply = run(refused);
            assertThat(sqlState(reply)).as(refused).isEqualTo("25P02");
            assertThat(error(reply)).contains("PRV-6212").contains("current transaction is aborted");
            assertThat(status(reply)).as(refused).isEqualTo('E');
        }

        reply = run("COMMIT");
        assertThat(tag(reply))
                .as("COMMIT of a failed block is a rollback, and says so")
                .isEqualTo("ROLLBACK");
        assertThat(status(reply)).isEqualTo('I');

        assertThat(status(run("SELECT total FROM user_volume"))).isEqualTo('I');
    }

    @Test
    void anErrorOutsideABlockLeavesTheSessionIdle() throws Exception {
        List<PgTestClient.Message> reply = run("SELECT * FROM no_such_view");
        assertThat(status(reply)).isEqualTo('I');
    }

    @Test
    void aRefusedWriteInsideABlockFailsTheBlock() throws Exception {
        run("BEGIN");
        List<PgTestClient.Message> reply = run("INSERT INTO user_volume (user_id, total) VALUES ('x', 1)");
        assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
        assertThat(status(reply)).isEqualTo('E');
        assertThat(tag(run("ROLLBACK"))).isEqualTo("ROLLBACK");
    }

    @Test
    void savepointsNestReleaseAndRollBackAFailedBlockToWhereItWas() throws Exception {
        run("BEGIN");
        assertThat(tag(run("SAVEPOINT a"))).isEqualTo("SAVEPOINT");
        assertThat(tag(run("SAVEPOINT b"))).isEqualTo("SAVEPOINT");
        assertThat(tag(run("RELEASE SAVEPOINT b"))).isEqualTo("RELEASE");

        run("SELECT * FROM no_such_view");
        List<PgTestClient.Message> reply = run("ROLLBACK TO SAVEPOINT a");
        assertThat(tag(reply)).isEqualTo("ROLLBACK");
        assertThat(status(reply))
                .as("rolled back to a savepoint, the block is usable again")
                .isEqualTo('T');

        // The savepoint survives a rollback to it; `b` was released and is gone.
        assertThat(status(run("ROLLBACK TO a"))).isEqualTo('T');
        reply = run("RELEASE b");
        assertThat(sqlState(reply)).isEqualTo("3B001");
        assertThat(error(reply)).contains("PRV-6214").contains("savepoint \"b\" does not exist");
        assertThat(status(reply)).as("a bad RELEASE fails the block").isEqualTo('E');

        // A savepoint name is an identifier: unquoted folds to lower case.
        assertThat(status(run("ROLLBACK TO SAVEPOINT A"))).isEqualTo('T');
        assertThat(tag(run("RELEASE a"))).isEqualTo("RELEASE");
        assertThat(tag(run("COMMIT"))).isEqualTo("COMMIT");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "SAVEPOINT a|SAVEPOINT can only be used in transaction blocks",
                "RELEASE SAVEPOINT a|RELEASE SAVEPOINT can only be used in transaction blocks",
                "ROLLBACK TO a|ROLLBACK TO SAVEPOINT can only be used in transaction blocks",
                "COMMIT AND CHAIN|COMMIT AND CHAIN can only be used in transaction blocks",
            })
    void aSavepointVerbOutsideABlockIsRefusedWith25P01(String sql, String message) throws Exception {
        List<PgTestClient.Message> reply = run(sql);
        assertThat(sqlState(reply)).isEqualTo("25P01");
        assertThat(error(reply)).contains("PRV-6213").contains(message);
        assertThat(status(reply)).isEqualTo('I');
    }

    @Test
    void commitAndChainOpensTheNextBlockAtOnce() throws Exception {
        run("BEGIN");
        List<PgTestClient.Message> reply = run("COMMIT AND CHAIN");
        assertThat(tag(reply)).isEqualTo("COMMIT");
        assertThat(status(reply)).isEqualTo('T');
        assertThat(status(run("ROLLBACK"))).isEqualTo('I');
    }

    @Test
    void setTransactionIsAcceptedAndAStrongerIsolationSaysItRunsAsReadCommitted() throws Exception {
        List<PgTestClient.Message> reply = run("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
        assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'N').get(0)))
                .containsEntry('C', "25P01")
                .containsEntry('M', "SET TRANSACTION can only be used in transaction blocks");
        assertThat(tag(reply)).isEqualTo("SET");

        reply = run("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE");
        assertThat(tag(reply)).isEqualTo("SET");
        assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'N').get(0))
                        .get('M'))
                .contains("SERIALIZABLE is accepted and runs as READ COMMITTED");

        run("BEGIN");
        reply = run("SET TRANSACTION READ ONLY");
        assertThat(PgTestClient.shape(reply)).isEqualTo("CZ");
        assertThat(status(reply)).isEqualTo('T');

        reply = run("SET TRANSACTION SNAPSHOT '00000003-0000001B-1'");
        assertThat(sqlState(reply)).isEqualTo("0A000");
        assertThat(status(reply)).isEqualTo('E');
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "SHOW transaction_isolation|transaction_isolation|read committed",
                "SHOW TRANSACTION ISOLATION LEVEL|transaction_isolation|read committed",
                "show default_transaction_isolation|default_transaction_isolation|read committed",
                "SHOW transaction_read_only|transaction_read_only|on",
                "SHOW default_transaction_read_only|default_transaction_read_only|on",
                "SHOW transaction_deferrable|transaction_deferrable|off",
                "SHOW standard_conforming_strings|standard_conforming_strings|on",
                "SHOW datestyle|DateStyle|ISO, MDY",
            })
    void theSettingsDriversProbeAreShownAsThisServerHasThem(String sql, String column, String value) throws Exception {
        for (boolean inBlock : new boolean[] {false, true}) {
            if (inBlock) {
                run("BEGIN");
            }
            List<PgTestClient.Message> reply = run(sql);
            assertThat(PgTestClient.shape(reply)).isEqualTo("TDCZ");
            assertThat(PgTestClient.described(PgTestClient.ofType(reply, 'T').get(0)))
                    .extracting(PgTestClient.Described::name)
                    .containsExactly(column);
            assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0)))
                    .containsExactly(value);
            assertThat(tag(reply)).isEqualTo("SHOW");
            assertThat(status(reply)).isEqualTo(inBlock ? 'T' : 'I');
        }
    }

    @Test
    void discardAllIsRefusedInsideABlockAsPostgresRefusesIt() throws Exception {
        run("BEGIN");
        List<PgTestClient.Message> reply = run("DISCARD ALL");
        assertThat(sqlState(reply)).isEqualTo("25001");
        assertThat(error(reply)).contains("PRV-6215");
        assertThat(status(reply)).isEqualTo('E');
        run("ROLLBACK");
        assertThat(tag(run("DISCARD ALL"))).isEqualTo("DISCARD ALL");
    }

    // ------------------------------------------------------------------ extended protocol

    @Test
    void extendedProtocolCarriesTheStatusOnEverySync() throws Exception {
        // pgjdbc's shape with autocommit off: BEGIN and the query in one Sync-delimited sequence.
        client.parse("", "BEGIN");
        client.bindText("", "");
        client.execute("", 0);
        client.parse("", "SELECT total FROM user_volume");
        client.bindText("", "");
        client.execute("", 0);
        client.sync();
        List<PgTestClient.Message> reply = client.readUntilReady();
        assertThat(PgTestClient.shape(reply)).isEqualTo("12C12DCZ");
        assertThat(PgTestClient.ofType(reply, 'C').get(0).strings().get(0)).isEqualTo("BEGIN");
        assertThat(status(reply)).isEqualTo('T');

        // An error inside the block: skipped to Sync, and Sync says E.
        client.parse("", "SELECT * FROM no_such_view");
        client.bindText("", "");
        client.execute("", 0);
        client.sync();
        reply = client.readUntilReady();
        assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
        assertThat(status(reply)).isEqualTo('E');

        // A new statement is refused at Parse with 25P02.
        client.parse("", "SELECT total FROM user_volume");
        client.sync();
        reply = client.readUntilReady();
        assertThat(sqlState(reply)).isEqualTo("25P02");
        assertThat(status(reply)).isEqualTo('E');

        // A named COMMIT parsed once and executed now reports ROLLBACK, and the session is idle.
        client.parse("commit", "COMMIT");
        client.bindText("", "commit");
        client.execute("", 0);
        client.sync();
        reply = client.readUntilReady();
        assertThat(PgTestClient.ofType(reply, 'C').get(0).strings().get(0)).isEqualTo("ROLLBACK");
        assertThat(status(reply)).isEqualTo('I');

        // And the same named statement, executed again in a healthy block, commits.
        client.query("BEGIN");
        assertThat(status(client.readUntilReady())).isEqualTo('T');
        client.bindText("", "commit");
        client.execute("", 0);
        client.sync();
        reply = client.readUntilReady();
        assertThat(PgTestClient.ofType(reply, 'C').get(0).strings().get(0)).isEqualTo("COMMIT");
        assertThat(status(reply)).isEqualTo('I');
    }

    @Test
    void extendedProtocolShowDescribesItsColumnAndAnswersTagShow() throws Exception {
        client.parse("", "SHOW TRANSACTION ISOLATION LEVEL");
        client.bindText("", "");
        client.describePortal("");
        client.execute("", 0);
        client.sync();
        List<PgTestClient.Message> reply = client.readUntilReady();
        assertThat(PgTestClient.shape(reply)).isEqualTo("12TDCZ");
        assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0))).containsExactly("read committed");
        assertThat(tag(reply)).isEqualTo("SHOW");
    }

    // ------------------------------------------------------------------ helpers

    private List<PgTestClient.Message> run(String sql) throws IOException {
        client.query(sql);
        return client.readUntilReady();
    }

    private static char status(List<PgTestClient.Message> reply) {
        PgTestClient.Message last = reply.get(reply.size() - 1);
        assertThat(last.type()).isEqualTo('Z');
        return (char) last.payload()[0];
    }

    private static String tag(List<PgTestClient.Message> reply) {
        List<PgTestClient.Message> complete = PgTestClient.ofType(reply, 'C');
        assertThat(complete)
                .as("a CommandComplete in %s", PgTestClient.shape(reply))
                .hasSize(1);
        return complete.get(0).strings().get(0);
    }

    private static String sqlState(List<PgTestClient.Message> reply) {
        return PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0)).get('C');
    }

    private static String error(List<PgTestClient.Message> reply) {
        return PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0)).get('M');
    }
}

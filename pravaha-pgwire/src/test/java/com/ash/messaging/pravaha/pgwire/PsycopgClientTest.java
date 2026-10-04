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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PGWIRE-TX-1 through psycopg 3 in its default mode -- not autocommit, so it sends {@code BEGIN}
 * before the first statement and waits for {@code ReadyForQuery 'T'} -- which is what failed with
 * {@code PRV-2001 ... near the keyword 'BEGIN'} before, and why the docs told people to pass {@code
 * autocommit=True}.
 *
 * <p><strong>Optional by construction</strong>, like {@code PsqlSessionTest} and {@code
 * NpgsqlClientTest}: it runs the Python at {@code $PRAVAHA_PSYCOPG_PYTHON}, else {@code python3},
 * and is skipped when that interpreter cannot {@code import psycopg}. Neither the SDK's nor the
 * console's virtualenv carries psycopg, so on most machines this needs one made for it -- {@code uv
 * venv /tmp/psy && VIRTUAL_ENV=/tmp/psy uv pip install 'psycopg[binary]'}, then {@code
 * PRAVAHA_PSYCOPG_PYTHON=/tmp/psy/bin/python}. {@link JdbcTransactionTest} makes the same claim with
 * a driver that is always on the classpath.
 */
class PsycopgClientTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    /** Each step prints one line; the test reads them back. psycopg raises on anything unexpected. */
    private static final String SCRIPT = """
            import sys, psycopg
            from psycopg import errors, pq
            conn = psycopg.connect(host="127.0.0.1", port=int(sys.argv[1]), user="dana", dbname="pravaha")
            assert not conn.autocommit
            print("default-read", conn.execute("SELECT total FROM user_volume").fetchone()[0],
                  conn.info.transaction_status.name)
            conn.commit()
            print("after-commit", conn.info.transaction_status.name)
            try:
                conn.execute("SELECT * FROM no_such_view")
            except psycopg.Error as e:
                print("failed", conn.info.transaction_status.name)
            try:
                conn.execute("SELECT total FROM user_volume")
            except errors.InFailedSqlTransaction as e:
                print("aborted", e.sqlstate)
            conn.rollback()
            print("after-rollback", conn.info.transaction_status.name)
            with conn.transaction():
                try:
                    with conn.transaction():
                        conn.execute("SELECT * FROM no_such_view")
                except psycopg.Error:
                    pass
                print("savepoint-recovered", conn.execute("SELECT total FROM user_volume").fetchone()[0])
            conn.commit()
            conn.isolation_level = psycopg.IsolationLevel.SERIALIZABLE
            conn.read_only = True
            print("isolation", conn.execute("SHOW transaction_isolation").fetchone()[0])
            conn.commit()
            conn.close()
            print("done")
            """;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private static String python;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private PravahaPgWireServer server;

    @BeforeAll
    static void findPsycopg() throws Exception {
        String candidate = System.getenv().getOrDefault("PRAVAHA_PSYCOPG_PYTHON", "python3");
        try {
            Process probe = new ProcessBuilder(candidate, "-c", "import psycopg")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            if (probe.waitFor(60, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                python = candidate;
            }
        } catch (IOException noInterpreter) {
            // No such interpreter: skipped, exactly as an interpreter without psycopg is.
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void psycopgInItsDefaultModeReadsCommitsRollsBackAndRecoversThroughASavepoint() throws Exception {
        assumeTrue(python != null, "no Python with psycopg: set PRAVAHA_PSYCOPG_PYTHON");
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 100);
        view.commit(100);
        server = new PravahaPgWireServer(new ViewCatalog().register(view)).start("127.0.0.1", 0);

        // stderr to the build log, not into the lines asserted below: psycopg may log the isolation
        // NOTICE there, and a traceback belongs in the log either way.
        Process run = new ProcessBuilder(python, "-c", SCRIPT, String.valueOf(server.port()))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(run.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(output).isZero();

        assertThat(output.lines())
                .containsExactly(
                        "default-read 300 INTRANS",
                        "after-commit IDLE",
                        "failed INERROR",
                        "aborted 25P02",
                        "after-rollback IDLE",
                        "savepoint-recovered 300",
                        "isolation read committed",
                        "done");
    }
}

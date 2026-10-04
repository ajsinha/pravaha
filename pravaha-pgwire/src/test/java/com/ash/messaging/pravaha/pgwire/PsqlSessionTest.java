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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate P6: a real SQL client, driven as a person would drive it.
 *
 * <p>This is the test that actually proves the point of this module, and it is the one that cannot
 * run everywhere: it shells out to {@code psql}. On a machine with no {@code psql} binary it
 * borrows one from a PostgreSQL container image that is already pulled, which is the same binary
 * reaching this server over a real socket. Where neither is available the tests below are
 * <em>skipped, not substituted</em>: {@link PgWireSessionTest} drives real protocol bytes over a
 * real socket and is the strongest thing available without a client, and it is not the same claim,
 * because it cannot catch a handshake step that every client makes and this server's own test
 * client happens not to.
 *
 * <p>A skip prints why, because a skipped cross-language test looks exactly like a passing one in a
 * surefire summary line -- which is how a gate gets marked passed by a test that never ran.
 */
class PsqlSessionTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final Principal ANALYST = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private PravahaPgWireServer server;

    @TempDir
    private java.io.File tlsDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static ViewCatalog populated() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 100);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 100);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 100);
        view.commit(100);
        return new ViewCatalog().register(view);
    }

    @Test
    void psqlConnectsAndSelectsFromAView() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        // No ORDER BY: the planner refuses LogicalSort with PRV-2020 (ADR-030), which is an engine
        // limitation and not a gateway one -- and it is exactly the kind of thing a real client
        // finds on its first run and a protocol unit test never would.
        String output = psql(null, "SELECT user_id, tier, total FROM user_volume");

        assertThat(output).contains("u1").contains("gold").contains("300");
        assertThat(output).contains("(3 rows)");
        // A server that is not authenticating should say nothing about who it thinks you are: it
        // runs every session as anonymous by design, and a NOTICE at the top of every session about
        // the configuration the operator chose is noise the operator cannot act on.
        assertThat(output).doesNotContain("NOTICE");
    }

    @Test
    void psqlAuthenticatesWithAPasswordVerifiedByTheTokenVerifier() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(StaticTokenVerifier.of("s3cret", ANALYST))
                .start("127.0.0.1", 0);

        assertThat(psql("s3cret", "SELECT count(*) FROM user_volume")).contains("3");
    }

    /**
     * Gate P6's follow-up: {@code \d} and {@code \dt} are SQL against {@code pg_catalog}, and before
     * {@link PgCatalogShim} they reached the planner and came back as parse errors naming syntax
     * ({@code OPERATOR(pg_catalog.~)}, {@code ::regclass}) no Pravaha dialect ever understood. This
     * drives the actual queries a real {@code psql} sends, over the real wire.
     */
    @Test
    void psqlBackslashDListsTheViews() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        String output = psql(null, "\\d");

        assertThat(output).contains("user_volume").contains("table").contains("public");
    }

    @Test
    void psqlBackslashDtListsTheViewsAsTables() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        String output = psql(null, "\\dt");

        // psql 18 titles \dt "List of tables"; earlier versions, "List of relations".
        assertThat(output)
                .containsAnyOf("List of relations", "List of tables")
                .contains("user_volume")
                .contains("table");
    }

    @Test
    void psqlBackslashDNameDescribesTheViewsColumns() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        String output = psql(null, "\\d user_volume");

        assertThat(output)
                .contains("Table \"public.user_volume\"")
                .contains("user_id")
                .contains("text")
                .contains("tier")
                .contains("total")
                .contains("bigint");
    }

    /**
     * SX-5, driven with a real client rather than in-process: a principal denied every view must not
     * be able to enumerate the catalogue by running {@code \d} instead of {@code SELECT}.
     */
    @Test
    void psqlBackslashDShowsNothingToAPrincipalDeniedEveryView() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(StaticTokenVerifier.of("s3cret", ANALYST))
                .authorizedBy(
                        (principal, view) -> com.ash.messaging.pravaha.security.AccessDecision.deny(
                                "no principal may read anything in this test"),
                        com.ash.messaging.pravaha.security.AuditSink.NONE)
                .start("127.0.0.1", 0);

        String output = psql("s3cret", "\\d");

        assertThat(output).doesNotContain("user_volume");
    }

    /**
     * The point of this whole slice: a real {@code psql}, told to require encryption, actually gets
     * it against this server -- not against a mock, not against this module's own test client.
     * {@code sslmode=require} is libpq's own "encrypt, do not verify the certificate", which is
     * exactly right for a certificate this test minted five lines ago and asked nobody to trust.
     */
    @Test
    void psqlConnectsOverTlsWhenTheServerHasACertificateAndSslmodeRequiresIt() throws Exception {
        requirePsql();
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        String output = psql(null, "SELECT user_id, tier, total FROM user_volume", "require");

        assertThat(output).contains("u1").contains("gold").contains("300");
        assertThat(output).contains("(3 rows)");
    }

    /**
     * {@code \d} over the same TLS connection: the catalog shim and TLS are independent features
     * and this is the test that they actually compose, rather than each merely working alone.
     */
    @Test
    void psqlBackslashDWorksOverTls() throws Exception {
        requirePsql();
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        String output = psql(null, "\\d user_volume", "require");

        assertThat(output)
                .contains("Table \"public.user_volume\"")
                .contains("user_id")
                .contains("bigint");
    }

    /**
     * A TLS-configured server must not become TLS-only: {@code sslmode=disable} never sends an
     * {@code SSLRequest} at all, so this exercises the plaintext path exactly as {@link
     * #psqlConnectsAndSelectsFromAView} does, on a server that happens to also hold a certificate.
     * A client that opts out is not refused, and it is not silently upgraded behind its back either.
     */
    @Test
    void psqlStillConnectsInPlaintextWhenTheServerHasACertificateButTheClientDisablesSsl() throws Exception {
        requirePsql();
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        String output = psql(null, "SELECT user_id, tier, total FROM user_volume", "disable");

        assertThat(output).contains("u1").contains("gold").contains("300");
        assertThat(output).contains("(3 rows)");
    }

    @Test
    void psqlShowsTheEnginesOwnRefusalForAViewThatIsNotThere() throws Exception {
        requirePsql();
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        // The PRV code reaching a person's terminal, unmangled, is most of what an error code is
        // for. If it survives the protocol it survives every client.
        assertThat(psql(null, "SELECT * FROM nope")).contains("PRV-4023");
    }

    /**
     * Runs one query through the psql client and returns everything it printed.
     *
     * <p>No {@code sslmode} on the command line, deliberately: the default is {@code prefer}, so
     * the client opens with an SSLRequest and this server has to decline it correctly for the
     * connection to happen at all. Passing {@code sslmode=disable} would skip the one handshake
     * step most likely to be wrong.
     */
    private String psql(String password, String sql) throws IOException, InterruptedException {
        return psql(password, sql, null);
    }

    /**
     * As {@link #psql(String, String)}, with an explicit {@code sslmode} -- {@code require} to
     * prove a TLS-configured server actually negotiates TLS with a real client, {@code disable} to
     * prove the same server still serves a client that opts out.
     */
    private String psql(String password, String sql, String sslmode) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(psqlCommand(password, sslmode));
        command.addAll(List.of(
                "-h", "127.0.0.1", "-p", String.valueOf(server.port()), "-U", "dana", "-d", "pravaha", "-c", sql));
        ProcessBuilder psql = new ProcessBuilder(command);
        psql.redirectErrorStream(true);
        psql.environment().put("PGPASSWORD", password == null ? "" : password);
        // Non-interactive and never paged, or the process waits for a terminal that is not there.
        psql.environment().put("PGCONNECT_TIMEOUT", "10");
        psql.environment().put("PAGER", "cat");
        if (sslmode != null) {
            psql.environment().put("PGSSLMODE", sslmode);
        }
        Process process = psql.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS))
                .as("psql should have finished; it printed: %s", output)
                .isTrue();
        return output;
    }

    /**
     * How to invoke {@code psql} here: the binary if it is installed, otherwise one out of a
     * container image that is already on this machine.
     *
     * <p>The container is a real {@code psql}, not a stand-in -- same binary, same handshake, same
     * SSL negotiation, reaching the server over a real socket on the host network. What it is not
     * is a guarantee that the test can run anywhere: {@code --network host} is a Linux behaviour,
     * and nothing here pulls an image. Both of those are why {@link #requirePsql()} can still skip.
     */
    private static List<String> psqlCommand(String password, String sslmode) {
        if (onPath("psql")) {
            return List.of("psql");
        }
        // The docker fallback's environment is the container's, not this JVM's -- ProcessBuilder's
        // own environment() only reaches the `docker` CLI itself, so PGSSLMODE has to travel in as
        // an -e flag here exactly as PGPASSWORD already does, or a TLS test would pass against a
        // real local psql and silently stop proving anything the moment it fell back to the image.
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "run",
                "--rm",
                "--network",
                "host",
                "-e",
                "PGPASSWORD=" + (password == null ? "" : password)));
        if (sslmode != null) {
            command.add("-e");
            command.add("PGSSLMODE=" + sslmode);
        }
        command.add(PSQL_IMAGE);
        command.add("psql");
        return command;
    }

    /** A PostgreSQL image carrying a real {@code psql}. Used only if it is already pulled. */
    private static final String PSQL_IMAGE = "postgres:16";

    /**
     * Skips loudly when there is no real client to drive, rather than quietly passing.
     *
     * <p>A skipped cross-language test is indistinguishable from a passing one in a surefire
     * summary, and gate P6 is exactly the kind of gate that gets marked passed by a test that never
     * ran. So the skip says so on standard output, and says what did run instead.
     */
    private static void requirePsql() {
        if (onPath("psql")) {
            return;
        }
        boolean containerised = "Linux".equals(System.getProperty("os.name"))
                && onPath("docker")
                && succeeds("docker", "image", "inspect", PSQL_IMAGE);
        if (!containerised) {
            System.out.println("P6: SKIPPED -- no psql on PATH and no local " + PSQL_IMAGE + " image to borrow one "
                    + "from, so the real-client test did not run. PgWireSessionTest drove the protocol "
                    + "over a socket instead; that is not the same claim.");
        }
        Assumptions.assumeTrue(containerised, "no psql binary and no local " + PSQL_IMAGE + " image");
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        return path != null
                && java.util.Arrays.stream(path.split(java.io.File.pathSeparator))
                        .map(dir -> Path.of(dir, executable))
                        .anyMatch(Files::isExecutable);
    }

    /** Whether a probe command exits zero, with its output discarded. */
    private static boolean succeeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}

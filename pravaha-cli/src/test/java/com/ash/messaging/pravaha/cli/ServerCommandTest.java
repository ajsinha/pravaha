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
package com.ash.messaging.pravaha.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server-facing commands, for everything that can be decided without a server.
 *
 * <p>Their behaviour against a running server is proven in {@code CliAgainstServerIT}, which starts
 * one. What is worth testing here is the half that fails before a connection is attempted -- a
 * missing argument, an unreadable file, a malformed filter -- because those are the mistakes people
 * actually make, and each should say what is wrong rather than surfacing as a connection error.
 */
class ServerCommandTest {

    private record Result(int code, String out, String err) {}

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = new PravahaCli(outStream, errStream).run(args);
        }
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theUsageTextDocumentsEveryServerCommand() {
        Result help = run("--help");

        // The commands exist to be discovered here; one missing from the usage text is one nobody
        // finds.
        assertThat(help.out())
                .contains("query")
                .contains("register")
                .contains("queries")
                .contains("subscribe")
                .contains("pause")
                .contains("resume")
                .contains("drop");
    }

    @Test
    void queryWithoutSqlSaysWhichOptionIsMissing() {
        Result result = run("query", "--url", "grpc://localhost:1");

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(result.err()).contains("--sql");
    }

    @Test
    void registerWithoutANameSaysSo() {
        Result result = run("register", "--sql", "SELECT 1 FROM t");

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(result.err()).contains("--name");
    }

    @Test
    void dropWithoutANameSaysSo() {
        assertThat(run("drop").code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(run("pause").code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(run("resume").code()).isEqualTo(PravahaCli.EXIT_USAGE);
    }

    @Test
    void subscribeWithoutAViewSaysSo() {
        Result result = run("subscribe", "--url", "grpc://localhost:1");

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(result.err()).contains("--view");
    }

    @Test
    void anUnreadableSqlFileNamesThePath(@TempDir Path directory) {
        Path missing = directory.resolve("nowhere.sql");

        Result result = run("register", "--name", "v", "--sql-file", missing.toString());

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        // The path, because "cannot read file" without it is a support question.
        assertThat(result.err()).contains("nowhere.sql");
    }

    @Test
    void aSqlFileIsReadWhenItExists(@TempDir Path directory) throws Exception {
        Path sql = directory.resolve("query.sql");
        Files.writeString(sql, "SELECT trade_id FROM trade\n");

        // No server, so this fails to connect -- but it must fail at the *connection*, which means
        // the file was read and the arguments were accepted.
        Result result = run("register", "--url", "grpc://localhost:1", "--name", "v", "--sql-file", sql.toString());

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_FAILED);
        assertThat(result.err()).doesNotContain("--sql");
    }

    @Test
    void aMalformedFilterSaysWhatAFilterLooksLike() {
        Result result = run("subscribe", "--url", "grpc://localhost:1", "--view", "v", "--filter", "notapair");

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(result.err()).contains("column=value");
    }

    @Test
    void aServerThatIsNotThereFailsRatherThanHanging() {
        Result result = run("queries", "--url", "grpc://localhost:1");

        // Exit 1, not a usage error: the command was well formed and the world was not.
        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_FAILED);
        assertThat(result.err()).isNotBlank();
    }

    @Test
    void eachLifecycleCommandSaysWhatItDidInEnglish() {
        // HLP-10: the past tense was built as action + "ped", so pause and resume printed
        // "pauseped" and "resumeped".
        assertThat(ServerCommand.pastTense("drop")).isEqualTo("dropped");
        assertThat(ServerCommand.pastTense("pause")).isEqualTo("paused");
        assertThat(ServerCommand.pastTense("resume")).isEqualTo("resumed");
    }

    @Test
    void aWeightIsAlwaysSigned() {
        // HLP-11: an insert and the retraction that withdraws it are the same columns; the sign is
        // all that tells them apart, so neither may be left to be inferred.
        assertThat(ServerCommand.weightText(1)).isEqualTo("+1");
        assertThat(ServerCommand.weightText(-1)).isEqualTo("-1");
        assertThat(ServerCommand.weightText(3)).isEqualTo("+3");
        assertThat(ServerCommand.weightText(-2)).isEqualTo("-2");
    }

    @Test
    void theUsageTextSaysASubscriptionPrintsWeights() {
        assertThat(run("--help").out()).contains("+1 a row arriving, -1 a row withdrawn");
    }

    @Test
    void registerRefusesAParameterRatherThanDroppingIt() {
        // HLP-12: `register --param` was documented and never existed; an ignored value would have
        // registered the query without it.
        Result result = run(
                "register", "--url", "grpc://localhost:1", "--name", "v", "--sql", "SELECT 1 FROM t", "--param", "EU");

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(result.err()).contains("register takes no parameters");
    }

    @Test
    void anUnknownCommandIsAUsageError() {
        Result result = run("subcsribe", "--view", "v");

        assertThat(result.code()).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(result.err()).contains("unknown command");
    }
}

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
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PravahaCliTest {

    private static final String SCHEMA = "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new PravahaCli(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void noArgumentsPrintsUsageAndExitsTwo() {
        // Exit 2 for a usage problem, not 1: a script has to tell "you called it wrong" apart from
        // "it ran and found something wrong".
        assertThat(run()).isEqualTo(2);
        assertThat(stdout()).contains("Usage:").contains("validate").contains("Ask once. Answer always.");
    }

    @Test
    void helpExitsZero() {
        assertThat(run("--help")).isZero();
        assertThat(run("help")).isZero();
    }

    @Test
    void anUnknownCommandNamesItselfAndShowsUsage() {
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(stderr()).contains("frobnicate");
        assertThat(stdout()).contains("Usage:");
    }

    @Test
    void versionPrints() {
        assertThat(run("version")).isZero();
        assertThat(stdout()).contains("pravaha");
    }

    @Test
    void validateAcceptsASoundQuery() {
        assertThat(run("validate", "--sql", "SELECT user_id FROM txn WHERE amount > 100", "--schema", SCHEMA))
                .isZero();
        assertThat(stdout()).contains("valid").contains("user_id");
    }

    @Test
    void validateRejectsAnUnknownColumnWithItsErrorCode() {
        assertThat(run("validate", "--sql", "SELECT nope FROM txn", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr()).contains("PRV-2002").contains("docs.pravaha.io/errors/PRV-2002");
    }

    @Test
    void validateRefusesAnUnboundedGroupByBeforeAnythingRuns() {
        assertThat(run("validate", "--sql", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr()).contains("PRV-2050").contains("Add a window");
    }

    @Test
    void aMissingOptionSaysWhichAndWhatWasSupplied() {
        // A CLI is often someone's first contact with the product; "missing required option" alone
        // makes them read source they may not have.
        assertThat(run("validate", "--schema", SCHEMA)).isEqualTo(2);
        assertThat(stderr()).contains("--sql is required").contains("schema");
    }

    @Test
    void explainShowsBothLevels() {
        assertThat(run(
                        "explain",
                        "--sql",
                        "SELECT user_id FROM txn WHERE amount > 100",
                        "--schema",
                        SCHEMA,
                        "--level",
                        "all"))
                .isZero();
        assertThat(stdout())
                .contains("Logical plan")
                .contains("LogicalFilter")
                .contains("Physical plan")
                .contains("Filter(")
                .contains("Scan(txn)");
    }

    @Test
    void anUnknownExplainLevelIsAUsageError() {
        assertThat(run("explain", "--sql", "SELECT user_id FROM txn", "--schema", SCHEMA, "--level", "weird"))
                .isEqualTo(2);
        assertThat(stderr()).contains("logical, physical, codegen or all");
    }

    @Test
    void explainCodegenShowsTheJavaTheEngineWillRun() {
        // A generated plan that produces a wrong answer is otherwise undebuggable from outside:
        // there is no file to open, and the class named in the stack trace was compiled from a
        // string. Line numbers because a compiler error citing line 47 is useless without them.
        assertThat(run(
                        "explain",
                        "--sql",
                        "SELECT amount FROM txn WHERE amount > 100",
                        "--schema",
                        SCHEMA,
                        "--level",
                        "codegen"))
                .isZero();
        assertThat(stdout())
                .contains("Generated source")
                .contains("implements com.ash.messaging.pravaha.codegen.FusedStage")
                .contains("   1  ");
    }

    @Test
    void explainCodegenSaysSoWhenAQueryWillRunInterpreted() {
        // Not an error. Every operator has a correct slow implementation and correctness never
        // depends on generation succeeding (design 12.4) -- but an operator needs to know that this
        // query takes the slower path, and why, which is a diagnostic rather than a failure.
        assertThat(run("explain", "--sql", "SELECT user_id FROM txn", "--schema", SCHEMA, "--level", "codegen"))
                .isZero();
        assertThat(stdout()).contains("no generated form").contains("interpreted path, which is correct and slower");
    }

    @Test
    void runExecutesEndToEnd(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, """
                1,alice,500,COMPLETED
                2,bob,50,COMPLETED
                3,carol,900,PENDING
                """);
        Path output = dir.resolve("out.csv");

        int code = run(
                "run",
                "--sql",
                "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "user_id:STRING,amount:INT64");

        assertThat(code).isZero();
        assertThat(Files.readAllLines(output)).containsExactly("alice,500");
        // Plan and execute time are reported separately: planning is paid once at registration and
        // execution per record, and a slow run needs to say which half is slow.
        assertThat(stdout()).contains("3 in, 1 out").contains("plan").contains("execute");
    }

    @Test
    void optionsAcceptBothSpacedAndEqualsForms() {
        assertThat(run("validate", "--sql=SELECT user_id FROM txn", "--schema=" + SCHEMA))
                .isZero();
    }

    @Test
    void colourIsOffWhenOutputIsNotATerminal() {
        // Escape codes in a log file or CI transcript make the thing being grepped for harder to
        // find, which is the opposite of the point.
        assertThat(Ansi.enabled()).isFalse();
        assertThat(Ansi.bold("plain")).isEqualTo("plain");
        assertThat(Ansi.bad("plain")).doesNotContain("[");
    }
}

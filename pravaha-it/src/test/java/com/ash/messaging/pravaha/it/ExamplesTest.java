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
package com.ash.messaging.pravaha.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.cli.PravahaCli;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The examples are executed, not merely shipped.
 *
 * <p>Written immediately after documenting output that turned out to be wrong. The quickstart
 * quoted a validation time of 1243 µs; the real figure was two seconds, because a cold JVM spends
 * most of that loading Calcite. The number had been *plausible*, which is exactly what makes this
 * kind of rot survive review.
 *
 * <p>So the outputs in {@code examples/} and {@code docs/QUICKSTART.md} are produced here and
 * compared. An example that stops working fails the build rather than quietly misleading whoever
 * tries it next -- and for a closed-source product they cannot fall back to reading the code
 * (design section 30.4), which makes a wrong example unusually expensive.
 */
class ExamplesTest {

    private static final String SCHEMA = "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING";

    @Test
    void example01FilterAndProjectProducesItsDocumentedOutput(@TempDir Path dir) throws IOException {
        Path output = dir.resolve("out.csv");
        Result result = run(
                "run",
                "--sql",
                "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100",
                "--schema",
                SCHEMA,
                "--in",
                example("01-filter-and-project/transactions.csv").toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "user_id:STRING,amount:INT64");

        assertThat(result.exitCode()).isZero();
        List<String> lines = Files.readAllLines(output);
        assertThat(lines).containsExactly("alice,500", "dave,150", "frank,1200");

        // And the README claims exactly that.
        assertThat(readme("01-filter-and-project"))
                .contains("alice,500")
                .contains("dave,150")
                .contains("frank,1200");
    }

    @Test
    void example02AggregateProducesItsDocumentedOutput(@TempDir Path dir) throws IOException {
        Path output = dir.resolve("out.csv");
        Result result = run(
                "run",
                "--sql",
                "SELECT COUNT(*), SUM(amount) FROM txn WHERE status = 'COMPLETED'",
                "--schema",
                SCHEMA,
                "--in",
                example("02-aggregate/transactions.csv").toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "n:INT64,total:INT64");

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readAllLines(output)).containsExactly("3,700");
        assertThat(readme("02-aggregate")).contains("3,700");
    }

    @Test
    void example02sRefusalIsRefusedForTheDocumentedReason() {
        Result result =
                run("validate", "--sql", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "--schema", SCHEMA);

        assertThat(result.exitCode()).isOne();
        assertThat(result.err()).contains("PRV-2050").contains("Bound it with a window");

        // The README quotes the message; if the wording changes, the quote must too.
        String readme = readme("02-aggregate");
        assertThat(readme).contains("PRV-2050").contains("Bound it with a window");
    }

    @Test
    void theQuickstartsValidateCommandWorksAndReportsItsFields() {
        Result result = run(
                "validate",
                "--sql",
                "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100",
                "--schema",
                SCHEMA);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("valid")
                .contains("user_id VARCHAR NOT NULL")
                .contains("amount INT64 NOT NULL");
    }

    @Test
    void theQuickstartsExplainCommandShowsBothPlans() {
        Result result = run(
                "explain",
                "--level",
                "all",
                "--sql",
                "SELECT user_id FROM txn WHERE amount > 100 AND status = 'COMPLETED'",
                "--schema",
                SCHEMA);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("Logical plan")
                .contains("LogicalTableScan")
                .contains("Physical plan")
                .contains("Scan(txn)");
    }

    @Test
    void aMistypedColumnExitsOneWithItsCodeAndHelpUrl() {
        // Exit 1 for a query problem, 2 for a command-line mistake. Scripts distinguish these, and
        // the quickstart says so.
        Result result = run("validate", "--sql", "SELECT user_idd FROM txn", "--schema", SCHEMA);
        assertThat(result.exitCode()).isOne();
        assertThat(result.err()).contains("PRV-2002").contains("docs.pravaha.io/errors/PRV-2002");
    }

    @Test
    void aMissingOptionExitsTwo() {
        assertThat(run("validate", "--schema", SCHEMA).exitCode()).isEqualTo(2);
    }

    @Test
    void everyExampleDirectoryHasAReadme() throws IOException {
        try (var dirs = Files.list(repoRoot().resolve("examples"))) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> assertThat(dir.resolve("README.md"))
                            .as("%s must document itself", dir.getFileName())
                            .exists());
        }
    }

    @Test
    void aQueryThatThrowsFailsLoudlyRatherThanReportingOk(@TempDir Path dir) throws IOException {
        // Division by zero used to cost five minutes and then lie. awaitQuiescent waited out its
        // whole timeout -- a dead lane cannot drain, so quiescence never arrives -- and reported
        // "the lane is still working or stuck" while the ArithmeticException sat unread in the
        // lane's failure field. Shortening that wait exposed the second half: the lane dies inside
        // close(), where the pipeline is finished, which is after every earlier check has passed. So
        // the command printed "ok  3 in, 1 out" and exited zero, with two rows missing and nothing
        // said about why.
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "a,1\nb,0\nc,2\n");

        Result result = run(
                "run",
                "--sql",
                "SELECT k, 100 / n FROM t",
                "--schema",
                "k:STRING,n:INT64",
                "--out-schema",
                "k:STRING,r:INT64",
                "--in",
                input.toString(),
                "--out",
                dir.resolve("out.csv").toString(),
                "--stream",
                "t");

        assertThat(result.exitCode())
                .as("a query that threw must not exit zero; it printed 'ok' and lost two rows")
                .isNotZero();
        String said = result.out() + result.err();
        assertThat(said)
                .as("the message must name the arithmetic, not the lane's liveness. Got: %s", said)
                .containsIgnoringCase("zero");
        assertThat(said).doesNotContain("did not finish within five minutes");
    }

    @Test
    void overflowFailsTheSameWayEveryTime(@TempDir Path dir) throws IOException {
        // Reported as non-deterministic: the same command on the same input either dropped the row
        // silently and exited zero, or hung five minutes and exited one. Both halves were the same
        // swallowed exception seen from two sides of a race with the lane's shutdown. It is one
        // answer now, so this runs the query several times and demands they agree.
        Path input = dir.resolve("big.csv");
        Files.writeString(input, "a," + Long.MAX_VALUE + "\n");

        for (int attempt = 0; attempt < 5; attempt++) {
            Result result = run(
                    "run",
                    "--sql",
                    "SELECT k, n * 2 FROM t",
                    "--schema",
                    "k:STRING,n:INT64",
                    "--out-schema",
                    "k:STRING,r:INT64",
                    "--in",
                    input.toString(),
                    "--out",
                    dir.resolve("out" + attempt + ".csv").toString(),
                    "--stream",
                    "t");
            assertThat(result.exitCode())
                    .as(
                            "attempt %d disagreed with the others; overflow must not silently drop a row "
                                    + "under a successful status",
                            attempt)
                    .isNotZero();
        }
    }

    @Test
    void anAggregateTheEngineRefusesSaysSoInsteadOfHanging(@TempDir Path dir) throws IOException {
        // COUNT(DISTINCT) over a stream is refused by design -- it is one entry per distinct value
        // kept for ever. The refusal is thrown on the lane's thread, and with the exception swallowed
        // the caller waited out five minutes and was told the lane was "still working or stuck",
        // which is the opposite of what had happened: it had decided immediately and correctly.
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "a,1\nb,2\n");

        Result result = run(
                "run",
                "--sql",
                "SELECT COUNT(DISTINCT k) FROM t",
                "--schema",
                "k:STRING,n:INT64",
                "--out-schema",
                "c:INT64",
                "--in",
                input.toString(),
                "--out",
                dir.resolve("out.csv").toString(),
                "--stream",
                "t");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.out() + result.err())
                .as("the refusal must reach the user rather than a timeout message")
                .doesNotContain("did not finish within five minutes");
    }

    @Test
    void runReadsEveryRowOfALargeFileEveryTime(@TempDir Path dir) throws IOException {
        // pumpOnce returns 0 both when the source has nothing right now and when it has nothing
        // ever, and a file fills the lane's inbox faster than the lane drains it -- so the loop
        // stopped at the first full inbox and called it the end of the file. Five runs of one
        // command over one 20,000-row file returned 17,664, 9,472, 7,424, 6,400 and 4,608 rows,
        // every one reporting ok and exiting zero.
        //
        // Run repeatedly, because the defect was non-deterministic: a single run could be right by
        // luck, and it is the disagreement between runs that proves the bug.
        int rows = 20_000;
        Path input = dir.resolve("many.csv");
        StringBuilder csv = new StringBuilder(rows * 16);
        for (int i = 0; i < rows; i++) {
            csv.append("u").append(i % 7).append(',').append(i).append('\n');
        }
        Files.writeString(input, csv);

        for (int attempt = 0; attempt < 3; attempt++) {
            Path output = dir.resolve("out" + attempt + ".csv");
            Result result = run(
                    "run",
                    "--sql",
                    "SELECT k, n FROM t",
                    "--schema",
                    "k:STRING,n:INT64",
                    "--out-schema",
                    "k:STRING,n:INT64",
                    "--in",
                    input.toString(),
                    "--out",
                    output.toString(),
                    "--stream",
                    "t");

            assertThat(result.exitCode())
                    .as("attempt %d: %s%s", attempt, result.out(), result.err())
                    .isZero();
            assertThat(Files.readAllLines(output))
                    .as("attempt %d truncated the file and reported ok", attempt)
                    .hasSize(rows);
        }
    }

    // ------------------------------------------------------------------ harness

    private record Result(int exitCode, String out, String err) {}

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new PravahaCli(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path example(String relative) {
        return repoRoot().resolve("examples").resolve(relative);
    }

    private static String readme(String directory) {
        try {
            return Files.readString(example(directory + "/README.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the README for " + directory, e);
        }
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }
}

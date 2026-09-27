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
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    void everyCommandPrintsItsOwnFlagsWithoutTouchingTheNetwork() {
        // P-4: --help was an ordinary flag, so `queries --help` dialled the server and failed with
        // PRV-1041, and six other commands answered "missing required option". No --url is given
        // here and no server is running, so anything that reaches the network fails this test.
        for (String command : PravahaCli.helpTopics()) {
            out.reset();
            err.reset();
            assertThat(run(command, "--help"))
                    .as("`pravaha %s --help` must exit 0", command)
                    .isZero();
            assertThat(stdout())
                    .as("`pravaha %s --help` must describe %s", command, command)
                    .contains(command);
            assertThat(stderr())
                    .as("`pravaha %s --help` must not report a failure", command)
                    .isEmpty();
        }
    }

    @Test
    void oneCommandsHelpIsNotEveryCommandsHelp() {
        // The whole point is that it answers about the command asked for. Printing the full usage
        // would pass the test above and leave the flags just as hard to find.
        assertThat(run("drop", "--help")).isZero();
        assertThat(stdout()).contains("--name").doesNotContain("--out-schema");
    }

    @Test
    void anUnknownCommandAskedForHelpStillSaysItIsUnknown() {
        assertThat(run("frobnicate", "--help")).isEqualTo(2);
        assertThat(stderr()).contains("frobnicate");
    }

    @Test
    void anOfflineRefusalNamesTheStreamTheCallerGaveIt() {
        // SX-19. SqlPlanner stopped listing declared streams on "object not found" -- right, on a
        // server, where it runs before authorization and cannot tell who is asking. The
        // suppression is blanket, so on `validate` it withheld the name the user typed in the same
        // command, and sent them to "the listing call" on a command that contacts no server.
        assertThat(run("validate", "--sql", "SELECT user_id FROM txns", "--schema", SCHEMA, "--stream", "txn"))
                .isEqualTo(1);
        assertThat(stderr())
                .as("the refusal must end a typo, not describe a server this command never called")
                .contains("PRV-2002")
                .contains("'txn'")
                .doesNotContain("This server has");

        out.reset();
        err.reset();
        assertThat(run("explain", "--sql", "SELECT user_id FROM txns", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr())
                .as("explain plans the same way and owes the same hint; txn is --stream's default")
                .contains("'txn'")
                .doesNotContain("This server has");
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

    @org.junit.jupiter.api.AfterEach
    void clearTheConfiguredHelpBase() {
        com.ash.messaging.pravaha.api.HelpUrls.configure(null);
    }

    @Test
    void validateRejectsAnUnknownColumnWithItsErrorCode() {
        assertThat(run("validate", "--sql", "SELECT nope FROM txn", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr()).contains("PRV-2002");
    }

    /**
     * DOCX-21. The second line under a refusal used to be
     * {@code https://docs.pravaha.io/errors/PRV-2002}, on a host that has never resolved. With no
     * {@code pravaha.docs.base-url} there is no URL at all, and the line says where the code is
     * written down offline instead -- which is the whole point of dropping the link.
     */
    @Test
    void withNoHelpBaseARefusalPrintsNoUrlAndSaysWhereToLookTheCodeUp() {
        assertThat(run("validate", "--sql", "SELECT nope FROM txn", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr())
                .contains("PRV-2002")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .contains("look PRV-2002 up in the console's help under Errors, or in docs/TROUBLESHOOTING.md");
    }

    @Test
    void withAHelpBaseConfiguredARefusalPrintsThatDeploymentsUrl() {
        com.ash.messaging.pravaha.api.HelpUrls.configure("http://localhost:17070/help/codes/");
        assertThat(run("validate", "--sql", "SELECT nope FROM txn", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr())
                .contains("PRV-2002")
                .contains("http://localhost:17070/help/codes/PRV-2002")
                .doesNotContain("docs.pravaha.io");
    }

    @Test
    void validateRefusesAnUnboundedGroupByBeforeAnythingRuns() {
        assertThat(run("validate", "--sql", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "--schema", SCHEMA))
                .isEqualTo(1);
        assertThat(stderr())
                .contains("PRV-2050")
                .as("the refusal names the key column, not its ordinal (Wave 4 gate)")
                .contains("GROUP BY user_id")
                .contains("TUMBLE(event_time");
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

    /**
     * API-F7. Against nothing listening, every one of the seven server commands failed with the
     * bare stderr text {@code PRV-1041  io exception} — no host, no port, no scheme.
     *
     * <p>An operator debugging "why did my script print `io exception` and exit 1" had nothing to
     * go on, not even whether the default endpoint had been used because {@code --url} went into a
     * different flag. The transport's own text is true of any socket anywhere; the one thing this
     * process knows and that message does not is where it was pointed.
     */
    @Test
    void apiF7_aDeadServerRefusalNamesTheAddressItWasTalkingTo() {
        int code = run("queries", "--url", "grpc://localhost:1");

        assertThat(code).isNotZero();
        assertThat(stderr()).contains("grpc://localhost:1").contains("--url");
    }

    /**
     * API-F7's other half. {@code subscribe} wrote its success banner to <strong>stdout</strong>
     * before the connection was known to have failed, so a caller reading stdout alone — or a
     * pipeline consuming it — saw an apparent confirmation from a command that exited 1.
     */
    @Test
    void apiF7_subscribeWritesNoSuccessBannerToStdoutWhenItCannotConnect() {
        int code = run("subscribe", "--view", "anything", "--url", "grpc://localhost:1");

        assertThat(code).isNotZero();
        assertThat(stdout())
                .as("stdout carries the rows; a note to a person belongs on stderr, and a note "
                        + "about a connection that failed belongs nowhere")
                .doesNotContain("subscribed to");
    }

    /**
     * X-3 (round 1's Q-10). {@code --out-schema} is a second description of the output, and
     * nothing compared it with the plan's real one.
     *
     * <p>The sink's decoder reads the engine's rows at the widths this string declares, so
     * declaring two adjacent {@code INT32} columns as one {@code INT64} read eight bytes across
     * both of them. Traced exactly: {@code 1,1} came back as {@code 4294967297}, and {@code -2,-2}
     * as {@code -4294967298} in the first column and {@code 4294967294} in the second, the second
     * read running past the row into unrelated bytes. No exception, no warning, exit 0, a full CSV
     * of plausible numbers.
     */
    @Test
    void x3_anOutSchemaThatIsNotThePlansOutputIsRefusedRatherThanReadingTheWrongBytes(@TempDir Path dir)
            throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,1,COMPLETED\n2,bob,-2,COMPLETED\n");
        Path output = dir.resolve("out.csv");

        // MOD over an INT64 column produces INT32 in this planner, so the true output is
        // INT32,INT32 -- and declaring one INT64 used to read straight across the pair.
        int code = run(
                "run",
                "--sql",
                "SELECT MOD(amount, 3), MOD(amount, 3) FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "a:INT64");

        assertThat(code).isNotZero();
        assertThat(stderr())
                .contains("--out-schema declares 1 column(s) and this query produces 2")
                .contains("Declare:");
        assertThat(output)
                .as("nothing is written for a run that cannot describe its own output")
                .doesNotExist();
    }

    /** X-3's narrower half: the right number of columns at the wrong widths. */
    @Test
    void x3_anOutSchemaWithTheRightCountAndTheWrongWidthIsRefused(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,1,COMPLETED\n");
        Path output = dir.resolve("out.csv");

        int code = run(
                "run",
                "--sql",
                "SELECT MOD(amount, 3), MOD(amount, 3) FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "a:INT64,b:INT64");

        assertThat(code).isNotZero();
        assertThat(stderr())
                .contains("--out-schema declares column 1")
                .contains("INT64")
                .contains("INT32")
                .contains("reads across the next column");
    }

    /** X-3's control: the plan's own types are what the refusal names, and they run. */
    @Test
    void x3_theShapeTheRefusalNamesIsAcceptedAndCorrect(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,1,COMPLETED\n2,bob,-2,COMPLETED\n");
        Path output = dir.resolve("out.csv");

        int code = run(
                "run",
                "--sql",
                "SELECT MOD(amount, 3), MOD(amount, 3) FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "a:INT32,b:INT32");

        assertThat(code).isZero();
        assertThat(Files.readAllLines(output)).containsExactly("1,1", "-2,-2");
    }

    @Test
    void aSecondRunReplacesTheOutputRatherThanAddingToIt(@TempDir Path dir) throws IOException {
        // The filesystem sink appends by default so a server restart keeps its output (HLP-2); a
        // one-shot run writes its whole answer, and rerunning it must not double the file.
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,500,COMPLETED\n");
        Path output = dir.resolve("out.csv");
        Files.writeString(output, "left,over\n");
        String[] args = {
            "run",
            "--sql",
            "SELECT user_id, amount FROM txn",
            "--schema",
            SCHEMA,
            "--in",
            input.toString(),
            "--out",
            output.toString(),
            "--out-schema",
            "user_id:STRING,amount:INT64"
        };

        assertThat(run(args)).isZero();
        assertThat(run(args)).isZero();
        assertThat(Files.readAllLines(output)).containsExactly("alice,500");
    }

    // ------------------------------------------------------------ W8-11: the dead-letter queue

    private static final String ONE_BAD_LINE =
            "1,alice,500,COMPLETED\n" + "2,bob,NOTANUMBER,COMPLETED\n" + "3,carol,900,COMPLETED\n";

    @Test
    void aBadLineWithNoDeadLetterFileStillFailsTheRunAndNamesTheLineAndColumn(@TempDir Path dir) throws IOException {
        // The default has to stay what it was. A record is not discarded merely because nobody said
        // where to put it -- that is the half of the dead-letter queue's two rules that matters
        // more, because a query producing slightly wrong answers from quietly discarded input is
        // one nobody investigates.
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, ONE_BAD_LINE);

        int code = run(
                "run",
                "--sql",
                "SELECT user_id, amount FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                dir.resolve("out.csv").toString(),
                "--out-schema",
                "user_id:STRING,amount:INT64");

        assertThat(code).isEqualTo(1);
        // And it says what was actually wrong. It used to say "a plugin aborted a row mid-write,
        // which the ingest path cannot yet undo ... Report this": the reader aborted the row before
        // reporting the decode failure, abort() refused because it cannot give back a claimed inbox
        // cell, and its internal complaint replaced the one fact the person needed.
        assertThat(stderr())
                .contains("line 2")
                .contains("amount")
                .contains("NOTANUMBER")
                .doesNotContain("Report this");
    }

    @Test
    void withADeadLetterFileTheRunFinishesAndTheRejectedLineIsOnDiskWithItsBytes(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, ONE_BAD_LINE);
        Path output = dir.resolve("out.csv");
        Path dlq = dir.resolve("rejects.jsonl");

        int code = run(
                "run",
                "--sql",
                "SELECT user_id, amount FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "user_id:STRING,amount:INT64",
                "--dlq",
                dlq.toString());

        assertThat(code).isZero();
        // The other two rows are answered: one bad record does not stop the pipeline.
        assertThat(Files.readAllLines(output)).containsExactly("alice,500", "carol,900");
        // And it is not silent about it.
        assertThat(stdout()).contains("1 rejected");

        List<String> letters = Files.readAllLines(dlq);
        assertThat(letters).hasSize(1);
        assertThat(letters.get(0)).contains("\"offset\":\"line 2\"").contains("NOTANUMBER");
        // The raw bytes as received, Base64 and never re-encoded: a record that failed to decode
        // cannot be described any other way, and the first question asked of a dead letter is
        // whether it can be replayed.
        Matcher raw = Pattern.compile("\"raw\":\"([^\"]*)\"").matcher(letters.get(0));
        assertThat(raw.find()).isTrue();
        assertThat(new String(Base64.getDecoder().decode(raw.group(1)), StandardCharsets.UTF_8))
                .isEqualTo("2,bob,NOTANUMBER,COMPLETED");
    }

    @Test
    void theLaneKeepsDrainingAfterARejection(@TempDir Path dir) throws IOException {
        // The reason rows are staged while a queue is attached. On the fast path the plugin decodes
        // straight into a claimed inbox cell, RowInbox.drain "stops at the first cell that is
        // claimed but not yet published", and there is no way to hand a claim back -- so a reader
        // that abandoned a row and kept going would stall the lane for ever, and the run would time
        // out with every row after the first bad one missing.
        //
        // Enough rows to cross many poll batches, with bad lines throughout, so a stall shows up as
        // missing output rather than as luck.
        StringBuilder input = new StringBuilder();
        int rows = 600;
        int bad = 0;
        for (int i = 1; i <= rows; i++) {
            boolean broken = i % 7 == 3;
            if (broken) {
                bad++;
            }
            input.append(i)
                    .append(",user")
                    .append(i)
                    .append(',')
                    .append(broken ? "BAD" : "10")
                    .append(",COMPLETED\n");
        }
        Path file = dir.resolve("txn.csv");
        Files.writeString(file, input.toString());
        Path output = dir.resolve("out.csv");
        Path dlq = dir.resolve("rejects.jsonl");

        int code = run(
                "run",
                "--sql",
                "SELECT user_id, amount FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                file.toString(),
                "--out",
                output.toString(),
                "--out-schema",
                "user_id:STRING,amount:INT64",
                "--dlq",
                dlq.toString());

        assertThat(code).isZero();
        assertThat(Files.readAllLines(output)).hasSize(rows - bad);
        assertThat(Files.readAllLines(dlq)).hasSize(bad);
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

    @Test
    void aMidStreamLaneFailureKeepsTheRowsThatCompletedBeforeIt(@TempDir Path dir) throws IOException {
        // TY-2. A div-by-zero on one row produced PRV-3010, exit 1, and an out.csv with ZERO rows --
        // not the rows that had already completed. That contradicts what the PRV-3010 message itself
        // promises: the offending record is diverted, not the batch. The failure and the exit code
        // are correct and stay; losing the other rows was a separate harm that nobody intended.
        Path input = dir.resolve("num.csv");
        Files.writeString(
                input,
                "1,alice,10,COMPLETED\n" + "2,bob,5,COMPLETED\n" + "3,carol,0,COMPLETED\n" + "4,dave,2,COMPLETED\n");
        Path out = dir.resolve("out.csv");

        int code = run(
                "run",
                "--sql",
                "SELECT user_id, 100 / amount AS ratio FROM txn",
                "--schema",
                SCHEMA,
                "--in",
                input.toString(),
                "--out",
                out.toString(),
                "--out-schema",
                "user_id:STRING,ratio:INT64");

        assertThat(code).as("the query still fails, and visibly").isEqualTo(1);
        assertThat(stderr()).contains("PRV-3010");
        assertThat(Files.readAllLines(out))
                .as("the rows that completed before the bad one survive; an empty file here says the "
                        + "engine produced nothing, which is not what happened")
                .isNotEmpty();
    }
}

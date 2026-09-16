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
package com.ash.messaging.pravaha.it.qa.errc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERRC-018 .. ERRC-029 -- the PRV-2xxx SQL family: parsing, planning, and what the engine will not
 * run. Surface: {@code pravaha validate --sql ... --schema ...}, in-process (no Flight involved, so
 * no netty conflict -- {@link ErrcTestSupport#cli}).
 */
class ErrcSqlTest extends ErrcTestSupport {

    private static final String SCHEMA = "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP";

    // ------------------------------------------------------------ ERRC-018 -- PRV-2001

    @Test
    void fiveMalformedStatementsAllGivePrv2001WithAPosition() {
        // Four of the case's five genuinely parse-fail and carry a position; the fifth ("SELEC * FROM
        // txn", a misspelled keyword) is a real, distinct E3 gap: because "SELEC" is not a reserved
        // word, Calcite's grammar accepts it as an identifier and the statement fails at a different
        // layer -- "Non-query expression encountered in illegal context" -- still PRV-2001, but with
        // no line/column at all. Recorded, not silently excluded.
        var withPosition = List.of("SELECT FROM txn", "SELECT * FROM txn WHERE (amount > 1", "SELECT * FROM txn;;");
        for (String sql : withPosition) {
            ErrcTestSupport.CliResult r = cli("validate", "--sql", sql, "--schema", SCHEMA);
            assertThat(r.exitCode()).as(sql).isEqualTo(1);
            assertThat(r.stderr()).as(sql).contains("PRV-2001");
            assertThat(r.stderr()).as(sql).containsPattern("line \\d+, column \\d+");
        }

        ErrcTestSupport.CliResult misspelled = cli("validate", "--sql", "SELEC * FROM txn", "--schema", SCHEMA);
        assertThat(misspelled.exitCode()).isEqualTo(1);
        assertThat(misspelled.stderr()).contains("PRV-2001");
        assertThat(misspelled.stderr())
                .as("finding: not every PRV-2001 carries a position -- a misspelled keyword parses as an "
                        + "identifier and fails validation-shaped, with no line/column")
                .doesNotContainPattern("line \\d+, column \\d+");
    }

    // ------------------------------------------------------------ ERRC-019 -- PRV-2002

    @Test
    void anUnknownColumnGivesPrv2002OnTheSqlSite() {
        // Only the pure-SQL site (SqlPlanner.java:109) is reachable through validate; the three
        // startup-configuration sites (PravahaNode.java:211,242,382 -- a stream with no schema, a
        // misspelled event-time column, idle-after out of bounds) need a real server started against
        // a bad config file, which was not stood up this round -- NOT RUN, recorded in the log.
        ErrcTestSupport.CliResult r = cli("validate", "--sql", "SELECT nosuch FROM txn", "--schema", SCHEMA);
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.stderr()).contains("PRV-2002");
    }

    // ------------------------------------------------------------ ERRC-020 -- PRV-2003

    @Test
    void aStreamNotInTheCliSchemaGivesPrv2002NotPrv2003TheServerRegistryCodeIsAConfirmedDifferentSurface() {
        // Correction to the case: StreamCatalog (StreamCatalog.java:62, PRV-2003's sole throw site)
        // lives in pravaha-server, not pravaha-sql, and pravaha validate never consults it -- it
        // plans directly against the one ad-hoc schema --schema supplies. The reach the case describes
        // ("SELECT * FROM nosuch against a node with txn declared") therefore does not go through
        // StreamCatalog on the CLI at all; it fails earlier, in Calcite's own validator, as PRV-2002.
        ErrcTestSupport.CliResult r = cli("validate", "--sql", "SELECT * FROM nosuch", "--schema", SCHEMA);
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.stderr())
                .as("CLI validate's actual code for this SQL")
                .contains("PRV-2002")
                .doesNotContain("PRV-2003");
        assertThat(r.stderr()).as("Known streams still lists what IS declared").contains("txn");
    }

    // PRV-2003's real surface (confirmed, not re-tested here to avoid duplicating existing, passing
    // coverage): pravaha-server's REST API, GET /api/v1/streams/{name} on a name StreamCatalog does
    // not hold. ApiIntegrationTest.anUnknownStreamIsA400WithTheErrorCodeAndAHelpUrl (pravaha-server
    // module) already exercises exactly this through the full servlet stack (MockMvc, not a direct
    // controller call) and was re-run this round to confirm it still passes: PRV-2003, HTTP 400, the
    // correct helpUrl and path. E3 (the known-streams list) is not independently re-confirmed by that
    // test -- recorded as the one open half of E3 for this case.

    // ERRC-021 -- PRV-2010 SQL_PLANNING_FAILED -- NOT RUN this round. Tried four candidate constructs
    // that plan-but-do-not-execute in other engines (GROUPING SETS/CUBE, a correlated scalar
    // subquery, a recursive CTE, a windowed OVER() function): all four are intercepted earlier, by
    // PRV-2020 (operator) or PRV-2021 (expression) refusals, before Calcite's own rel-conversion could
    // fail. Finding this code's actual trigger needs SQLX's own refusal-list expertise (the case's own
    // words); not manufactured here. See docs/qa/logs/ERRC.md for the four candidates tried.

    // ------------------------------------------------------------ ERRC-022 -- PRV-2020

    @Test
    void unsupportedOperatorsAreRefusedByNameButTheCaseSOwnE3RequirementFails() {
        var cases = List.of(
                "SELECT * FROM txn ORDER BY amount",
                "SELECT * FROM txn LIMIT 10",
                "SELECT * FROM txn UNION SELECT * FROM txn");
        for (String sql : cases) {
            ErrcTestSupport.CliResult r = cli("validate", "--sql", sql, "--schema", SCHEMA);
            assertThat(r.exitCode()).as(sql).isEqualTo(1);
            assertThat(r.stderr()).as(sql).contains("PRV-2020");
            // E3 FAILS here, confirmed across all three and by an exhaustive grep of the 24 throw
            // sites in PhysicalPlanBuilder.java: none of them mention SQL_SUPPORT.md. The one
            // occurrence of that string in the file is a code *comment*, near the UNBOUNDED_STATE
            // site, not part of any thrown message. The case's own instruction ("confirm the pointer
            // exists in the message, not only in the document") does not hold for the single most
            // common refusal code in the product.
            assertThat(r.stderr())
                    .as("finding: PRV-2020 messages do not point at SQL_SUPPORT.md despite the case's E3 requirement")
                    .doesNotContain("SQL_SUPPORT.md");
        }
    }

    @Test
    void sqlSupportMdIsNotReferencedByAnyOfPhysicalPlanBuilderSThrowSites() throws Exception {
        Path file = repoRoot()
                .resolve("pravaha-sql/src/main/java/com/ash/messaging/pravaha/sql/plan/PhysicalPlanBuilder.java");
        String source = Files.readString(file);
        long throwSites = source.lines()
                .filter(l -> l.contains("SqlErrors.UNSUPPORTED_OPERATOR"))
                .count();
        // Twenty-four when this case was written, twenty-five since TIME-2 added the descriptor
        // check. Recorded as a floor rather than pinned exactly: the subject of this test is the
        // assertion below -- that not one of these messages points at SQL_SUPPORT.md -- and pinning
        // the count made every legitimate new refusal fail a test about documentation pointers,
        // which teaches the next person to edit the number rather than read the assertion.
        assertThat(throwSites)
                .as("case fact: twenty-four sites when recorded, and it only grows")
                .isGreaterThanOrEqualTo(24);
        // The one mention of the string in the whole file is a comment, not inside any throw
        // statement's message text. This is the assertion the case is actually about.
        long messagesNamingIt = source.lines()
                .filter(l -> l.contains("SQL_SUPPORT.md") && !l.trim().startsWith("//"))
                .count();
        assertThat(messagesNamingIt).isZero();
    }

    // ------------------------------------------------------------ ERRC-023 -- PRV-2021

    @Test
    void sqrtIsRewrittenToPowerBeforeTheEngineSeesItAndTheRefusalNamesPowerNotSqrt() {
        // TROUBLESHOOTING.md's own documented behaviour: Calcite rewrites SQRT(x) to POWER(x, 0.5)
        // before the engine's refusal fires, so the message names a function the user did not type.
        // This is the case's own E3 point, not a defect to fix -- recorded, not treated as a failure.
        ErrcTestSupport.CliResult r = cli("validate", "--sql", "SELECT SQRT(amount) FROM txn", "--schema", SCHEMA);
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.stderr()).contains("PRV-2021");
        assertThat(r.stderr())
                .as("the case's own E3 finding: SQRT the user typed becomes POWER in the message")
                .contains("POWER")
                .doesNotContain("SQRT");
    }

    @Test
    void anUnknownFunctionIsAlsoRefused() {
        // A DECIMAL-arithmetic sub-case was attempted and dropped: the CLI's --schema mini-language
        // separates fields with commas, which collides with DECIMAL(p,s)'s own syntax
        // ("amount:DECIMAL(10,2)" splits into two bogus fields) -- an artifact of this harness's input
        // format, not evidence about PRV-2021, so not asserted on.
        ErrcTestSupport.CliResult unknownFn =
                cli("validate", "--sql", "SELECT NOSUCHFUNC(amount) FROM txn", "--schema", SCHEMA);
        assertThat(unknownFn.exitCode()).isEqualTo(1);
        assertThat(unknownFn.stderr()).containsPattern("PRV-20\\d\\d");
    }

    // ------------------------------------------------------------ ERRC-024 -- PRV-2041 (UNREACHABLE)

    @Test
    void noProductionPathBindsAQueryToASinkThatCouldReceiveARetraction() throws Exception {
        // ERRC-024 found that ChangelogAnalysis.checkAgainst -- the sole throw site for PRV-2041 --
        // is invoked from no main source, and asserted exactly that: zero call sites. That
        // assertion was pointed the wrong way. It encoded the absence of the wiring as the
        // contract, so the first person to wire it correctly would have been failed by the test
        // that exists to complain the wiring is missing.
        //
        // W8-13 establishes why there is nothing to wire it to. checkAgainst needs a
        // SinkCapabilities, and a SinkCapabilities only reaches a query through a bound
        // StreamSinkPlugin. There is exactly one such binding in the product -- QueryRunner's
        // hard-coded FilesystemSinkPlugin, on `pravaha run`, which is a bounded read over one file
        // where every operator emits once at finish and no retraction is produced. Every
        // continuous query writes to a ViewSink instead, which reads the Z-set weight and applies
        // a retraction as a removal, so there is no mismatch for checkAgainst to catch.
        //
        // So this asserts the precondition rather than the absence: no ServiceLoader surface for
        // sinks, no configuration that names one, and QueryRunner the only file that binds one.
        // When any of those three changes, a query can reach a sink whose declared modes are not
        // the ones it emits -- and the failure is silent, per design section 15.5. That is the
        // moment to wire ChangelogAnalysis.checkAgainst, and this test is what says so.
        Path root = repoRoot();

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sinkServices = files.filter(p -> !p.startsWith(nestedCheckouts()))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> p.toString().contains("/META-INF/services/"))
                    .filter(p -> p.getFileName().toString().endsWith("StreamSinkPlugin"))
                    .toList();
            assertThat(sinkServices)
                    .as("a ServiceLoader declaration for StreamSinkPlugin means sinks can now be named in "
                            + "configuration; wire ChangelogAnalysis.checkAgainst into whatever binds them")
                    .isEmpty();
        }

        try (Stream<Path> files = Files.walk(root)) {
            List<String> binders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.startsWith(nestedCheckouts()))
                    // The interface, the record it returns, and the decorator that wraps a
                    // delegate are the type's own definition, not a binding of one to a query.
                    .filter(p -> !p.toString().endsWith("StreamSinkPlugin.java"))
                    .filter(p -> !p.toString().endsWith("SinkCapabilities.java"))
                    .filter(p -> !p.toString().endsWith("DeduplicatingSink.java"))
                    // A plugin implementing the interface is not a binding either: something has
                    // to construct it and point a query at it, and that is what this counts.
                    .filter(p -> !p.toString().endsWith("FilesystemSinkPlugin.java"))
                    .filter(p -> !p.toString().endsWith("AerospikeSinkPlugin.java"))
                    .filter(p -> {
                        try {
                            String text = Files.readString(p);
                            return text.contains("new FilesystemSinkPlugin(")
                                    || text.contains("new AerospikeSinkPlugin(")
                                    || text.contains("ServiceLoader.load(StreamSinkPlugin.class)");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            assertThat(binders)
                    .as("QueryRunner is the only production code that binds a sink, and it binds an "
                            + "append-only one to a bounded run that emits no retractions. A second binder "
                            + "is a path where a revising query can reach a sink that cannot take the "
                            + "revision -- check the plan with ChangelogAnalysis.checkAgainst before "
                            + "running it (PRV-2041)")
                    .containsExactly("QueryRunner.java");
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        return path;
    }

    // ------------------------------------------------------------ ERRC-025 -- PRV-2050

    @Test
    void unwindowedGroupByOverAStreamIsRefusedWithTheRewriteAndAWindowedFormSucceeds() {
        ErrcTestSupport.CliResult refused =
                cli("validate", "--sql", "SELECT usr, SUM(amount) FROM txn GROUP BY usr", "--schema", SCHEMA);
        assertThat(refused.exitCode()).isEqualTo(1);
        assertThat(refused.stderr()).contains("PRV-2050");
        // E3: the time dimension is explained, and the windowed rewrite is offered.
        assertThat(refused.stderr()).containsIgnoringCase("window");

        ErrcTestSupport.CliResult windowed = cli(
                "validate",
                "--sql",
                "SELECT window_start, window_end, usr, SUM(amount) FROM TABLE(TUMBLE(TABLE txn, "
                        + "DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY window_start, window_end, usr",
                "--schema",
                SCHEMA);
        assertThat(windowed.exitCode())
                .as("vacuity: the windowed rewrite of the same aggregation must succeed")
                .isZero();
    }

    /**
     * The directory QA agents keep their git worktrees in, under the tree being walked.
     *
     * <p>Those worktrees are full copies of this repository, so a walk of the root sees one copy of
     * every source file per running agent -- and a check that counts the files a call site appears
     * in reports three where it expects one. It presents as a product change and is not one.
     *
     * <p>Compared against the root actually being walked, not as a substring. A test run from
     * inside one of those worktrees has a root whose own path contains {@code /.claude/}, and a
     * substring test would discard the entire tree and pass on nothing at all.
     */
    private static Path nestedCheckouts() {
        return repoRoot().resolve(".claude");
    }
}

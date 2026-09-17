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
        // SX-5 removed the list of declared names from this message, and this assertion is the
        // cost of that, written down rather than quietly dropped. On the CLI the schema came from
        // the caller's own --schema flag a moment earlier, so naming it back discloses nothing --
        // but SqlPlanner cannot tell a local `pravaha validate` from a remote reader, and the
        // remote case is where the list becomes a catalogue dump to someone authorized for nothing.
        //
        // So the suppression is blanket and the CLI pays for it. Recorded as SX-19: the CLI owns
        // the schema it passed in and could append those names itself when it catches PRV-2002,
        // which restores the help exactly where it is safe.
        assertThat(r.stderr())
                .as("the count is kept; the names are not, and on this surface that is a loss (SX-19)")
                .contains("stream(s) declared")
                .doesNotContain("txn:");
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
            // sites in PhysicalPlanBuilder.java: none of them mention CONTINUOUS_QUERIES.md. The one
            // occurrence of that string in the file is a code *comment*, near the UNBOUNDED_STATE
            // site, not part of any thrown message. The case's own instruction ("confirm the pointer
            // exists in the message, not only in the document") does not hold for the single most
            // common refusal code in the product.
            // E-11, fixed: this asserted the absence and was right to. Not one of the twenty-four
            // refusals named the document listing what this engine executes, so a user holding the
            // error had nowhere to go. Every site now routes through `unsupported`, which appends
            // the pointer once.
            assertThat(r.stderr())
                    .as("the refusal now names the document that answers the question it raises")
                    .contains("CONTINUOUS_QUERIES.md");
        }
    }

    @Test
    void sqlSupportMdIsNotReferencedByAnyOfPhysicalPlanBuilderSThrowSites() throws Exception {
        Path file = repoRoot()
                .resolve("pravaha-sql/src/main/java/com/ash/messaging/pravaha/sql/plan/PhysicalPlanBuilder.java");
        String source = Files.readString(file);
        // Counted by call to `unsupported(`, not by mention of the code. E-11 routed all of them
        // through one helper so the pointer to CONTINUOUS_QUERIES.md is added once rather than remembered
        // twenty-five times -- which means the code name now appears exactly once in the file, and
        // counting *that* would report one site where there are twenty-five.
        long throwSites = source.lines()
                .filter(l -> l.contains("unsupported(") && !l.contains("private static"))
                .count();
        // Twenty-four when this case was written, twenty-five since TIME-2 added the descriptor
        // check. Recorded as a floor rather than pinned exactly: the subject of this test is the
        // assertion below -- that not one of these messages points at CONTINUOUS_QUERIES.md -- and pinning
        // the count made every legitimate new refusal fail a test about documentation pointers,
        // which teaches the next person to edit the number rather than read the assertion.
        assertThat(throwSites)
                .as("case fact: twenty-four sites when recorded, and it only grows")
                .isGreaterThanOrEqualTo(24);
        // The one mention of the string in the whole file is a comment, not inside any throw
        // statement's message text. This is the assertion the case is actually about.
        // E-11, inverted. This asserted zero -- correctly, when written: not one of the twenty-four
        // refusals named the document that lists what this engine executes, so a user holding the
        // error had no idea where to look. TROUBLESHOOTING.md said the surface lives in
        // CONTINUOUS_QUERIES.md, which is true of the document and no use at all to somebody holding the
        // error.
        //
        // The pointer is added once, in the `unsupported` helper every site now goes through,
        // rather than twenty-five times -- because the twenty-sixth site is written by somebody who
        // has not read any of this. So the assertion is that exactly one place names it, and that
        // no site bypasses the helper.
        assertThat(source.lines()
                        .filter(l -> l.contains("CONTINUOUS_QUERIES.md"))
                        .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//"))
                        .filter(l -> l.contains("\""))
                        .count())
                .as("the pointer is centralised in unsupported(), so exactly one *message* carries it")
                .isEqualTo(1);
        assertThat(source.contains("new PravahaException(SqlErrors.UNSUPPORTED_OPERATOR"))
                .as("a site that builds the exception directly would skip the pointer")
                .isFalse();
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
    void aQueryIsNeverAttachedToASinkWithoutCheckingItsChangelogFirst() throws Exception {
        // ERRC-024 found that ChangelogAnalysis.checkAgainst -- the sole throw site for PRV-2041 --
        // was called from nowhere, and this test has guarded that absence through three rounds while
        // W8-13 built the sink side beneath it. **It was written to fail at the commit that finally
        // attaches a query to a sink, and that commit has now happened**, so the assertion is
        // inverted rather than deleted: the guard is no longer "nothing calls it" but "whatever
        // attaches must call it, and must call it first".
        //
        // Why first matters more than whether. Design section 15.5's failure is silent: a query that
        // revises its answer, pointed at a sink that can only append, corrupts that sink with rows
        // which are each individually correct and a total that is wrong for ever. A check run after
        // the feed opens is a check that runs after the corruption has begun.
        Path root = repoRoot();

        try (Stream<Path> files = Files.walk(root)) {
            List<String> callers = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.startsWith(nestedCheckouts()))
                    // Its own declaration, not a call to it.
                    .filter(p -> !p.toString().endsWith("ChangelogAnalysis.java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("ChangelogAnalysis.checkAgainst(");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            assertThat(callers)
                    .as("something in production must call ChangelogAnalysis.checkAgainst now that a "
                            + "registration can name a sink (ADR-043). If this is empty again, the "
                            + "attachment has been built or kept without the changelog negotiation that "
                            + "makes it safe, and PRV-2041 is unreachable once more")
                    .isNotEmpty();
        }

        // And the ordering, which is the part a caller could get wrong while still calling it: the
        // check must precede the feed opening. Reading the source is crude, and it is the only way
        // to assert an ordering that has no runtime observable when the query is well-formed -- a
        // correctly-checked registration and an unchecked one look identical unless the sink refuses.
        String registry = Files.readString(
                root.resolve("pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/QueryRegistry.java"));
        int checkedAt = registry.indexOf("ChangelogAnalysis.checkAgainst(");
        int feedOpenedAt = registry.indexOf("feeds.open(");
        assertThat(checkedAt).as("the registry must call checkAgainst at all").isNotNegative();
        assertThat(feedOpenedAt).as("the registry must still open a feed").isNotNegative();
        assertThat(checkedAt)
                .as("checkAgainst must be called BEFORE feeds.open: a sink that cannot take this "
                        + "query's changelog has to be refused before a single row can be produced, "
                        + "not after the feed has started delivering (design section 15.5)")
                .isLessThan(feedOpenedAt);
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

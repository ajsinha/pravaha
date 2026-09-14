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
    void sqlSupportMdIsNotReferencedByAnyOfPhysicalPlanBuilderSTwentyFourThrowSites() throws Exception {
        Path file = repoRoot()
                .resolve("pravaha-sql/src/main/java/com/ash/messaging/pravaha/sql/plan/PhysicalPlanBuilder.java");
        String source = Files.readString(file);
        long throwSites = source.lines()
                .filter(l -> l.contains("SqlErrors.UNSUPPORTED_OPERATOR"))
                .count();
        assertThat(throwSites).as("case fact: twenty-four sites").isEqualTo(24);
        // The one mention of the string in the whole file is a comment, not inside any of the
        // twenty-four throw statements' message text.
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
    void changelogAnalysisCheckAgainstIsCalledFromNowhereInMainSources() throws Exception {
        // The single most important finding in this family, and one the case file's own fact 9 did
        // NOT already know about (2041 is not among the nine it lists as throwless). Exhaustive
        // search, not a sample: ChangelogAnalysis.checkAgainst -- the sole throw site for PRV-2041 --
        // is invoked nowhere in any module's main sources, only from its own unit test
        // (ChangelogAnalysisTest). SinkCapabilities is implemented by FilesystemSinkPlugin and
        // AerospikeSinkPlugin, and StreamSchema's own javadoc *claims* "ChangelogAnalysis refuses an
        // append-only sink for a revising query -- correctly, and at registration" -- a claim about
        // wiring that does not exist. A windowed aggregate with allowed lateness (which revises
        // its answer and needs an upsert/retract-capable sink) registered against the filesystem
        // sink (append-only) is accepted rather than refused, per design section 15.5's own predicted
        // failure mode: "the query runs, results are written... nothing has failed."
        Path root = repoRoot();
        long mainSourceCallSites;
        try (Stream<Path> files = Files.walk(root)) {
            mainSourceCallSites = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().endsWith("ChangelogAnalysis.java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("ChangelogAnalysis.checkAgainst");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
        }
        assertThat(mainSourceCallSites)
                .as("ChangelogAnalysis.checkAgainst is unreachable from any product surface: nothing in main "
                        + "sources outside its own class calls it")
                .isZero();
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
}

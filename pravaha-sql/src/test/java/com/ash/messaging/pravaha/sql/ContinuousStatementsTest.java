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
package com.ash.messaging.pravaha.sql;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recognizer for the continuous-query statements: every form, every alias, case and quoting,
 * and the refusals -- which must name the expected shape and never reach Calcite.
 */
class ContinuousStatementsTest {

    private static final String SELECT = "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id";

    private static ContinuousStatement recognized(String sql) {
        return ContinuousStatements.recognize(sql).orElseThrow(() -> new AssertionError("not recognized: " + sql));
    }

    private static ContinuousStatement.Create create(String sql) {
        return (ContinuousStatement.Create) recognized(sql);
    }

    private static PravahaException refusal(String sql) {
        try {
            ContinuousStatements.recognize(sql);
        } catch (PravahaException e) {
            return e;
        }
        throw new AssertionError("not refused: " + sql);
    }

    // ------------------------------------------------------------------ the canonical forms

    @Test
    void theMinimalCreateCarriesItsNameKeyAndSelectAndNothingElse() {
        ContinuousStatement.Create create = create("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS " + SELECT);

        assertThat(create.name()).isEqualTo("spend");
        assertThat(create.keyColumns()).containsExactly("user_id");
        assertThat(create.sink()).isEmpty();
        assertThat(create.retain()).isEmpty();
        assertThat(create.select()).isEqualTo(SELECT);
        assertThat(create.verb()).isEqualTo("CREATE CONTINUOUS QUERY");
    }

    @Test
    void everyClauseTogetherAcrossLinesWithATrailingSemicolon() {
        ContinuousStatement.Create create = create("""
                CREATE CONTINUOUS QUERY spend
                    KEYED BY (user_id, region)
                    WRITING TO spend_sink
                    RETAIN FOR PT24H
                AS
                SELECT user_id, region, SUM(amount) AS total FROM txn GROUP BY user_id, region;
                """);

        assertThat(create.keyColumns()).containsExactly("user_id", "region");
        assertThat(create.sink()).contains("spend_sink");
        assertThat(create.retain()).contains(ContinuousStatement.Retain.of(Duration.ofHours(24)));
        assertThat(create.select())
                .as("the SELECT as written, less the semicolon")
                .isEqualTo("SELECT user_id, region, SUM(amount) AS total FROM txn GROUP BY user_id, region");
    }

    @Test
    void theClausesBeforeAsMayComeInAnyOrder() {
        ContinuousStatement.Create create =
                create("CREATE CONTINUOUS QUERY spend RETAIN FOREVER WRITING TO s KEYED BY (user_id) AS " + SELECT);

        assertThat(create.retain()).contains(ContinuousStatement.Retain.forever());
        assertThat(create.sink()).contains("s");
        assertThat(create.keyColumns()).containsExactly("user_id");
    }

    @Test
    void aRetentionIsAnIsoDurationBareOrQuotedOrAnIntervalInOneUnit() {
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR 'P7D' AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofDays(7)));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR pt30m AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofMinutes(30)));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '24' HOUR AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofHours(24)));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '90' SECONDS AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofSeconds(90)));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '5' MINUTE AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofMinutes(5)));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '2' DAY AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofDays(2)));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '1' WEEK AS SELECT a FROM t")
                        .retain())
                .contains(ContinuousStatement.Retain.of(Duration.ofDays(7)));
    }

    @Test
    void dropPauseResumeAndShow() {
        assertThat(recognized("DROP CONTINUOUS QUERY spend")).isEqualTo(new ContinuousStatement.Drop("spend"));
        assertThat(recognized("PAUSE CONTINUOUS QUERY spend;")).isEqualTo(new ContinuousStatement.Pause("spend"));
        assertThat(recognized("  RESUME CONTINUOUS QUERY spend ; ")).isEqualTo(new ContinuousStatement.Resume("spend"));
        assertThat(recognized("SHOW CONTINUOUS QUERIES")).isEqualTo(new ContinuousStatement.Show());
        assertThat(recognized("show continuous queries;")).isEqualTo(new ContinuousStatement.Show());
    }

    // ------------------------------------------------------------------ the design's spellings

    @Test
    void theDesignsSpellingsAreAliases() {
        ContinuousStatement.Create create = create("""
                CREATE CONTINUOUS QUERY user_volume
                INTO user_volume_agg
                SERVE AS VIEW user_volume
                  INDEXED BY (user_id)
                AS SELECT STREAM user_id, COUNT(*) AS txn_count FROM txn GROUP BY user_id
                EMIT CHANGES;
                """);

        assertThat(create.name()).isEqualTo("user_volume");
        assertThat(create.sink()).contains("user_volume_agg");
        assertThat(create.keyColumns()).containsExactly("user_id");
        assertThat(create.select())
                .as("EMIT CHANGES and the semicolon are not handed to the planner")
                .isEqualTo("SELECT STREAM user_id, COUNT(*) AS txn_count FROM txn GROUP BY user_id");
    }

    @Test
    void emitChangesInsideAStringOrACommentIsPartOfTheSelect() {
        ContinuousStatement.Create inString =
                create("CREATE CONTINUOUS QUERY v KEYED BY (a) AS SELECT a FROM t WHERE b = 'EMIT CHANGES'");
        assertThat(inString.select()).isEqualTo("SELECT a FROM t WHERE b = 'EMIT CHANGES'");

        ContinuousStatement.Create inComment =
                create("CREATE CONTINUOUS QUERY v KEYED BY (a) AS SELECT a FROM t -- EMIT CHANGES");
        assertThat(inComment.select()).isEqualTo("SELECT a FROM t -- EMIT CHANGES");
    }

    // ------------------------------------------------------------------ case and quoting

    @Test
    void keywordsAreCaseInsensitiveAndNamesKeepTheirCase() {
        ContinuousStatement.Create create =
                create("create Continuous query DailySpend keyed by (User_Id) writing to Spend_Sink as select 1 AS x");

        assertThat(create.name())
                .as("identifiers are case-sensitive here, as the planner's are")
                .isEqualTo("DailySpend");
        assertThat(create.keyColumns()).containsExactly("User_Id");
        assertThat(create.sink()).contains("Spend_Sink");
    }

    @Test
    void quotedNamesAreUnquotedWithTheirEscapesUndone() {
        ContinuousStatement.Create create = create(
                "CREATE CONTINUOUS QUERY \"spend\" KEYED BY (\"user id\", \"a\"\"b\") WRITING TO \"audit-log\" AS SELECT 1");

        assertThat(create.name()).isEqualTo("spend");
        assertThat(create.keyColumns()).containsExactly("user id", "a\"b");
        assertThat(create.sink()).contains("audit-log");
        assertThat(recognized("DROP CONTINUOUS QUERY \"Mixed Case\""))
                .isEqualTo(new ContinuousStatement.Drop("Mixed Case"));
    }

    @Test
    void aNameMayBeOneOfTheStatementsOwnWordsBecauseItsPositionDecides() {
        assertThat(create("CREATE CONTINUOUS QUERY keyed KEYED BY (retain) AS SELECT 1 AS retain")
                        .name())
                .isEqualTo("keyed");
        assertThat(create("CREATE CONTINUOUS QUERY retain KEYED BY (a) WRITING TO writing AS SELECT 1 AS a")
                        .sink())
                .contains("writing");
        // A SQL reserved word is read as a name here and refused by the registry, which is the one
        // place that knows whether a view by that name could ever be read in a FROM clause.
        assertThat(create("CREATE CONTINUOUS QUERY \"select\" KEYED BY (a) AS SELECT 1 AS a")
                        .name())
                .isEqualTo("select");
        assertThat(recognized("DROP CONTINUOUS QUERY as")).isEqualTo(new ContinuousStatement.Drop("as"));
    }

    @Test
    void leadingCommentsDoNotHideTheStatement() {
        assertThat(recognized("-- tidy up\n/* nightly */ DROP CONTINUOUS QUERY spend"))
                .isEqualTo(new ContinuousStatement.Drop("spend"));
    }

    // ------------------------------------------------------------------ what is not one of these

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM spend",
                "select pause from t",
                "CREATE TABLE t (a INT)",
                "CREATE VIEW v AS SELECT 1",
                "DROP TABLE t",
                "SHOW TABLES",
                "WITH x AS (SELECT 1) SELECT * FROM x",
                "",
                "   ",
                "'CREATE CONTINUOUS QUERY v'",
                "-- CREATE CONTINUOUS QUERY v\nSELECT 1",
                "INSERT INTO sink SELECT * FROM t",
            })
    void ordinarySqlIsNotRecognizedAndNotRefused(String sql) {
        assertThat(ContinuousStatements.recognize(sql)).isEmpty();
        assertThat(ContinuousStatements.isContinuousStatement(sql)).isFalse();
    }

    @Test
    void nullIsNothing() {
        assertThat(ContinuousStatements.recognize(null)).isEmpty();
        assertThat(ContinuousStatements.isContinuousStatement(null)).isFalse();
    }

    // ------------------------------------------------------------------ malformed

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE CONTINUOUS QUERY",
                "CREATE CONTINUOUS QUERY v",
                "CREATE CONTINUOUS QUERY v AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY () AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a,) AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED (a) AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a)",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) AS",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) AS ;",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) AS INSERT INTO s SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) KEYED BY (b) AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) INDEXED BY (b) AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) WRITING s AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) WRITING TO s INTO t AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR a_day AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR 'a day' AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR PT0S AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR 'PT-1H' AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL 24 HOUR AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '1' MONTH AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '1' FORTNIGHT AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOR INTERVAL '0' DAY AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) RETAIN FOREVER RETAIN FOREVER AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) FOO AS SELECT 1",
                "CREATE CONTINUOUS QUERY v KEYED BY (a) EMIT CHANGES AS SELECT 1",
                "CREATE CONTINUOUS QUERY 'v' KEYED BY (a) AS SELECT 1",
                "CREATE CONTINUOUS QUERY \"\" KEYED BY (a) AS SELECT 1",
                "CREATE CONTINUOUS QUERY \"v KEYED BY (a) AS SELECT 1",
                "CREATE CONTINUOUS QUERY v /* KEYED BY (a) AS SELECT 1",
                "CREATE CONTINUOUS TABLE v",
                "DROP CONTINUOUS QUERY",
                "DROP CONTINUOUS QUERY a b",
                "DROP CONTINUOUS QUERY a;;",
                "DROP CONTINUOUS VIEW a",
                "PAUSE spend",
                "PAUSE QUERY spend",
                "RESUME",
                "RESUME CONTINUOUS QUERY spend now",
                "SHOW CONTINUOUS QUERY",
                "SHOW CONTINUOUS QUERIES LIKE 'x'",
            })
    void aStatementThatStartsAsOneOfTheseAndGoesWrongIsRefusedWithItsShape(String sql) {
        PravahaException refused = refusal(sql);

        assertThat(refused.errorCode()).isEqualTo(SqlErrors.STATEMENT_MALFORMED);
        assertThat(refused.getMessage())
                .as("never Calcite's parse error, always the shape that was expected")
                .contains("PRV-2070")
                .contains("The statement's shape is:")
                .contains("CONTINUOUS")
                .contains("line 1, column");
        assertThat(ContinuousStatements.isContinuousStatement(sql))
                .as("recognised as one of these even though it is malformed")
                .isTrue();
    }

    @Test
    void theRefusalSaysWhatWasExpectedWhatWasFoundAndWhere() {
        PravahaException refused =
                refusal("CREATE CONTINUOUS QUERY spend\n  KEYED BY (user_id)\n  WRITTEN TO s AS SELECT 1");

        assertThat(refused.getMessage())
                .contains("found 'WRITTEN'")
                .contains("line 3, column 3")
                .contains(ContinuousStatements.CREATE_SHAPE);
    }

    @Test
    void aMissingKeyIsNamedAsTheProblem() {
        assertThat(refusal("CREATE CONTINUOUS QUERY spend AS SELECT 1").getMessage())
                .contains("has no key")
                .contains("KEYED BY");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE CONTINUOUS QUERY v KEYED BY (a) AS SELECT 1 EMIT CHANGES WITH ('parallelism' = '16')",
                "CREATE CONTINUOUS QUERY q_user_volume SERVE AS VIEW user_volume INDEXED BY (a) AS SELECT 1",
            })
    void theDesignsClausesThatAreNotBuiltAreRefusedByNameNotIgnored(String sql) {
        PravahaException refused = refusal(sql);

        assertThat(refused.errorCode()).isEqualTo(SqlErrors.CLAUSE_NOT_BUILT);
        assertThat(refused.getMessage()).contains("PRV-2072").contains("The statement's shape is:");
    }

    @Test
    void createOrReplaceIsRecognisedAndSaysSo() {
        ContinuousStatement.Create create = (ContinuousStatement.Create)
                ContinuousStatements.recognize("CREATE OR REPLACE CONTINUOUS QUERY spend KEYED BY (user_id) "
                                + "AS SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id")
                        .orElseThrow();

        assertThat(create.orReplace()).isTrue();
        assertThat(create.verb()).isEqualTo("CREATE OR REPLACE CONTINUOUS QUERY");
        assertThat(create.name()).isEqualTo("spend");
        assertThat(create.options()).isEmpty();
    }

    @Test
    void aReplacementsOptionsAreReadIncludingTheDottedOnes() {
        ContinuousStatement.Create create = (ContinuousStatement.Create)
                ContinuousStatements.recognize("CREATE OR REPLACE CONTINUOUS QUERY spend KEYED BY (user_id) "
                                + "WITH (backfill = 'history', backfill.rate.limit = 1000, cutover = auto, "
                                + "rollback.retention = 'PT30M') AS SELECT 1")
                        .orElseThrow();

        assertThat(create.options())
                .containsOnly(
                        java.util.Map.entry("backfill", "history"),
                        java.util.Map.entry("backfill.rate.limit", "1000"),
                        java.util.Map.entry("cutover", "auto"),
                        java.util.Map.entry("rollback.retention", "PT30M"));
    }

    // ------------------------------------------------------------------ B8: RANGE

    @Test
    void theDesignsRangeSpellingMakesTheOrderedColumnTheKeysLast() {
        // Design section 17.2, verbatim: INDEXED BY (user_id) RANGE (window_end) -- "point + range".
        // The two together are the key, so the column RANGE names is appended to it.
        ContinuousStatement.Create create = create("""
                CREATE CONTINUOUS QUERY user_volume
                  INDEXED BY (user_id) RANGE (window_end)
                AS SELECT user_id, window_end, COUNT(*) AS n FROM txn GROUP BY user_id, window_end
                """);

        assertThat(create.keyColumns()).containsExactly("user_id", "window_end");
        assertThat(create.rangeColumn()).contains("window_end");
    }

    @Test
    void aRangeColumnTheKeyAlreadyEndsWithIsNotAddedTwice() {
        ContinuousStatement.Create create =
                create("CREATE CONTINUOUS QUERY v KEYED BY (a, b) RANGE (b) AS SELECT a, b FROM t");

        assertThat(create.keyColumns()).containsExactly("a", "b");
        assertThat(create.rangeColumn()).contains("b");
    }

    @Test
    void aRangeWithNoKeyOfItsOwnIsTheKey() {
        ContinuousStatement.Create create = create("CREATE CONTINUOUS QUERY v RANGE (t) AS SELECT t FROM s");

        assertThat(create.keyColumns()).containsExactly("t");
        assertThat(create.rangeColumn()).contains("t");
    }

    @Test
    void aRangeCanBeWrittenBeforeTheKeyLikeEveryOtherClause() {
        ContinuousStatement.Create create =
                create("CREATE CONTINUOUS QUERY v RANGE (b) KEYED BY (a) RETAIN FOREVER AS SELECT a, b FROM t");

        assertThat(create.keyColumns()).containsExactly("a", "b");
    }

    @Test
    void aRangeOverSomethingThatIsNotTheKeysLastColumnIsRefusedWhereItGoesWrong() {
        assertThat(refusal("CREATE CONTINUOUS QUERY v KEYED BY (a, b) RANGE (a) AS SELECT 1")
                        .getMessage())
                .contains("PRV-2070")
                .contains("position 1 of 2")
                .contains("last");
        assertThat(refusal("CREATE CONTINUOUS QUERY v KEYED BY (a) RANGE (b, c) AS SELECT 1")
                        .getMessage())
                .contains("PRV-2070")
                .contains("an ordered index is kept over one");
        assertThat(refusal("CREATE CONTINUOUS QUERY v KEYED BY (a) RANGE (b) RANGE (c) AS SELECT 1")
                        .getMessage())
                .contains("RANGE is given twice");
    }

    // ------------------------------------------------------------------ B8: WITH on a plain CREATE

    @Test
    void aPlainCreateCarriesItsOptionListToBeJudgedByTheRegistry() {
        // Read here, judged in RegistrationOptions: the recognizer's job is the shape, and which
        // options exist depends on whether this is a registration or a replacement.
        ContinuousStatement.Create create = create(
                "CREATE CONTINUOUS QUERY v KEYED BY (a) WITH ('retention' = '24h', sink = warehouse) " + "AS SELECT 1");

        assertThat(create.orReplace()).isFalse();
        assertThat(create.options())
                .containsExactly(java.util.Map.entry("retention", "24h"), java.util.Map.entry("sink", "warehouse"));
    }

    @Test
    void anOptionNameMayBeWrittenAsTheDesignWritesIt() {
        // Design sections 11.2 and 17.2 single-quote their option names. A grammar that refused the
        // document it came from would be a second grammar.
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) WITH ('retention' = 'PT1H') AS SELECT 1")
                        .options())
                .containsExactly(java.util.Map.entry("retention", "PT1H"));
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (a) WITH (\"retention\" = 'PT1H') AS SELECT 1")
                        .options())
                .containsExactly(java.util.Map.entry("retention", "PT1H"));
        assertThat(create("CREATE OR REPLACE CONTINUOUS QUERY v KEYED BY (a) "
                                + "WITH ('backfill'.'rate'.'limit' = 10) AS SELECT 1")
                        .options())
                .containsExactly(java.util.Map.entry("backfill.rate.limit", "10"));
    }

    @Test
    void aKeyFromAnOptionStandsInForKeyedBy() {
        ContinuousStatement.Create create =
                create("CREATE CONTINUOUS QUERY v WITH (keys = 'a, b') AS SELECT a, b FROM t");

        assertThat(create.keyColumns()).as("resolved by the registry, not here").isEmpty();
        assertThat(create.options()).containsExactly(java.util.Map.entry("keys", "a, b"));
    }

    @Test
    void sayingTheKeyTwiceIsRefusedRatherThanResolved() {
        assertThat(refusal("CREATE CONTINUOUS QUERY v KEYED BY (a) WITH (keys = 'b') AS SELECT 1")
                        .getMessage())
                .contains("PRV-2070")
                .contains("keyed by twice");
    }

    @Test
    void anOptionListThatIsNotOneIsRefusedWhereItGoesWrong() {
        assertThat(refusal("CREATE OR REPLACE CONTINUOUS QUERY v KEYED BY (a) WITH (backfill 'history') AS SELECT 1")
                        .getMessage())
                .contains("PRV-2070")
                .contains("'=' after the option 'backfill'");
        assertThat(refusal("CREATE OR REPLACE CONTINUOUS QUERY v KEYED BY (a) "
                                + "WITH (cutover = 'auto', cutover = 'manual') AS SELECT 1")
                        .getMessage())
                .contains("given twice");
    }

    /**
     * Seeded fuzz: any text following the head of a statement is either a statement or a coded
     * refusal -- never another exception, never ordinary SQL. Deterministic, so a failure reproduces.
     */
    @Test
    void anythingAfterTheHeadIsEitherAStatementOrACodedRefusal() {
        String[] heads = {"CREATE CONTINUOUS QUERY ", "DROP CONTINUOUS QUERY ", "PAUSE ", "RESUME ", "SHOW CONTINUOUS "
        };
        String[] pieces = {
            "v",
            "\"v\"",
            "KEYED",
            "BY",
            "(",
            ")",
            ",",
            "a",
            "AS",
            "SELECT",
            "1",
            "WRITING",
            "TO",
            "INTO",
            "RETAIN",
            "FOR",
            "FOREVER",
            "PT1H",
            "'P1D'",
            "INTERVAL",
            "'3'",
            "HOUR",
            "EMIT",
            "CHANGES",
            ";",
            "--",
            "/*",
            "*/",
            "'",
            "\"",
            "SERVE",
            "VIEW",
            "WITH",
            "INDEXED",
            "RANGE",
            "QUERY",
            "QUERIES",
            "\n",
            "金额",
            "$",
            "."
        };
        Random random = new Random(20260919L);
        for (int trial = 0; trial < 5_000; trial++) {
            StringBuilder sql = new StringBuilder(heads[random.nextInt(heads.length)]);
            int length = random.nextInt(14);
            for (int i = 0; i < length; i++) {
                sql.append(pieces[random.nextInt(pieces.length)]).append(random.nextBoolean() ? " " : "");
            }
            String text = sql.toString();
            try {
                Optional<ContinuousStatement> statement = ContinuousStatements.recognize(text);
                assertThat(statement).as("seed trial %d: %s", trial, text).isPresent();
            } catch (PravahaException e) {
                assertThat(e.errorCode())
                        .as("seed trial %d: %s", trial, text)
                        .isIn(SqlErrors.STATEMENT_MALFORMED, SqlErrors.CLAUSE_NOT_BUILT);
            }
        }
    }

    // ------------------------------------------------------------------ key names to ordinals

    private static final StreamSchema OUTPUT = StreamSchema.builder("out")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("total", Types.int64())
            .build();

    @Test
    void keyNamesBecomeOutputOrdinalsInTheOrderTheyAreNamed() {
        ContinuousStatement.Create create = create("CREATE CONTINUOUS QUERY v KEYED BY (total, user_id) AS SELECT 1");

        assertThat(create.keyOrdinals(OUTPUT)).containsExactly(2, 0);
    }

    @Test
    void aKeyNameDifferingOnlyInCaseMatchesTheOneColumnItCanMean() {
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (USER_ID) AS SELECT 1")
                        .keyOrdinals(OUTPUT))
                .containsExactly(0);
    }

    @Test
    void anUnknownKeyNameIsRefusedWithoutListingTheColumns() {
        ContinuousStatement.Create create = create("CREATE CONTINUOUS QUERY v KEYED BY (amount) AS SELECT 1");

        assertThatThrownBy(() -> create.keyOrdinals(OUTPUT))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.KEY_COLUMN_UNKNOWN))
                .hasMessageContaining("'amount'")
                .hasMessageContaining("AS total")
                .hasMessageNotContaining("region");
    }

    @Test
    void aKeyNamedTwiceOrAmbiguousIsRefused() {
        assertThatThrownBy(() -> create("CREATE CONTINUOUS QUERY v KEYED BY (user_id, USER_ID) AS SELECT 1")
                        .keyOrdinals(OUTPUT))
                .hasMessageContaining("PRV-2071")
                .hasMessageContaining("twice");

        StreamSchema twoCases = StreamSchema.builder("out")
                .field("Total", Types.int64())
                .field("total", Types.int64())
                .build();
        assertThatThrownBy(() -> create("CREATE CONTINUOUS QUERY v KEYED BY (TOTAL) AS SELECT 1")
                        .keyOrdinals(twoCases))
                .hasMessageContaining("PRV-2071")
                .hasMessageContaining("different cases");
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (\"total\") AS SELECT 1")
                        .keyOrdinals(twoCases))
                .as("the exact spelling wins over a case-insensitive one")
                .isEqualTo(List.of(1));
    }

    // ------------------------------------------------------------------ the ordered column's type

    @Test
    void anOrderableRangeColumnResolvesToItsOrdinal() {
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (user_id) RANGE (total) AS SELECT 1")
                        .rangeOrdinal(OUTPUT))
                .contains(2);
        assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (user_id) AS SELECT 1")
                        .rangeOrdinal(OUTPUT))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"region", "price", "ratio", "flag", "blob"})
    void aRangeOverATypeWithNoTotalOrderIsRefusedAtRegistrationAndSaysWhy(String column) {
        // Refused against the columns the view will actually have, not against the text, and with
        // the reason for that type rather than one sentence for all of them.
        StreamSchema output = StreamSchema.builder("out")
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("price", Types.decimal(18, 4))
                .field("ratio", Types.float64())
                .field("flag", Types.bool())
                .field("blob", Types.bytes())
                .build();

        assertThatThrownBy(() -> create(
                                "CREATE CONTINUOUS QUERY v KEYED BY (user_id) RANGE (" + column + ") " + "AS SELECT 1")
                        .rangeOrdinal(output))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.RANGE_NOT_ORDERED))
                .hasMessageContaining("PRV-2073")
                .hasMessageContaining("no total order")
                .hasMessageContaining("point reads and full-key lookups are unaffected");
    }

    @Test
    void aRangeOverAColumnTheQueryDoesNotProduceIsTheKeyRefusal() {
        assertThatThrownBy(() -> create("CREATE CONTINUOUS QUERY v KEYED BY (user_id) RANGE (nope) AS SELECT 1")
                        .rangeOrdinal(OUTPUT))
                .hasMessageContaining("PRV-2071");
    }

    @Test
    void everyTemporalAndIntegerWidthCanBeRanged() {
        StreamSchema widths = StreamSchema.builder("out")
                .field("a", Types.int8())
                .field("b", Types.int16())
                .field("c", Types.int32())
                .field("d", Types.int64())
                .field("e", Types.date())
                .field("f", Types.time())
                .field("g", Types.timestamp(9))
                .build();
        for (String column : List.of("a", "b", "c", "d", "e", "f", "g")) {
            assertThat(create("CREATE CONTINUOUS QUERY v KEYED BY (" + column + ") RANGE (" + column + ") "
                                    + "AS SELECT 1")
                            .rangeOrdinal(widths))
                    .as(column)
                    .isNotEmpty();
        }
    }
}

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
package com.ash.messaging.pravaha.registry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code WITH (...)} on a plain {@code CREATE CONTINUOUS QUERY} (B8).
 *
 * <p>The list was refused wholesale with {@code PRV-2072} while {@code CREATE OR REPLACE} accepted
 * the same syntax -- one grammar with two answers. What matters now is that accepting it did not
 * soften the judgement that refusal existed to make: every option is either built or refused by
 * name, and an option that is built has to have the same effect as the argument that already says
 * it. A {@code WITH (retention = ...)} registration that merely <em>recorded</em> a retention would
 * pass a test that only read the statement's answer back.
 */
class RegistrationOptionsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;
    private ContinuousQueryStatements statements;
    private SinkDeliveryTest.RecordingSinks sinks;

    @BeforeEach
    void setUp() {
        sinks = new SinkDeliveryTest.RecordingSinks();
        registry =
                new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN).writingTo(sinks);
        statements = new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE);
        sinks.bind("warehouse", com.ash.messaging.pravaha.api.plugin.SinkCapabilities.appendOnly());
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private ViewQuery.Result run(String sql) {
        return statements.execute(ContinuousStatements.recognize(sql).orElseThrow(), DANA);
    }

    private static PravahaException refusalOf(Runnable action) {
        try {
            action.run();
        } catch (PravahaException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    // ------------------------------------------------------------------ equivalence with what exists

    @Test
    void aRetentionOptionIsTheSameRetentionRetainForIs() {
        run("CREATE CONTINUOUS QUERY by_clause KEYED BY (user_id) RETAIN FOR PT24H "
                + "AS SELECT user_id, amount FROM txn");
        run("CREATE CONTINUOUS QUERY by_option KEYED BY (user_id) WITH (retention = 'PT24H') "
                + "AS SELECT user_id, amount FROM txn");

        assertThat(registry.require("by_option").view().retention())
                .isEqualTo(registry.require("by_clause").view().retention())
                .isEqualTo(Retention.ofAge(Duration.ofHours(24)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'24h'", "PT24H", "'PT24H'", "pt24h", "'1d'", "'1440m'", "'86400s'"})
    void everySpellingOfADayIsTheSameDay(String written) {
        // The short form is quoted, because `24h` is a number followed by a word to any lexer and
        // a grammar that guessed otherwise would guess wrong somewhere else. An ISO-8601 duration
        // is a single word and needs no quotes.
        run("CREATE CONTINUOUS QUERY spelled KEYED BY (user_id) WITH (retention = " + written + ") "
                + "AS SELECT user_id, amount FROM txn");

        assertThat(registry.require("spelled").view().retention())
                .as(written)
                .isEqualTo(Retention.ofAge(Duration.ofHours(24)));
    }

    @Test
    void forEverIsTheSameForEverRetainForeverIs() {
        run("CREATE CONTINUOUS QUERY never KEYED BY (user_id) WITH (retention = 'forever') "
                + "AS SELECT user_id, amount FROM txn");

        assertThat(registry.require("never").view().retention().isForever()).isTrue();
    }

    @Test
    void aSinkOptionIsTheSameSinkWritingToIs() {
        run("CREATE CONTINUOUS QUERY by_clause KEYED BY (user_id) WRITING TO warehouse "
                + "AS SELECT user_id, amount FROM txn");
        run("CREATE CONTINUOUS QUERY by_option KEYED BY (user_id) WITH (sink = 'warehouse') "
                + "AS SELECT user_id, amount FROM txn WHERE amount > 1");

        assertThat(registry.sinkOf("by_option"))
                .isEqualTo(registry.sinkOf("by_clause"))
                .contains("warehouse");
    }

    @Test
    void aSinkNamedByAnOptionIsInTheAnswerAsOneNamedByTheClauseIs() {
        ViewQuery.Result created = run("CREATE CONTINUOUS QUERY answered KEYED BY (user_id) "
                + "WITH (sink = 'warehouse') AS SELECT user_id, amount FROM txn");

        assertThat(created.rows().get(0)[3]).isEqualTo("warehouse");
    }

    @Test
    void aKeyFromAnOptionIsTheSameKeyKeyedByGives() {
        run("CREATE CONTINUOUS QUERY by_clause KEYED BY (user_id) AS SELECT amount, user_id FROM txn");
        run("CREATE CONTINUOUS QUERY by_option WITH (keys = 'user_id') AS SELECT amount, user_id FROM txn");

        assertThat(registry.require("by_option").view().keyOrdinals())
                .isEqualTo(registry.require("by_clause").view().keyOrdinals())
                .containsExactly(1);
    }

    @Test
    void aCompositeKeyFromAnOptionKeepsTheOrderItWasWrittenIn() {
        run("CREATE CONTINUOUS QUERY composite WITH (keys = 'amount, user_id') "
                + "AS SELECT user_id, amount FROM txn");

        assertThat(registry.require("composite").view().keyOrdinals()).containsExactly(1, 0);
    }

    // ------------------------------------------------------------------ refused by name

    @Test
    void anOptionThatDoesNotExistIsRefusedByNameWithTheOnesThatDo() {
        PravahaException refused = refusalOf(() -> run("CREATE CONTINUOUS QUERY v KEYED BY (user_id) "
                + "WITH ('consistency.default' = 'consistent') AS SELECT user_id, amount FROM txn"));

        assertThat(refused.errorCode()).isEqualTo(RegistryErrors.OPTION_UNKNOWN);
        assertThat(refused.getMessage())
                .contains("PRV-8017")
                .contains("'consistency.default'")
                .contains("refused rather than ignored")
                .contains("[retention, sink, keys, index, lane]");
        assertThat(registry.find("v"))
                .as("nothing was registered on the way to the refusal")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"backfill", "backfill.rate.limit", "cutover", "rollback.retention"})
    void aReplacementsOptionOnAPlainCreateIsRefusedWithTheStatementThatTakesIt(String option) {
        PravahaException refused = refusalOf(() -> run("CREATE CONTINUOUS QUERY v KEYED BY (user_id) " + "WITH ("
                + option + " = 'x') AS SELECT user_id, amount FROM txn"));

        assertThat(refused.errorCode()).isEqualTo(RegistryErrors.OPTION_UNKNOWN);
        assertThat(refused.getMessage()).contains("CREATE OR REPLACE");
    }

    @Test
    void aRegistrationsOptionOnAReplacementIsRefusedTheSameWayByTheOtherVocabulary() {
        run("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS SELECT user_id, amount FROM txn");

        PravahaException refused = refusalOf(() -> run("CREATE OR REPLACE CONTINUOUS QUERY spend "
                + "KEYED BY (user_id) WITH (retention = '24h') AS SELECT user_id, amount FROM txn"));

        assertThat(refused.getMessage()).contains("not an option this engine builds");
    }

    @Test
    void aRetentionSaidTwiceIsRefusedRatherThanDecidedByOrder() {
        PravahaException refused = refusalOf(() -> run("CREATE CONTINUOUS QUERY v KEYED BY (user_id) "
                + "RETAIN FOR PT1H WITH (retention = '24h') AS SELECT user_id, amount FROM txn"));

        assertThat(refused.errorCode()).isEqualTo(RegistryErrors.OPTION_UNKNOWN);
        assertThat(refused.getMessage()).contains("says its retention twice");
    }

    @Test
    void twoDifferentSinksAreRefusedAndTheSameOneIsNot() {
        assertThat(refusalOf(() -> run("CREATE CONTINUOUS QUERY v KEYED BY (user_id) WRITING TO warehouse "
                                + "WITH (sink = 'other') AS SELECT user_id, amount FROM txn"))
                        .getMessage())
                .contains("names two different sinks");

        run("CREATE CONTINUOUS QUERY agreed KEYED BY (user_id) WRITING TO warehouse "
                + "WITH (sink = 'warehouse') AS SELECT user_id, amount FROM txn");
        assertThat(registry.sinkOf("agreed")).contains("warehouse");
    }

    @Test
    void aRetentionThatIsNotALengthOfTimeIsRefusedWithTheFormsThatAre() {
        assertThat(refusalOf(() -> run("CREATE CONTINUOUS QUERY v KEYED BY (user_id) "
                                + "WITH (retention = 'a while') AS SELECT user_id, amount FROM txn"))
                        .getMessage())
                .contains("PRV-8017")
                .contains("not a length of event time")
                .contains("24h");
        assertThat(refusalOf(() -> run("CREATE CONTINUOUS QUERY v KEYED BY (user_id) "
                                + "WITH (retention = 'PT0S') AS SELECT user_id, amount FROM txn"))
                        .getMessage())
                .contains("must be a positive age");
    }

    // ------------------------------------------------------------------ RANGE at registration

    @Test
    void aRangeOverTheKeysLastColumnRegistersAndTheKeyCarriesIt() {
        run("CREATE CONTINUOUS QUERY windows INDEXED BY (user_id) RANGE (amount) "
                + "AS SELECT user_id, amount FROM txn");

        assertThat(registry.require("windows").view().keyOrdinals())
                .as("the design's INDEXED BY (...) RANGE (...) is a key of both columns")
                .containsExactly(0, 1);
    }

    @Test
    void aRangeOverAColumnThisEngineCannotOrderIsRefusedAtRegistration() {
        PravahaException refused = refusalOf(() -> run("CREATE CONTINUOUS QUERY texty INDEXED BY (amount) "
                + "RANGE (user_id) AS SELECT amount, user_id FROM txn"));

        assertThat(refused.errorCode()).isEqualTo(SqlErrors.RANGE_NOT_ORDERED);
        assertThat(refused.getMessage()).contains("PRV-2073").contains("collation");
        assertThat(registry.find("texty"))
                .as("refused before anything was registered, not after")
                .isEmpty();
    }

    // ------------------------------------------------------------------ the option vocabulary itself

    @Test
    void theOptionReaderIsTotal() {
        assertThat(RegistrationOptions.defaults().retention()).isEmpty();
        assertThat(RegistrationOptions.of(Map.of())).isEqualTo(RegistrationOptions.defaults());
        assertThat(RegistrationOptions.of(Map.of("key", "a")).keyColumns()).containsExactly("a");
        assertThatThrownBy(() -> RegistrationOptions.of(Map.of("keys", " , ")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8017");
        assertThatThrownBy(() -> RegistrationOptions.of(Map.of("sink", "  ")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("cannot be empty");
        assertThat(RegistrationOptions.KNOWN).containsExactly("retention", "sink", "keys", "index", "lane");
    }

    @Test
    void laneIsDedicatedOrSharedAndNothingElse() {
        assertThat(RegistrationOptions.defaults().dedicatedLane()).isFalse();
        assertThat(RegistrationOptions.of(Map.of("lane", "dedicated")).dedicatedLane())
                .isTrue();
        assertThat(RegistrationOptions.of(Map.of("lane", " Dedicated ")).dedicatedLane())
                .isTrue();
        assertThat(RegistrationOptions.of(Map.of("lane", "shared")).dedicatedLane())
                .isFalse();
        assertThatThrownBy(() -> RegistrationOptions.of(Map.of("lane", "own")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8017")
                .hasMessageContaining("'dedicated'")
                .hasMessageContaining("'shared'");
        assertThatThrownBy(() -> RegistrationOptions.of(Map.of("parallelism", "4")))
                .hasMessageContaining("lane");
    }

    @Test
    void theStatementWithNoOptionsIsUntouched() {
        run("CREATE CONTINUOUS QUERY plain KEYED BY (user_id) AS SELECT user_id, amount FROM txn");

        assertThat(registry.require("plain").view().retention()).isEqualTo(registry.defaultRetention());
        assertThat(registry.sinkOf("plain")).isEmpty();
        assertThat(List.copyOf(registry.require("plain").view().keyOrdinals())).containsExactly(0);
    }
}

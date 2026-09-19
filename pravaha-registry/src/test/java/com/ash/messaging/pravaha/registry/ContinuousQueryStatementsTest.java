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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The continuous-query statements against a real registry: a {@code CREATE} lands exactly where the
 * registration argument would, keys named by column become the ordinals the view is keyed by, and
 * the sink and retention clauses reach the registry's own overloads.
 */
class ContinuousQueryStatementsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema PAYROLL = StreamSchema.builder("payroll")
            .field("employee", Types.string())
            .field("salary", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal GUEST = new Principal("guest", "acme", Set.of(), Map.of());
    private static final Principal NOBODY = new Principal("nobody", "acme", Set.of(), Map.of());

    /** guest may not read payroll, nor administer anything; nobody may not register. */
    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            if (principal.id().equals("guest") && (view.equals("payroll") || view.equals("salaries"))) {
                return AccessDecision.deny("guest may not read payroll");
            }
            return AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return principal.id().equals("nobody")
                    ? AccessDecision.deny("nobody may not register a query")
                    : AccessDecision.allow();
        }

        @Override
        public AccessDecision mayAdminister(Principal principal, String view) {
            return principal.id().equals("guest")
                    ? AccessDecision.deny("guest administers nothing")
                    : AccessDecision.allow();
        }
    };

    private SinkDeliveryTest.RecordingSinks sinks;
    private AuditSink.InMemory audit;
    private QueryRegistry registry;
    private ContinuousQueryStatements statements;

    @BeforeEach
    void setUp() {
        sinks = new SinkDeliveryTest.RecordingSinks();
        audit = new AuditSink.InMemory();
        registry = new QueryRegistry(new ViewCatalog(), POLICY, audit, TXN, PAYROLL).writingTo(sinks);
        statements = new ContinuousQueryStatements(registry, POLICY, audit);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private ViewQuery.Result run(String sql, Principal principal) {
        ContinuousStatement statement = ContinuousStatements.recognize(sql).orElseThrow();
        return statements.execute(statement, principal);
    }

    @Test
    void keyedByNamesBecomeTheOutputOrdinalsTheViewIsKeyedBy() {
        ViewQuery.Result created = run(
                "CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS " + "SELECT amount AS total, user_id FROM txn",
                DANA);

        RegisteredQuery query = registry.require("spend");
        assertThat(query.view().keyOrdinals())
                .as("user_id is the SECOND output column, so the key is ordinal 1 -- not the first column")
                .containsExactly(1);
        assertThat(created.schema()).isEqualTo(ContinuousQueryStatements.CREATED);
        assertThat(created.rows())
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsExactly("spend", "RUNNING", query.fingerprint().shortForm(), null));
    }

    @Test
    void theSqlSpellingIsTheSameComputationAsTheArgumentSpelling() {
        String select = "SELECT user_id, amount AS total FROM txn WHERE amount > 10";
        RegisteredQuery byArgument = registry.register("by_argument", select, List.of(0), DANA);

        run("create continuous query by_sql keyed by (USER_ID) as " + select + " EMIT CHANGES;", DANA);

        assertThat(registry.require("by_sql").fingerprint())
                .as("one question, one computation, whichever way it was asked")
                .isEqualTo(byArgument.fingerprint());
        assertThat(registry.require("by_sql").sql()).isEqualTo(select);
    }

    @Test
    void aSinkAndARetentionReachTheRegistry() {
        sinks.bind("orders", SinkCapabilities.appendOnly());

        ViewQuery.Result created = run(
                "CREATE CONTINUOUS QUERY big_txn KEYED BY (user_id) WRITING TO orders RETAIN FOR INTERVAL '2' HOUR "
                        + "AS SELECT user_id, amount FROM txn WHERE amount > 100",
                DANA);

        assertThat(registry.sinkOf("big_txn")).contains("orders");
        assertThat(registry.require("big_txn").view().retention()).isEqualTo(Retention.ofAge(Duration.ofHours(2)));
        assertThat(created.rows().get(0)[3]).isEqualTo("orders");
    }

    @Test
    void retainForeverAndNoRetentionAreTheirOwnAnswers() {
        run("CREATE CONTINUOUS QUERY kept KEYED BY (user_id) RETAIN FOREVER AS SELECT user_id, amount FROM txn", DANA);
        run("CREATE CONTINUOUS QUERY defaulted KEYED BY (amount) AS SELECT amount, user_id FROM txn", DANA);

        assertThat(registry.require("kept").view().retention().isForever()).isTrue();
        assertThat(registry.require("defaulted").view().retention()).isEqualTo(registry.defaultRetention());
    }

    @Test
    void aKeyTheQueryDoesNotProduceIsRefusedAndNothingIsRegistered() {
        assertThatThrownBy(() -> run(
                        "CREATE CONTINUOUS QUERY spend KEYED BY (amount) AS "
                                + "SELECT user_id, amount AS total FROM txn",
                        DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.KEY_COLUMN_UNKNOWN));
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aReservedWordNameIsRefusedByTheRegistrysOwnNameRule() {
        assertThatThrownBy(() ->
                        run("CREATE CONTINUOUS QUERY \"select\" KEYED BY (user_id) AS SELECT user_id FROM txn", DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.NAME_UNUSABLE));
    }

    @Test
    void pauseResumeAndDropAnswerWithTheStateTheQueryIsNowIn() {
        run("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS SELECT user_id, amount FROM txn", DANA);

        assertThat(run("PAUSE CONTINUOUS QUERY spend", DANA).rows().get(0)).containsExactly("spend", "PAUSED");
        assertThat(registry.require("spend").state()).isEqualTo(QueryState.PAUSED);
        assertThat(run("RESUME CONTINUOUS QUERY spend", DANA).rows().get(0)).containsExactly("spend", "RUNNING");
        assertThat(run("DROP CONTINUOUS QUERY spend;", DANA).rows().get(0)).containsExactly("spend", "DROPPED");
        assertThat(registry.names()).isEmpty();
        assertThatThrownBy(() -> run("DROP CONTINUOUS QUERY spend", DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.NO_SUCH_QUERY));
    }

    @Test
    void showListsWhatQueryListingShowsThisPrincipalAndNothingMore() {
        run("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS SELECT user_id, amount FROM txn", DANA);
        run("CREATE CONTINUOUS QUERY salaries KEYED BY (employee) AS SELECT employee, salary FROM payroll", DANA);
        run("CREATE CONTINUOUS QUERY staff KEYED BY (employee) RETAIN FOR P7D AS SELECT employee FROM payroll", DANA);

        ViewQuery.Result forDana = run("SHOW CONTINUOUS QUERIES", DANA);
        assertThat(forDana.schema()).isEqualTo(ContinuousQueryStatements.LISTING);
        assertThat(forDana.rows()).extracting(row -> row[0]).containsExactly("spend", "salaries", "staff");
        assertThat(forDana.rows().get(2))
                .containsExactly(
                        "staff",
                        "RUNNING",
                        "SELECT employee FROM payroll",
                        registry.require("staff").fingerprint().shortForm(),
                        0L,
                        "employee",
                        null,
                        "PT168H");

        // guest is denied 'salaries' by name and 'staff' by what it reads: the same two rules
        // QueryListing applies to pravaha.list and GET /api/v1/queries.
        List<String> forGuest = run("SHOW CONTINUOUS QUERIES", GUEST).rows().stream()
                .map(row -> (String) row[0])
                .toList();
        assertThat(forGuest)
                .containsExactlyElementsOf(new QueryListing(registry, POLICY, AuditSink.NONE)
                        .list(GUEST, "list").stream()
                                .map(QueryListing.Entry::name)
                                .toList())
                .containsExactly("spend");
    }

    @Test
    void aPrincipalWhoMayNotRegisterIsRefusedExactlyAsTheRegistryRefusesTheArgumentForm() {
        PravahaException byArgument =
                catching(() -> registry.register("spend", "SELECT user_id, amount FROM txn", List.of(0), NOBODY));
        PravahaException bySql = catching(() ->
                run("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS SELECT user_id, amount FROM txn", NOBODY));

        assertThat(bySql.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
        assertThat(bySql.getMessage()).isEqualTo(byArgument.getMessage());
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aPrincipalWhoMayNotAdministerCannotDropPauseOrResumeAndEachRefusalIsAudited() {
        run("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS SELECT user_id, amount FROM txn", DANA);

        for (String verb : List.of("DROP", "PAUSE", "RESUME")) {
            PravahaException refused = catching(() -> run(verb + " CONTINUOUS QUERY spend", GUEST));
            assertThat(refused.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
            assertThat(refused.getMessage())
                    .contains("guest may not " + verb.toLowerCase(java.util.Locale.ROOT) + " 'spend'");
        }
        assertThat(registry.require("spend").state()).isEqualTo(QueryState.RUNNING);
        assertThat(audit.forPrincipal("guest"))
                .filteredOn(event -> List.of("drop", "pause", "resume").contains(event.action()))
                .hasSize(3)
                .allSatisfy(event -> assertThat(event.allowed()).isFalse());
    }

    @Test
    void aPrincipalWhoMayNotReadTheSourceIsRefusedAsARegistrationIs() {
        PravahaException refused = catching(() ->
                run("CREATE CONTINUOUS QUERY leak KEYED BY (employee) AS SELECT employee, salary FROM payroll", GUEST));

        assertThat(refused.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
        assertThat(refused.getMessage()).contains("standing read");
    }

    private static PravahaException catching(Runnable action) {
        try {
            action.run();
        } catch (PravahaException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }
}

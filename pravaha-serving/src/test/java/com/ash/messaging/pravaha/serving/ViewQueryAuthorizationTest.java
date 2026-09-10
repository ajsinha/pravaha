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
package com.ash.messaging.pravaha.serving;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authorization on the read path (ADR-031).
 *
 * <p>These tests exist because the alternative -- pushing the rules down to whatever store the data
 * came from -- cannot work here. A served view is derived: it is the answer to a continuous query
 * that the store has never seen, over a change feed that is read once and shared by every query
 * registered against it. There is no row in Aerospike corresponding to "gold-tier total for u4"
 * whose permissions could be consulted. The rule has to be enforced where the row is made.
 */
class ViewQueryAuthorizationTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final Principal ANALYST = new Principal("dana", "acme", Set.of("analyst"), Map.of("tier", "gold"));
    private static final Principal INTERN = new Principal("sam", "acme", Set.of("intern"), Map.of());

    private ViewCatalog catalog;
    private AuditSink.InMemory audit;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        catalog = new ViewCatalog().register(view);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 100);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 100);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 100);
        view.applyValues(new Object[] {"u4", "gold", 1200L}, 1, 100);
        view.commit(100);
        audit = new AuditSink.InMemory();
    }

    private ViewQuery queryWith(SecurityPolicy policy) {
        return new ViewQuery(catalog, policy, audit);
    }

    @Test
    void aPrincipalWithoutAccessIsRefused() {
        ViewQuery queries = queryWith((principal, view) ->
                principal.hasRole("analyst") ? AccessDecision.allow() : AccessDecision.deny("not an analyst"));

        assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", INTERN))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("sam");
    }

    @Test
    void aRowFilterIsAppliedWhetherOrNotTheQueryMentionsItsColumn() {
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("tier = 'gold'"));

        // The filter names a column the SELECT list does not. A filter that only worked when the
        // caller happened to project the column would be a filter the caller could switch off.
        ViewQuery.Result result = queries.execute("SELECT user_id FROM user_volume", ANALYST);

        assertThat(result.rows().stream().map(row -> (String) row[0]).sorted().toList())
                .containsExactly("u1", "u4");
    }

    @Test
    void aRowFilterCannotBeUndoneByTheCallersOwnWhereClause() {
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("tier = 'gold'"));

        // The classic escape when a filter is concatenated into SQL text: end the caller's predicate
        // in something that makes the appended one redundant. Against a filter placed in the plan
        // there is no syntax to reach it with.
        ViewQuery.Result result =
                queries.execute("SELECT user_id FROM user_volume WHERE total > 0 OR total <= 0", ANALYST);

        assertThat(result.rows().stream().map(row -> (String) row[0]).sorted().toList())
                .containsExactly("u1", "u4");
    }

    @Test
    void aRowFilterSurvivesAnAggregate() {
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("tier = 'gold'"));

        ViewQuery.Result result = queries.execute("SELECT SUM(total) FROM user_volume", ANALYST);

        // 300 + 1200, not 1557: the filter is below the aggregate, so the rows this principal may
        // not see never reach the sum. Filtering the *answer* afterwards could not have done this.
        assertThat((Long) result.rows().get(0)[0]).isEqualTo(1500L);
    }

    @Test
    void aFilterNamingAColumnTheViewDoesNotHaveIsRefusedRatherThanDropped() {
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("region = 'emea'"));

        // The soundness rule. There is no region column here, so no filter applied at read time can
        // separate the regions -- and silently serving the unfiltered rows is exactly the leak the
        // policy exists to stop.
        assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", ANALYST))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7003")
                .hasMessageContaining("aggregated away");
    }

    @Test
    void everyDecisionIsRecorded() {
        ViewQuery queries = queryWith((principal, view) ->
                principal.hasRole("analyst") ? AccessDecision.allow() : AccessDecision.deny("not an analyst"));

        queries.execute("SELECT user_id FROM user_volume", ANALYST);
        assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", INTERN))
                .isInstanceOf(PravahaException.class);

        assertThat(audit.events()).hasSize(2);
        assertThat(audit.forPrincipal("dana")).singleElement().satisfies(event -> {
            assertThat(event.allowed()).isTrue();
            assertThat(event.target()).isEqualTo("user_volume");
        });
        assertThat(audit.denials())
                .singleElement()
                .satisfies(event -> assertThat(event.principal().id()).isEqualTo("sam"));
    }

    @Test
    void theSchemaIsAsAuthorizedAsTheRows() {
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.deniedWithoutDetail());

        // A schema is the list of columns an organisation keeps about its customers. Answering
        // "what would this return" for someone who may not run it hands them that list for free.
        assertThatThrownBy(() -> queries.schemaOf("SELECT * FROM user_volume", INTERN))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
    }

    @Test
    void anEmbeddedEngineWithNoPolicyStillAnswers() {
        // The single-argument constructor is the embedded case: a process that has already
        // authenticated its caller and does not want a second set of rules to keep in step.
        assertThat(new ViewQuery(catalog)
                        .execute("SELECT user_id FROM user_volume")
                        .size())
                .isEqualTo(4);
    }
}

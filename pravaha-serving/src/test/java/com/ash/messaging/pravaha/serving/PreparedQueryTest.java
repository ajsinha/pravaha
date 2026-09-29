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
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Preparing once and binding many times, over a maintained view (ADR-032). */
class PreparedQueryTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final Principal ANALYST = new Principal("dana", "public", Set.of("analyst"), Map.of("tier", "gold"));

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

    private ViewQuery queries(SecurityPolicy policy) {
        return new ViewQuery(catalog, policy, audit);
    }

    private ViewQuery queries() {
        return queries(SecurityPolicy.PERMISSIVE);
    }

    @Test
    void oneStatementAnswersDifferentQuestions() {
        ViewQuery query = queries();
        ViewQuery.Prepared statement = query.prepare("SELECT total FROM user_volume WHERE user_id = ?", ANALYST);

        assertThat((Long) query.execute(statement, BoundParameters.of("u1"), ANALYST)
                        .rows()
                        .get(0)[0])
                .isEqualTo(300L);
        assertThat((Long) query.execute(statement, BoundParameters.of("u4"), ANALYST)
                        .rows()
                        .get(0)[0])
                .isEqualTo(1200L);
    }

    @Test
    void theShapeOfTheAnswerIsKnownBeforeAnyValueIsBound() {
        ViewQuery.Prepared statement =
                queries().prepare("SELECT user_id, total FROM user_volume WHERE total > ?", ANALYST);

        // Which is what lets a client show a grid's columns while the user is still typing the
        // value, and what Flight SQL's getFlightInfo has to answer before any rows exist.
        assertThat(statement.resultSchema().fields().stream().map(f -> f.name()).toList())
                .containsExactly("user_id", "total");
        assertThat(statement.parameters().count()).isEqualTo(1);
        assertThat(statement.parameters().typeOf(0)).isEqualTo(TypeName.INT64);
    }

    @Test
    void aValueThatLooksLikeSqlIsAValue() {
        ViewQuery query = queries();
        ViewQuery.Prepared statement = query.prepare("SELECT user_id FROM user_volume WHERE user_id = ?", ANALYST);

        // The classic injection string. It is not escaped here -- it is never parsed, because
        // binding happens after planning, when there is no parser left to reach. The result is a
        // search for a user with that literal name, which is what the query says.
        ViewQuery.Result result = query.execute(statement, BoundParameters.of("u1' OR '1'='1"), ANALYST);

        assertThat(result.size()).isZero();
    }

    @Test
    void bindingTheWrongNumberOfValuesIsRefused() {
        ViewQuery query = queries();
        ViewQuery.Prepared statement =
                query.prepare("SELECT user_id FROM user_volume WHERE user_id = ? AND total > ?", ANALYST);

        assertThatThrownBy(() -> query.execute(statement, BoundParameters.of("u1"), ANALYST))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2061");
    }

    @Test
    void aSecurityFilterStillAppliesToAPreparedStatement() {
        ViewQuery query = queries((principal, view) -> AccessDecision.allowWithRowFilter("tier = 'gold'"));
        ViewQuery.Prepared statement = query.prepare("SELECT user_id FROM user_volume WHERE total > ?", ANALYST);

        ViewQuery.Result result = query.execute(statement, BoundParameters.of(10L), ANALYST);

        // u2 is silver and passes the bound filter; it must still not come back. A prepared
        // statement is a plan, and the policy is applied to the plan built for each execution.
        assertThat(result.rows().stream().map(row -> (String) row[0]).sorted().toList())
                .containsExactly("u1", "u4");
    }

    @Test
    void accessTakenAwayAfterPreparingStopsTheNextExecution() {
        boolean[] allowed = {true};
        ViewQuery query = queries(
                (principal, view) -> allowed[0] ? AccessDecision.allow() : AccessDecision.deny("access was revoked"));
        ViewQuery.Prepared statement = query.prepare("SELECT user_id FROM user_volume WHERE total > ?", ANALYST);

        // Three, not four: u3's total is 7 and the bound floor is 10.
        assertThat(query.execute(statement, BoundParameters.of(10L), ANALYST).size())
                .isEqualTo(3);

        allowed[0] = false;

        // A handle is a plan, never a permission. Freezing the decision at prepare time would mean
        // a statement prepared this morning still serving rows this afternoon.
        assertThatThrownBy(() -> query.execute(statement, BoundParameters.of(10L), ANALYST))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
    }

    @Test
    void preparingIsAuthorizedTooSoASchemaIsNotFree() {
        ViewQuery query = queries((principal, view) -> AccessDecision.deniedWithoutDetail());

        assertThatThrownBy(() -> query.prepare("SELECT user_id FROM user_volume WHERE total > ?", ANALYST))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
    }

    @Test
    void everyExecutionIsAudited() {
        ViewQuery query = queries();
        ViewQuery.Prepared statement = query.prepare("SELECT user_id FROM user_volume WHERE user_id = ?", ANALYST);
        query.execute(statement, BoundParameters.of("u1"), ANALYST);
        query.execute(statement, BoundParameters.of("u4"), ANALYST);

        // Three: the prepare and both executions. A statement prepared once and run a thousand
        // times must not appear in the audit log once.
        assertThat(audit.forPrincipal("dana")).hasSize(3);
    }

    @Test
    void aStatementWithNoPlaceholdersStillWorks() {
        ViewQuery query = queries();
        ViewQuery.Prepared statement = query.prepare("SELECT user_id FROM user_volume", ANALYST);

        assertThat(query.execute(statement, BoundParameters.none(), ANALYST).size())
                .isEqualTo(4);
    }
}

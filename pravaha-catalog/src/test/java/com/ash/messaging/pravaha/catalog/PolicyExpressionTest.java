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
package com.ash.messaging.pravaha.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a policy's expression may say, and how it is bound to a principal (ADR-059 §4). */
class PolicyExpressionTest {

    private static final Principal ANA = new Principal("ana", "acme", Set.of("eu"), Map.of("region", "EU"));

    private static void refused(String expression, @Nullable String column, String because) {
        assertThatThrownBy(() -> PolicyExpression.of(expression, column))
                .isInstanceOfSatisfying(PravahaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_INVALID);
                    assertThat(e.getMessage()).contains(because);
                });
    }

    @Test
    void sessionFunctionsBindToThePrincipal() {
        PolicyExpression filter = PolicyExpression.of(
                "region = session_attribute('region') AND (owner = current_user() OR is_member('eu'))", null);
        assertThat(filter.readsSession()).isTrue();
        assertThat(filter.boundTo(ANA)).isEqualTo("region = 'EU' AND ( owner = 'ana' OR TRUE )");
        assertThat(filter.probe()).isEqualTo("region = '0' AND ( owner = '' OR FALSE )");
        assertThat(filter.canonical())
                .isEqualTo(
                        "region = session_attribute ( 'region' ) AND ( owner = current_user ( ) OR is_member ( 'eu' ) )");
        assertThat(PolicyExpression.of("amount > 100", null).readsSession()).isFalse();
    }

    @Test
    void subqueriesNonDeterministicAndUnknownFunctionsAreRefusedByName() {
        refused("region IN (SELECT region FROM allowed)", null, "subquery");
        refused("EXISTS (SELECT 1)", null, "subquery");
        refused("RAND() < 0.5", null, "does not answer the same way twice");
        refused("ts > CURRENT_TIMESTAMP", null, "does not answer the same way twice");
        refused("send_home(card) = 1", null, "not a function a policy may call");
        refused("region = 'EU'; DROP", null, "';' ends a statement");
        refused("region = ?", null, "no parameters");
        refused("(region = 'EU'", null, "not closed");
        refused("region = 'EU')", null, "closes nothing");
        refused("region = 'EU", null, "not closed");
        refused("", null, "needs an expression");
        refused("region = current_user", null, "current_user()");
        refused("region = session_attribute(region)", null, "one quoted name");
        refused("region = session_attribute('')", null, "between the quotes");
        refused("region # 1", null, "no meaning");
        refused("x".repeat(PolicyExpression.MAX_LENGTH + 1), null, "at most");
    }

    @Test
    void aMaskNamesOnlyItsOwnColumn() {
        PolicyExpression mask = PolicyExpression.of(
                "CASE WHEN is_member('ops') THEN card ELSE 'XXXX-' || SUBSTRING(card FROM 13 FOR 4) END", "card");
        assertThat(mask.boundTo(ANA)).startsWith("CASE WHEN FALSE THEN card");
        PolicyExpression.of("CAST(NULL AS VARCHAR(20))", "card");
        PolicyExpression.of("\"card\"", "card");
        refused("card || region", "card", "may name only that column");
        refused("\"region\"", "card", "may name only that column");
    }

    @Test
    void aMissingClaimIsCodedAndAQuoteInAClaimStaysInItsLiteral() {
        PolicyExpression filter = PolicyExpression.of("region = session_attribute('region')", null);
        assertThatThrownBy(() -> filter.boundTo(Principal.of("bob")))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_CLAIM_MISSING));
        Principal sly = new Principal("o'hara", "acme", Set.of(), Map.of("region", "x' OR 'a'='a"));
        assertThat(filter.boundTo(sly)).isEqualTo("region = 'x'' OR ''a''=''a'");
        assertThat(PolicyExpression.of("owner = current_user()", null).boundTo(sly))
                .isEqualTo("owner = 'o''hara'");
        assertThat(PolicyExpression.of("-- note\nname = \"x\"\"y\" AND 1.5 >= amount", null)
                        .canonical())
                .isEqualTo("name = \"x\"\"y\" AND 1.5 >= amount");
    }

    @Test
    void theStatementsParse() {
        assertThat(CatalogStatements.parse(
                        "CREATE ROW FILTER sales.region_scope AS region = session_attribute('region') "
                                + "EXCEPT ROLE finance_admin, auditors;"))
                .isEqualTo(new CatalogStatement.CreatePolicy(
                        PolicyDefinition.Type.ROW_FILTER,
                        List.of("sales", "region_scope"),
                        "",
                        "region = session_attribute('region')",
                        List.of("finance_admin", "auditors")));
        assertThat(CatalogStatements.parse(
                        "create mask card_last4 on column card_number as 'XXXX-' || RIGHT(card_number, 4)"))
                .isEqualTo(new CatalogStatement.CreatePolicy(
                        PolicyDefinition.Type.MASK,
                        List.of("card_last4"),
                        "card_number",
                        "'XXXX-' || RIGHT(card_number, 4)",
                        List.of()));
        assertThat(CatalogStatements.parse("ALTER STREAM orders SET POLICY sales.region_scope"))
                .isEqualTo(new CatalogStatement.SetPolicy(
                        new CatalogStatement.Target("STREAM", List.of("orders")), List.of("sales", "region_scope")));
        assertThat(CatalogStatements.parse("ALTER QUERY revenue UNSET POLICY f"))
                .isEqualTo(new CatalogStatement.UnsetPolicy(
                        new CatalogStatement.Target("VIEW", List.of("revenue")), List.of("f")));
        assertThat(CatalogStatements.parse("ALTER TAG 'domain=payments' SET POLICY card_last4"))
                .isEqualTo(new CatalogStatement.SetPolicy(
                        new CatalogStatement.Target("TAG", List.of("domain=payments")), List.of("card_last4")));
        assertThat(CatalogStatements.parse("DROP ROW FILTER IF EXISTS acme.sales.f"))
                .isEqualTo(new CatalogStatement.DropPolicy(
                        PolicyDefinition.Type.ROW_FILTER, List.of("acme", "sales", "f"), true));
        assertThat(CatalogStatements.parse("DROP MASK m"))
                .isEqualTo(new CatalogStatement.DropPolicy(PolicyDefinition.Type.MASK, List.of("m"), false));
        assertThat(CatalogStatements.parse("SHOW POLICIES"))
                .isEqualTo(new CatalogStatement.ShowPolicies(Optional.empty()));
        assertThat(CatalogStatements.parse("SHOW POLICIES ON VIEW revenue"))
                .isEqualTo(new CatalogStatement.ShowPolicies(
                        Optional.of(new CatalogStatement.Target("VIEW", List.of("revenue")))));
        assertThat(CatalogStatements.parse("CREATE ROW FILTER f AS (a = 1) AND b IN (1, 2) EXCEPT ROLE r"))
                .extracting(s -> ((CatalogStatement.CreatePolicy) s).expression())
                .isEqualTo("(a = 1) AND b IN (1, 2)");
        assertThat(CatalogStatements.parse("COMMENT ON POLICY f IS 'x'"))
                .isEqualTo(new CatalogStatement.Comment(
                        new CatalogStatement.Target("POLICY", List.of("f")), Optional.of("x")));

        assertThat(CatalogStatements.recognizes("CREATE ROW FILTER x AS y")).isTrue();
        assertThat(CatalogStatements.recognizes("CREATE ROW x")).isFalse();
        assertThat(CatalogStatements.recognizes("DROP CONTINUOUS QUERY q")).isFalse();
        assertThat(CatalogStatements.recognizes("DROP MASK m")).isTrue();
        assertThat(CatalogStatements.recognizes("ALTER TAG 'pii' SET POLICY m")).isTrue();

        for (String malformed : List.of(
                "CREATE ROW FILTER f AS",
                "CREATE ROW FILTER f region = 1",
                "CREATE MASK m AS 'x'",
                "CREATE ROW FILTER f AS a = 1 EXCEPT r",
                "ALTER TAG 'pii' OWNER TO ROLE r",
                "ALTER TAG 'pii' SET TAGS ('x')")) {
            assertThatThrownBy(() -> CatalogStatements.parse(malformed))
                    .as(malformed)
                    .isInstanceOf(CatalogStatements.Malformed.class);
        }
    }
}

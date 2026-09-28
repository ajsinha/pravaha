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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The statements of ADR-059 §3, read as written there. */
class CatalogStatementsTest {

    @Test
    void theAdrsExamplesParse() {
        assertThat(CatalogStatements.parse("CREATE NAMESPACE sales COMMENT 'Order-to-cash';"))
                .isEqualTo(new CatalogStatement.CreateNamespace(List.of("sales"), false, Optional.of("Order-to-cash")));
        assertThat(CatalogStatements.parse("GRANT USE, CREATE ON NAMESPACE sales TO ROLE sales_eng"))
                .isEqualTo(new CatalogStatement.GrantPrivileges(
                        Set.of(Privilege.USE, Privilege.CREATE),
                        new CatalogStatement.Target("NAMESPACE", List.of("sales")),
                        Grantee.role("sales_eng")));
        assertThat(CatalogStatements.parse("GRANT SELECT, SUBSCRIBE ON VIEW sales.hourly_revenue TO ROLE analyst"))
                .isInstanceOf(CatalogStatement.GrantPrivileges.class);
        assertThat(CatalogStatements.parse("REVOKE SUBSCRIBE ON VIEW sales.hourly_revenue FROM ROLE analyst"))
                .isEqualTo(new CatalogStatement.RevokePrivileges(
                        Set.of(Privilege.SUBSCRIBE),
                        new CatalogStatement.Target("VIEW", List.of("sales", "hourly_revenue")),
                        Grantee.role("analyst")));
        assertThat(CatalogStatements.parse(
                        "ALTER VIEW sales.hourly_revenue SET TAGS ('domain' = 'finance', 'certified')"))
                .isEqualTo(new CatalogStatement.SetTags(
                        new CatalogStatement.Target("VIEW", List.of("sales", "hourly_revenue")),
                        Map.of("domain", "finance", "certified", "")));
        assertThat(CatalogStatements.parse("COMMENT ON VIEW sales.hourly_revenue IS 'Revenue per region'"))
                .isEqualTo(new CatalogStatement.Comment(
                        new CatalogStatement.Target("VIEW", List.of("sales", "hourly_revenue")),
                        Optional.of("Revenue per region")));
        assertThat(CatalogStatements.parse("ALTER VIEW sales.hourly_revenue OWNER TO ROLE finance_data"))
                .isEqualTo(new CatalogStatement.SetOwner(
                        new CatalogStatement.Target("VIEW", List.of("sales", "hourly_revenue")),
                        Grantee.role("finance_data")));
        assertThat(CatalogStatements.parse("SHOW GRANTS ON VIEW sales.hourly_revenue"))
                .isInstanceOf(CatalogStatement.ShowGrantsOn.class);
        assertThat(CatalogStatements.parse("SHOW GRANTS TO ROLE analyst"))
                .isEqualTo(new CatalogStatement.ShowGrantsTo(Grantee.role("analyst")));
        assertThat(CatalogStatements.parse(
                        "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW sales.hourly_revenue  -- which grant"))
                .isEqualTo(new CatalogStatement.ShowEffectiveAccess(
                        "ana", new CatalogStatement.Target("VIEW", List.of("sales", "hourly_revenue"))));
    }

    @Test
    void queryNamesTheSameObjectAsViewAndAllMeansEveryPrivilege() {
        CatalogStatement.GrantPrivileges all = (CatalogStatement.GrantPrivileges)
                CatalogStatements.parse("grant all privileges on query q to user ana");
        assertThat(all.privileges()).isEmpty();
        assertThat(all.target()).isEqualTo(new CatalogStatement.Target("VIEW", List.of("q")));
        assertThat(all.grantee()).isEqualTo(Grantee.user("ana"));
    }

    @Test
    void buildOnMayBeWrittenEitherWay() {
        assertThat(((CatalogStatement.GrantPrivileges)
                                CatalogStatements.parse("GRANT BUILD_ON ON STREAM orders TO ROLE a"))
                        .privileges())
                .containsExactly(Privilege.BUILD_ON);
        assertThat(((CatalogStatement.GrantPrivileges)
                                CatalogStatements.parse("GRANT BUILD ON ON STREAM orders TO ROLE a"))
                        .privileges())
                .containsExactly(Privilege.BUILD_ON);
    }

    @Test
    void theRestOfTheGrammar() {
        assertThat(CatalogStatements.parse("SHOW NAMESPACES")).isInstanceOf(CatalogStatement.ShowNamespaces.class);
        assertThat(CatalogStatements.parse("CREATE NAMESPACE IF NOT EXISTS acme.risk"))
                .isEqualTo(new CatalogStatement.CreateNamespace(List.of("acme", "risk"), true, Optional.empty()));
        assertThat(CatalogStatements.parse("ALTER VIEW revenue SET NAMESPACE sales"))
                .isEqualTo(new CatalogStatement.SetNamespace(
                        new CatalogStatement.Target("VIEW", List.of("revenue")), List.of("sales")));
        assertThat(CatalogStatements.parse("ALTER STREAM orders UNSET TAGS ('pii')"))
                .isEqualTo(new CatalogStatement.UnsetTags(
                        new CatalogStatement.Target("STREAM", List.of("orders")), List.of("pii")));
        assertThat(CatalogStatements.parse("GRANT MANAGE ON CATALOG TO ROLE auditors"))
                .isEqualTo(new CatalogStatement.GrantPrivileges(
                        Set.of(Privilege.MANAGE),
                        new CatalogStatement.Target("CATALOG", List.of()),
                        Grantee.role("auditors")));
        assertThat(CatalogStatements.parse("COMMENT ON TENANT acme IS NULL"))
                .isEqualTo(new CatalogStatement.Comment(
                        new CatalogStatement.Target("TENANT", List.of("acme")), Optional.empty()));
        assertThat(CatalogStatements.parse("GRANT SELECT ON VIEW \"odd name\" TO USER \"o'brien\""))
                .isEqualTo(new CatalogStatement.GrantPrivileges(
                        Set.of(Privilege.SELECT),
                        new CatalogStatement.Target("VIEW", List.of("odd name")),
                        Grantee.user("o'brien")));
    }

    @Test
    void recognitionIsByTheLeadingWordsOnly() {
        assertThat(CatalogStatements.recognizes("GRANT nonsense")).isTrue();
        assertThat(CatalogStatements.recognizes("alter view x owner to role y")).isTrue();
        assertThat(CatalogStatements.recognizes("SELECT * FROM grants")).isFalse();
        assertThat(CatalogStatements.recognizes("SHOW CONTINUOUS QUERIES")).isFalse();
        assertThat(CatalogStatements.recognizes("CREATE CONTINUOUS QUERY x")).isFalse();
        assertThat(CatalogStatements.recognizes("ALTER SESSION SET x = 1")).isFalse();
        assertThat(CatalogStatements.recognizes("COMMENT something")).isFalse();
        assertThat(CatalogStatements.recognizes(null)).isFalse();
    }

    @Test
    void aStatementThatStartsRightAndGoesWrongIsRefusedWithItsShape() {
        assertThatThrownBy(() -> CatalogStatements.parse("GRANT SELECT ON VIEW x"))
                .isInstanceOf(CatalogStatements.Malformed.class)
                .hasMessageContaining("ends early")
                .hasMessageContaining("GRANT <privilege>");
        assertThatThrownBy(() -> CatalogStatements.parse("GRANT FLY ON VIEW x TO ROLE r"))
                .isInstanceOf(CatalogStatements.Malformed.class)
                .hasMessageContaining("not a privilege");
        assertThatThrownBy(() -> CatalogStatements.parse("GRANT SELECT ON TABLE x TO ROLE r"))
                .isInstanceOf(CatalogStatements.Malformed.class)
                .hasMessageContaining("CATALOG, TENANT, NAMESPACE");
        assertThatThrownBy(() -> CatalogStatements.parse("GRANT SELECT ON VIEW a.b.c.d TO ROLE r"))
                .isInstanceOf(CatalogStatements.Malformed.class)
                .hasMessageContaining("4 parts");
        assertThatThrownBy(() -> CatalogStatements.parse("SHOW GRANTS ON VIEW x extra"))
                .isInstanceOf(CatalogStatements.Malformed.class)
                .hasMessageContaining("follows a complete statement");
        assertThatThrownBy(() -> CatalogStatements.parse("GRANT SELECT ON VIEW x TO GROUP g"))
                .isInstanceOf(CatalogStatements.Malformed.class)
                .hasMessageContaining("ROLE <name> or USER <name>");
    }
}

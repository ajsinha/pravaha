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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.ANA;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.BOB;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.CLOCK;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.EVE;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.OPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The statements, run: who may change what, what SHOW answers, and that every change is audited. */
class CatalogServiceTest {

    private AuditSink.InMemory audit;
    private CatalogService service;
    private CatalogStatementExecutor sql;

    @BeforeEach
    void setUp() {
        audit = new AuditSink.InMemory();
        Catalog catalog = Catalog.inMemory(CLOCK);
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(
                ObjectKind.STREAM, List.of("orders"), ObjectKind.SINK, List.of("audit_out")));
        service = new CatalogService(catalog, audit);
        service.resolvingUsersWith(id -> switch (id) {
            case "ana" -> Optional.of(ANA);
            case "bob" -> Optional.of(BOB);
            default -> Optional.empty();
        });
        sql = new CatalogStatementExecutor(service);
        catalog.registerView("acme.default.revenue", OPS);
    }

    private CatalogStatementExecutor.Answer run(Principal who, String statement) {
        return sql.execute(CatalogStatements.parse(statement), who);
    }

    @Test
    void anAdministratorCreatesANamespaceMovesAViewInAndGrants() {
        run(OPS, "CREATE NAMESPACE sales COMMENT 'Order-to-cash'");
        run(OPS, "ALTER VIEW revenue SET NAMESPACE sales");
        CatalogStatementExecutor.Answer granted = run(OPS, "GRANT USE ON NAMESPACE sales TO ROLE analyst");
        assertThat(granted.rows())
                .containsExactly(List.of("acme.sales", "NAMESPACE", "GRANTED", "USE to ROLE analyst"));
        run(OPS, "GRANT SELECT, SUBSCRIBE ON VIEW sales.revenue TO ROLE analyst");

        CatalogStatementExecutor.Answer grants = run(OPS, "SHOW GRANTS ON VIEW sales.revenue");
        assertThat(grants.columns()).isEqualTo(CatalogStatementExecutor.GRANTS);
        assertThat(grants.rows()).extracting(r -> r.get(1)).containsExactly("SELECT", "SUBSCRIBE");

        CatalogStatementExecutor.Answer why = run(OPS, "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW sales.revenue");
        Map<String, List<String>> byPrivilege = new java.util.HashMap<>();
        why.rows().forEach(r -> byPrivilege.put(r.get(2), r));
        assertThat(byPrivilege.get("SELECT").get(3)).isEqualTo("true");
        assertThat(byPrivilege.get("SELECT").get(4)).isEqualTo("grant SELECT on acme.sales.revenue to ROLE analyst");
        assertThat(byPrivilege.get("MANAGE").get(3)).isEqualTo("false");

        run(OPS, "REVOKE SUBSCRIBE ON VIEW sales.revenue FROM ROLE analyst");
        assertThat(service.access()
                        .check(ANA, Privilege.SUBSCRIBE, "acme.sales.revenue")
                        .allowed())
                .isFalse();
        assertThat(service.access()
                        .check(ANA, Privilege.SELECT, "acme.sales.revenue")
                        .allowed())
                .isTrue();
    }

    @Test
    void changingAnObjectNeedsManageAndSayingSoIsAudited() {
        run(OPS, "GRANT SELECT ON VIEW revenue TO USER ana");
        assertThatThrownBy(() -> run(ANA, "GRANT SELECT ON VIEW revenue TO USER bob"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7033")
                .hasMessageContaining("needs MANAGE");
        assertThatThrownBy(() -> run(ANA, "COMMENT ON VIEW revenue IS 'mine now'"))
                .hasMessageContaining("PRV-7033");
        List<AuditEvent> events = audit.events();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("catalog.grant");
            assertThat(e.allowed()).isFalse();
            assertThat(e.principal().id()).isEqualTo("ana");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("catalog.grant");
            assertThat(e.allowed()).isTrue();
            assertThat(e.principal().id()).isEqualTo("ops");
        });
    }

    @Test
    void aNameTheCallerMayNotSeeIsAnsweredAsOneThatDoesNotExist() {
        // In the tenant's default namespace, which every member may use, it is visible; moved into a
        // namespace bob may not use, it is not.
        assertThat(run(BOB, "SHOW GRANTS ON VIEW revenue").rows()).isEmpty();
        run(OPS, "CREATE NAMESPACE sales");
        run(OPS, "ALTER VIEW revenue SET NAMESPACE sales");
        String hidden = "";
        try {
            run(BOB, "SHOW GRANTS ON VIEW revenue");
        } catch (PravahaException e) {
            hidden = e.getMessage();
        }
        String absent = "";
        try {
            run(BOB, "SHOW GRANTS ON VIEW no_such_view");
        } catch (PravahaException e) {
            absent = e.getMessage();
        }
        assertThat(hidden).contains("PRV-7031");
        assertThat(hidden.replace("revenue", "X")).isEqualTo(absent.replace("no_such_view", "X"));
    }

    @Test
    void privilegesThatMeanNothingOnAKindAreRefusedAndOwnIsNotGranted() {
        assertThatThrownBy(() -> run(OPS, "GRANT WRITE ON VIEW revenue TO ROLE a"))
                .hasMessageContaining("PRV-7032");
        assertThatThrownBy(() -> run(OPS, "GRANT OWN ON VIEW revenue TO ROLE a"))
                .hasMessageContaining("PRV-7037");
        run(OPS, "GRANT WRITE ON SINK audit_out TO ROLE loaders");
        assertThat(service.catalog().grantsOn("node.sinks.audit_out")).hasSize(1);
        assertThatThrownBy(() -> run(OPS, "GRANT WRITE ON SINK no_such_sink TO ROLE loaders"))
                .hasMessageContaining("PRV-7031");
    }

    @Test
    void theOwnerMayGiveItAwayAndThenCannotTakeItBack() {
        Principal owner = new Principal("olga", "acme", Set.of(), Map.of());
        service.catalog().registerView("acme.default.ledger", owner);
        run(owner, "ALTER VIEW ledger SET TAGS ('domain' = 'finance', 'certified')");
        run(owner, "COMMENT ON VIEW ledger IS 'The ledger'");
        run(owner, "ALTER VIEW ledger OWNER TO USER ana");
        CatalogObject ledger = service.catalog().object("acme.default.ledger").orElseThrow();
        assertThat(ledger.owner()).isEqualTo(Grantee.user("ana"));
        assertThat(ledger.tags()).containsEntry("domain", "finance").containsEntry("certified", "");
        assertThat(ledger.version()).isEqualTo(4);
        assertThatThrownBy(() -> run(owner, "ALTER VIEW ledger OWNER TO USER olga"))
                .hasMessageContaining("PRV-7033");
    }

    @Test
    void onlyAnAdministratorCreatesANamespaceByDefaultAndAnExistingOneIsRefused() {
        assertThatThrownBy(() -> run(ANA, "CREATE NAMESPACE mine")).hasMessageContaining("PRV-7033");
        run(OPS, "CREATE NAMESPACE sales");
        assertThatThrownBy(() -> run(OPS, "CREATE NAMESPACE sales")).hasMessageContaining("PRV-7036");
        run(OPS, "CREATE NAMESPACE IF NOT EXISTS sales");
        run(OPS, "GRANT CREATE ON TENANT acme TO USER ana");
        run(ANA, "CREATE NAMESPACE mine");
        assertThat(service.catalog().object("acme.mine").orElseThrow().owner()).isEqualTo(Grantee.user("ana"));
    }

    @Test
    void searchAndListingShowOnlyWhatTheCallerMayUse() {
        run(OPS, "CREATE NAMESPACE sales");
        run(OPS, "ALTER VIEW revenue SET NAMESPACE sales");
        run(OPS, "COMMENT ON VIEW sales.revenue IS 'Revenue per region'");
        assertThat(service.search(ANA, "region")).isEmpty();
        run(OPS, "GRANT USE ON NAMESPACE sales TO ROLE analyst");
        assertThat(service.search(ANA, "region"))
                .extracting(CatalogObject::fullName)
                .containsExactly("acme.sales.revenue");
        assertThat(service.search(EVE, "region")).isEmpty();
        assertThat(service.namespaces(ANA)).extracting(CatalogObject::fullName).contains("acme.sales", "acme.default");
        assertThat(service.namespaces(BOB)).extracting(CatalogObject::fullName).doesNotContain("acme.sales");
    }

    @Test
    void effectiveAccessIsForOneselfOrForAManager() {
        CatalogService.EffectiveAccess mine = service.effectiveAccess(
                ANA, "ana", service.resolve(ANA, new CatalogStatement.Target("NAMESPACE", List.of("default"))));
        assertThat(mine.lines()).anySatisfy(l -> {
            assertThat(l.privilege()).isEqualTo(Privilege.USE);
            assertThat(l.allowed()).isTrue();
        });
        run(OPS, "GRANT SELECT ON VIEW revenue TO USER bob");
        assertThatThrownBy(() -> run(ANA, "SHOW EFFECTIVE ACCESS FOR USER bob ON VIEW revenue"))
                .hasMessageContaining("PRV-7033");
        assertThatThrownBy(() -> run(OPS, "SHOW EFFECTIVE ACCESS FOR USER nobody ON VIEW revenue"))
                .hasMessageContaining("PRV-7037");
    }

    @Test
    void grantsToARoleAreShownToItsHoldersAndToManagers() {
        run(OPS, "GRANT SELECT ON VIEW revenue TO ROLE analyst");
        assertThat(run(ANA, "SHOW GRANTS TO ROLE analyst").rows()).hasSize(1);
        assertThat(run(BOB, "SHOW GRANTS TO ROLE analyst").rows()).isEmpty();
        assertThat(run(OPS, "SHOW GRANTS TO ROLE analyst").rows()).hasSize(1);
    }
}

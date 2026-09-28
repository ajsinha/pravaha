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

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;

import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.ANA;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.BOB;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.CLOCK;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.OPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Row filters and masks as catalogue objects (ADR-059 §4): what they narrow, for whom, and who may say so. */
class PolicyServiceTest {

    @TempDir
    Path directory;

    private static final Principal EU_ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of("region", "EU"));
    private static final Principal US_BOB = new Principal("bob", "acme", Set.of("analyst"), Map.of("region", "US"));
    private static final Principal FINANCE =
            new Principal("fay", "acme", Set.of("analyst", "finance_admin"), Map.of("region", "EU"));

    private AuditSink.InMemory audit;
    private Catalog catalog;
    private CatalogService service;
    private CatalogStatementExecutor sql;

    @BeforeEach
    void setUp() {
        audit = new AuditSink.InMemory();
        catalog = Catalog.open(directory.resolve("catalog.journal"), CLOCK);
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(ObjectKind.STREAM, List.of("orders")));
        service = new CatalogService(catalog, audit);
        service.resolvingUsersWith(id -> switch (id) {
            case "ana" -> Optional.of(EU_ANA);
            case "fay" -> Optional.of(FINANCE);
            default -> Optional.empty();
        });
        sql = new CatalogStatementExecutor(service);
        catalog.registerView("revenue", OPS);
    }

    private CatalogStatementExecutor.Answer run(Principal who, String statement) {
        return sql.execute(CatalogStatements.parse(statement), who);
    }

    private Narrowing narrowing(Principal who, String view) {
        return service.policies().narrowing(who, "acme.default." + view);
    }

    @Test
    void aRowFilterNarrowsEveryoneButItsExceptRolesBoundToTheirOwnClaim() {
        CatalogStatementExecutor.Answer created = run(
                OPS,
                "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE finance_admin");
        assertThat(created.rows().get(0))
                .containsExactly(
                        "acme.default.region_scope",
                        "POLICY",
                        "CREATED",
                        "ROW FILTER; bind it with ALTER STREAM|VIEW <name> SET POLICY acme.default.region_scope");
        assertThat(narrowing(EU_ANA, "revenue").isNone())
                .as("nothing narrows until bound")
                .isTrue();

        run(OPS, "ALTER VIEW revenue SET POLICY region_scope");

        assertThat(narrowing(EU_ANA, "revenue").rowFilter()).contains("region = 'EU'");
        assertThat(narrowing(US_BOB, "revenue").rowFilter()).contains("region = 'US'");
        assertThat(narrowing(FINANCE, "revenue").isNone()).isTrue();
        assertThat(narrowing(EU_ANA, "revenue").enforcesSameAs(narrowing(US_BOB, "revenue")))
                .as("two principals with different claims never enforce the same thing")
                .isFalse();
    }

    @Test
    void severalFiltersOnOneObjectAndTogether() {
        run(OPS, "CREATE ROW FILTER region_scope AS region = session_attribute('region')");
        run(OPS, "CREATE ROW FILTER big_only AS amount > 100 OR is_member('auditor')");
        run(OPS, "ALTER VIEW revenue SET POLICY region_scope");
        run(OPS, "ALTER VIEW revenue SET POLICY big_only");

        assertThat(narrowing(EU_ANA, "revenue").rowFilter()).contains("(amount > 100 OR FALSE) AND (region = 'EU')");
    }

    @Test
    void aMaskReplacesOneColumnAndTwoMasksOnOneColumnAreRefused() {
        run(OPS, "CREATE MASK card_last4 ON COLUMN card AS 'XXXX-' || RIGHT(card, 4) EXCEPT ROLE payments_ops");
        run(OPS, "ALTER VIEW revenue SET POLICY card_last4");
        assertThat(narrowing(EU_ANA, "revenue").masks()).isEqualTo(Map.of("card", "'XXXX-' || RIGHT ( card , 4 )"));
        assertThat(narrowing(EU_ANA, "revenue").rowFilter()).isEmpty();

        run(OPS, "CREATE MASK card_hidden ON COLUMN card AS CAST(NULL AS VARCHAR)");
        assertThatThrownBy(() -> run(OPS, "ALTER VIEW revenue SET POLICY card_hidden"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_CONFLICT));

        // By tag the second one gets past binding -- and is refused where it would apply.
        run(OPS, "ALTER VIEW revenue SET TAGS ('pii')");
        run(OPS, "ALTER TAG 'pii' SET POLICY card_hidden");
        assertThatThrownBy(() -> narrowing(EU_ANA, "revenue"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_CONFLICT));
    }

    @Test
    void aTagBoundPolicyReachesAnObjectTaggedLaterButNotAnotherTenants() {
        run(OPS, "CREATE MASK email_hidden ON COLUMN email AS 'hidden'");
        run(OPS, "ALTER TAG 'domain=customers' SET POLICY email_hidden");

        catalog.registerView("signups", OPS);
        assertThat(narrowing(EU_ANA, "signups").isNone()).isTrue();
        run(OPS, "ALTER VIEW signups SET TAGS ('domain' = 'customers')");
        assertThat(narrowing(EU_ANA, "signups").masks()).containsEntry("email", "'hidden'");

        // Another value of the key does not match; another tenant's object carrying the tag is not reached.
        run(OPS, "ALTER VIEW revenue SET TAGS ('domain' = 'finance')");
        assertThat(narrowing(EU_ANA, "revenue").isNone()).isTrue();
        Principal eve = new Principal("eve", "globex", Set.of("admin"), Map.of());
        catalog.registerView("leads", eve);
        catalog.setTags("globex.default.leads", Map.of("domain", "customers"), "eve");
        assertThat(service.policies().narrowing(eve, "globex.default.leads").isNone())
                .isTrue();

        run(OPS, "ALTER TAG 'domain=customers' UNSET POLICY email_hidden");
        assertThat(narrowing(EU_ANA, "signups").isNone()).isTrue();
    }

    @Test
    void aMissingClaimIsRefusedNotGuessed() {
        run(OPS, "CREATE ROW FILTER region_scope AS region = session_attribute('region')");
        run(OPS, "ALTER VIEW revenue SET POLICY region_scope");
        assertThatThrownBy(() -> narrowing(BOB, "revenue")).isInstanceOfSatisfying(PravahaException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_CLAIM_MISSING);
            assertThat(e.getMessage()).contains("'region'");
        });
    }

    @Test
    void aClaimCannotEndItsLiteral() {
        run(OPS, "CREATE ROW FILTER region_scope AS region = session_attribute('region')");
        run(OPS, "ALTER VIEW revenue SET POLICY region_scope");
        Principal sly = new Principal("sly", "acme", Set.of(), Map.of("region", "EU' OR '1'='1"));
        assertThat(narrowing(sly, "revenue").rowFilter()).contains("region = 'EU'' OR ''1''=''1'");
    }

    @Test
    void creatingNeedsCreateOnTheNamespaceAndBindingNeedsManageOnTheTarget() {
        assertThatThrownBy(() -> run(BOB, "CREATE ROW FILTER sales.f AS region = 'EU'"))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.NO_SUCH_OBJECT));
        run(OPS, "CREATE NAMESPACE sales");
        assertThatThrownBy(() -> run(BOB, "CREATE ROW FILTER sales.f AS region = 'EU'"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.MANAGE_REQUIRED));

        // Ana may create in her default namespace when granted CREATE there, and owns what she makes...
        run(OPS, "GRANT CREATE ON NAMESPACE default TO USER ana");
        run(ANA, "CREATE ROW FILTER mine AS region = 'EU'");
        // ...but binding it to a view she does not manage is refused, and so is a tag binding.
        assertThatThrownBy(() -> run(ANA, "ALTER VIEW revenue SET POLICY mine"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.MANAGE_REQUIRED));
        assertThatThrownBy(() -> run(ANA, "ALTER TAG 'pii' SET POLICY mine"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.MANAGE_REQUIRED));
        run(OPS, "GRANT MANAGE ON VIEW revenue TO USER ana");
        run(ANA, "ALTER VIEW revenue SET POLICY mine");
        assertThat(narrowing(BOB, "revenue").rowFilter()).contains("region = 'EU'");
        assertThat(audit.events()).anyMatch(e -> e.action().equals("catalog.policy.bind"));
    }

    @Test
    void onlyStreamsAndViewsAreNarrowedAndTheCheckerSeesEveryDirectBinding() {
        AtomicInteger checked = new AtomicInteger();
        service.policies().checkingWith((policy, target) -> {
            checked.incrementAndGet();
            if (policy.expression().contains("missing")) {
                throw new PravahaException(CatalogErrors.POLICY_INVALID, "no column missing");
            }
        });
        run(OPS, "CREATE ROW FILTER f AS missing = 1");
        assertThatThrownBy(() -> run(OPS, "ALTER VIEW revenue SET POLICY f"))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_INVALID));
        assertThat(catalog.bindings()).isEmpty();
        run(OPS, "CREATE ROW FILTER g AS region = 'EU'");
        run(OPS, "ALTER STREAM orders SET POLICY g");
        assertThat(checked.get()).isEqualTo(2);
        assertThat(service.policies().narrowing(OPS, "node.streams.orders").rowFilter())
                .contains("region = 'EU'");
        assertThatThrownBy(() -> run(OPS, "ALTER NAMESPACE default SET POLICY g"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.INVALID_REQUEST));
    }

    @Test
    void aBoundPolicyIsNotDroppedAndADroppedObjectTakesItsBindings() {
        run(OPS, "CREATE ROW FILTER f AS region = 'EU'");
        run(OPS, "ALTER VIEW revenue SET POLICY f");
        assertThatThrownBy(() -> run(OPS, "DROP ROW FILTER f"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_CONFLICT));
        assertThatThrownBy(() -> run(OPS, "DROP MASK f"))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(CatalogErrors.NO_SUCH_OBJECT));

        catalog.dropView("revenue");
        assertThat(catalog.bindings()).isEmpty();
        assertThat(run(OPS, "DROP ROW FILTER f").rows().get(0).get(2)).isEqualTo("DROPPED");
        assertThat(run(OPS, "DROP ROW FILTER IF EXISTS f").rows().get(0).get(2)).isEqualTo("NOT_FOUND");
        assertThat(catalog.object("acme.default.f")).isEmpty();
    }

    @Test
    void showPoliciesAndEffectiveAccessSayWhatAppliesAndWhy() {
        run(OPS, "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE finance_admin");
        run(OPS, "CREATE MASK card_last4 ON COLUMN card AS 'XXXX'");
        run(OPS, "ALTER VIEW revenue SET POLICY region_scope");
        run(OPS, "ALTER VIEW revenue SET TAGS ('pii')");
        run(OPS, "ALTER TAG 'pii' SET POLICY card_last4");

        CatalogStatementExecutor.Answer all = run(OPS, "SHOW POLICIES");
        assertThat(all.columns()).isEqualTo(CatalogStatementExecutor.POLICIES);
        assertThat(all.rows())
                .extracting(r -> r.get(0) + " -> " + r.get(5))
                .containsExactly(
                        "acme.default.region_scope -> acme.default.revenue", "acme.default.card_last4 -> TAG 'pii'");
        assertThat(run(OPS, "SHOW POLICIES ON VIEW revenue").rows()).hasSize(2);

        CatalogStatementExecutor.Answer ana = run(OPS, "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW revenue");
        assertThat(ana.rows())
                .filteredOn(r -> r.get(2).startsWith("ROW FILTER"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.get(3)).isEqualTo("true");
                    assertThat(r.get(4)).isEqualTo("region = 'EU' (bound to acme.default.revenue)");
                });
        assertThat(ana.rows())
                .filteredOn(r -> r.get(2).startsWith("MASK"))
                .singleElement()
                .satisfies(r -> assertThat(r.get(4)).contains("bound to TAG 'pii'"));
        CatalogStatementExecutor.Answer fay = run(OPS, "SHOW EFFECTIVE ACCESS FOR USER fay ON VIEW revenue");
        assertThat(fay.rows())
                .filteredOn(r -> r.get(2).startsWith("ROW FILTER"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.get(3)).isEqualTo("false");
                    assertThat(r.get(4)).startsWith("exempt: holds the role finance_admin");
                });
    }

    @Test
    void policiesAndBindingsSurviveARestartAndACompaction() {
        run(OPS, "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE finance_admin");
        run(OPS, "CREATE MASK card_last4 ON COLUMN card AS 'XXXX'");
        run(OPS, "CREATE ROW FILTER gone AS 1 = 0");
        run(OPS, "ALTER VIEW revenue SET POLICY region_scope");
        run(OPS, "ALTER TAG 'pii' SET POLICY card_last4");
        run(OPS, "ALTER TAG 'pii' SET POLICY gone");
        run(OPS, "ALTER TAG 'pii' UNSET POLICY gone");
        run(OPS, "DROP ROW FILTER gone");
        run(OPS, "ALTER VIEW revenue SET TAGS ('pii')");
        Narrowing before = narrowing(EU_ANA, "revenue");

        Catalog second = Catalog.open(directory.resolve("catalog.journal"), CLOCK);
        CatalogService again = new CatalogService(second, AuditSink.NONE);
        assertThat(second.policies())
                .extracting(PolicyDefinition::fullName)
                .containsExactly("acme.default.region_scope", "acme.default.card_last4");
        assertThat(second.policy("acme.default.region_scope").orElseThrow().exceptRoles())
                .containsExactly("finance_admin");
        assertThat(second.bindings()).hasSize(2);
        assertThat(again.policies().narrowing(EU_ANA, "acme.default.revenue").enforcesSameAs(before))
                .isTrue();
        assertThat(second.object("acme.default.region_scope").orElseThrow().kind())
                .isEqualTo(ObjectKind.POLICY);

        second.compact();
        Catalog third = Catalog.open(directory.resolve("catalog.journal"), CLOCK);
        assertThat(third.bindings()).hasSize(2);
        assertThat(third.policies()).hasSize(2);
    }

    @Test
    void aMoveTakesDirectBindingsWithTheView() {
        run(OPS, "CREATE ROW FILTER f AS region = 'EU'");
        run(OPS, "ALTER VIEW revenue SET POLICY f");
        run(OPS, "CREATE NAMESPACE sales");
        run(OPS, "ALTER VIEW revenue SET NAMESPACE sales");
        assertThat(service.policies().narrowing(BOB, "acme.sales.revenue").rowFilter())
                .contains("region = 'EU'");
    }

    @Test
    void aPolicyIsAnObjectWithAnOwnerTagsAndAComment() {
        run(OPS, "GRANT CREATE ON NAMESPACE default TO USER ana");
        run(ANA, "CREATE ROW FILTER mine AS region = 'EU'");
        run(ANA, "COMMENT ON POLICY mine IS 'EU only'");
        run(ANA, "ALTER POLICY mine SET TAGS ('owner' = 'ana')");
        CatalogObject mine = catalog.object("acme.default.mine").orElseThrow();
        assertThat(mine.owner()).isEqualTo(Grantee.user("ana"));
        assertThat(mine.description()).isEqualTo("EU only");
        assertThat(mine.version()).isEqualTo(3);
        assertThatThrownBy(() -> run(BOB, "COMMENT ON POLICY mine IS 'mine now'"))
                .isInstanceOf(PravahaException.class);
        assertThat(service.policies().show(ANA, "mine").definition().expression())
                .isEqualTo("region = 'EU'");
    }
}

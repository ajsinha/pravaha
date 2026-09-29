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

    private static final Principal ANALYST = new Principal("dana", "public", Set.of("analyst"), Map.of("tier", "gold"));
    private static final Principal INTERN = new Principal("sam", "public", Set.of("intern"), Map.of());

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

    /**
     * SX-13. Two principals whose row filters are byte-identical share one computation by
     * fingerprint (ADR-025), so the shared view carries the <em>first</em> registration's name and
     * the second principal reads it under an alias registered for them.
     *
     * <p>A filtered read under that alias threw {@code PRV-7003} wrapping {@code PRV-2002 Object
     * 'bob2_sales' not found}, while the identical entitlement under the primary name returned its
     * rows. {@code withRowFilter} planned a throwaway {@code SELECT * FROM <alias> WHERE <filter>}
     * against a one-entry catalogue holding the view's own schema, which is named after the
     * primary registration — so the alias was a table that statement's catalogue did not contain.
     * It failed closed, which is why this is not a disclosure; what it broke is the promise
     * {@code docs/SECURITY.md} makes that sharing is invisible to the reader.
     */
    @Test
    void sx13_aFilteredReadUnderAnAliasOfASharedViewReturnsTheSameRowsAsUnderItsPrimaryName() {
        catalog.registerAs("bob2_sales", catalog.find("user_volume").orElseThrow());
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("tier = 'gold'"));

        ViewQuery.Result underThePrimaryName = queries.execute("SELECT user_id FROM user_volume", ANALYST);
        ViewQuery.Result underTheAlias = queries.execute("SELECT user_id FROM bob2_sales", ANALYST);

        assertThat(underTheAlias.rows().stream()
                        .map(row -> (String) row[0])
                        .sorted()
                        .toList())
                .as("the alias answers what the primary name answers, filtered the same way")
                .containsExactly("u1", "u4")
                .isEqualTo(underThePrimaryName.rows().stream()
                        .map(row -> (String) row[0])
                        .sorted()
                        .toList());
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
    void aFilterThatLeavesNoPredicateIsRefusedRatherThanServedUnrestricted() {
        // SECX-15, and the sharpest failure this campaign found. Calcite folds a predicate it can
        // decide at plan time, so a filter of TRUE leaves no FilterOperator anywhere in the plan --
        // and the injection walked the tree, found nothing, and returned the plan untouched. The
        // read ran with no restriction while the audit recorded "allowed with a row filter": a
        // false record of enforcement, which is worse than no record at all.
        //
        // Confirmed live before the fix: a principal entitled to two of four rows, with his filter
        // text set to TRUE, received all four.
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("TRUE"));

        assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", ANALYST))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7003")
                .hasMessageContaining("true for every row");
    }

    @Test
    void aFilterThatRestrictsNothingIsRefusedEvenWhenItSurvivesPlanning() {
        // TAUTOFILTER-1. This test used to assert the opposite: `1 = 1` is not folded away, so it
        // survived as a predicate, was "applied", and excluded nothing -- and the audit recorded an
        // entitlement restricted by a filter. The refusal now judges the predicate that runs, not
        // whether one survived: true for every row, or dropping only rows with a NULL in a compared
        // column (tier is nullable), is refused the way TRUE is.
        for (String vacuous : List.of("1 = 1", "user_id = user_id", "1 = 1 OR tier = 'gold'", "tier = tier")) {
            ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter(vacuous));
            assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", ANALYST))
                    .as(vacuous)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-7003")
                    .hasMessageContaining("TAUTOFILTER-1");
        }
        // A real restriction that happens to match every row present today is applied, not refused:
        // what is judged is the filter, never the data.
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allowWithRowFilter("total >= 0"));
        assertThat(queries.execute("SELECT user_id FROM user_volume", ANALYST).rows())
                .as("a surviving restriction is applied even when it matches every row")
                .isNotEmpty();
    }

    @Test
    void aPolicyThatMeansEverythingSaysSoWithAllowRatherThanATautology() {
        // Refusing a folded tautology is only defensible because there is a correct way to say the
        // same thing, and it works.
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allow());

        assertThat(queries.execute("SELECT user_id FROM user_volume", ANALYST).rows())
                .as("an unrestricted allow reads the whole view")
                .isNotEmpty();
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

    @Test
    void sx5_aDeniedCallerCannotTellARealViewFromAnAbsentOneByTheCode() {
        // SX-5's remaining channel, and why the first fix did not close it. Authorization moved
        // ahead of catalog.find, but planning happens before both -- and the planner resolves the
        // name against the schema while validating, so an absent view was refused there with
        // PRV-2002 before the policy was ever consulted, while a real-but-forbidden one reached the
        // policy and got PRV-7002. Two codes is an oracle: a caller entitled to nothing confirms a
        // name by reading which refusal comes back.
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.deny("interns read nothing"));

        Throwable real = org.assertj.core.api.Assertions.catchThrowable(
                () -> queries.execute("SELECT user_id FROM user_volume", INTERN));
        Throwable absent = org.assertj.core.api.Assertions.catchThrowable(
                () -> queries.execute("SELECT user_id FROM zzz_no_such_view", INTERN));

        assertThat(real)
                .as("a denied caller must be refused the view that exists")
                .isNotNull();
        assertThat(absent).as("and refused the one that does not").isNotNull();
        assertThat(codeIn(absent))
                .as(
                        "the two refusals must carry the same code. Real gave '%s', absent gave '%s'",
                        real.getMessage(), absent.getMessage())
                .isEqualTo(codeIn(real));
        assertThat(absent.getMessage())
                .as("and the refusal for an absent name must not disclose a view the caller may not read")
                .doesNotContain("user_volume");
    }

    @Test
    void sx5_anAuthorizedCallerStillGetsAStraightAnswerAboutATypo() {
        // The other half, and the reason this is not simply "refuse everything identically". Closing
        // an oracle must not cost a legitimate user their diagnosis: somebody allowed to read the
        // view has nothing disclosed to them by being told the name they typed is not there.
        ViewQuery queries = queryWith((principal, view) -> AccessDecision.allow());

        assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volumme", ANALYST))
                .as("a typo by someone entitled to the data should say the object was not found")
                .isInstanceOf(PravahaException.class)
                // L-3: the serving layer's own code, not the planner's. It used to be PRV-2002,
                // which sends a reader asking for a view to the SQL documentation -- and is the
                // code a read racing a drop/re-register got, where PRV-4023 is the one a retry
                // loop can act on.
                .hasMessageContaining("PRV-4023")
                .hasMessageContaining("user_volumme");
    }

    /** The {@code PRV-} code a refusal carries -- the part a caller can branch on. */
    private static String codeIn(Throwable refusal) {
        java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("PRV-\\d{4}").matcher(String.valueOf(refusal.getMessage()));
        return found.find() ? found.group() : "no code";
    }
}

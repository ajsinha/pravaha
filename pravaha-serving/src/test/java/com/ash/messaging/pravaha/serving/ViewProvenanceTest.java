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
 * A read is judged by the data behind a view, not by the name it was registered under.
 *
 * <p>SX-11, reproduced. Authorization was keyed on the registered view name, and a registrant picks
 * that name. A view called {@code secret_pay} reading the {@code payroll} stream was authorized as
 * {@code secret_pay} — so {@code carol}, denied everything matching "payroll", saw 6 of 8 such views
 * and read 2 of them, returning real salary figures. The policy was working exactly as written; it
 * was being asked about the wrong thing.
 *
 * <p>The fixture is the shape of the original finding rather than a minimal one: the view's name
 * shares no substring with the stream it reads, because a name that gave the game away would let a
 * name-matching policy pass this test for the wrong reason.
 */
class ViewProvenanceTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("payroll")
            .field("employee", Types.string())
            .field("region", Types.string())
            .field("salary", Types.int64())
            .build();

    /** Denied anything whose name matches "payroll" — the policy exactly as the finding had it. */
    private static final SecurityPolicy BY_NAME = (principal, view) ->
            view.contains("payroll") ? AccessDecision.deny("payroll is restricted") : AccessDecision.allow();

    private static final Principal CAROL = new Principal("carol", "acme", Set.of("analyst"), Map.of());

    private ViewCatalog catalog;
    private ServedView secretPay;
    private AuditSink.InMemory audit;

    @BeforeEach
    void setUp() {
        // Registered under a name that does not contain "payroll", reading the stream that does.
        secretPay = new ServedView("secret_pay", SCHEMA, List.of(0), 10_000);
        catalog = new ViewCatalog().register(secretPay);
        secretPay.applyValues(new Object[] {"ACC-0007", "EU", 99_000L}, 1, 100);
        secretPay.applyValues(new Object[] {"ACC-0008", "US", 404_000L}, 1, 100);
        secretPay.commit(100);
        audit = new AuditSink.InMemory();
    }

    @Test
    void aViewNamedAroundThePolicyStillCannotBeRead() {
        secretPay.derivedFrom(List.of("payroll"));
        ViewQuery queries = new ViewQuery(catalog, BY_NAME, audit);

        assertThatThrownBy(() -> queries.execute("SELECT employee, salary FROM secret_pay", CAROL))
                .as("carol is denied 'payroll' and this view reads payroll. Before SX-11 she got the rows: "
                        + "the check was applied to the name 'secret_pay', which nothing tied to the data")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("carol")
                .hasMessageContaining("payroll");
    }

    @Test
    void theRefusalNamesTheStreamRatherThanLeavingTheOperatorToGuess() {
        secretPay.derivedFrom(List.of("payroll"));
        ViewQuery queries = new ViewQuery(catalog, BY_NAME, audit);

        assertThatThrownBy(() -> queries.execute("SELECT employee FROM secret_pay", CAROL))
                .as("an operator reading this needs to know *why* a view they can see refuses them, or the "
                        + "next thing they do is grant access to 'secret_pay' and wonder why nothing changed")
                .hasMessageContaining("it reads 'payroll'");
    }

    @Test
    void theDenialOfTheUnderlyingStreamIsAuditedUnderThatStreamsName() {
        secretPay.derivedFrom(List.of("payroll"));
        ViewQuery queries = new ViewQuery(catalog, BY_NAME, audit);

        assertThatThrownBy(() -> queries.execute("SELECT employee FROM secret_pay", CAROL))
                .isInstanceOf(PravahaException.class);

        assertThat(audit.events())
                .as("an audit recorded only against 'secret_pay' cannot answer 'who tried to read payroll', "
                        + "which is the question actually asked after an incident")
                .anyMatch(event -> event.target().equals("payroll") && !event.allowed());
    }

    @Test
    void aPrincipalAllowedTheUnderlyingStreamStillReadsTheView() {
        // The other half, and the one that says this is authorization rather than a blanket refusal.
        secretPay.derivedFrom(List.of("payroll"));
        ViewQuery queries = new ViewQuery(catalog, (principal, view) -> AccessDecision.allow(), audit);

        ViewQuery.Result result = queries.execute("SELECT employee, salary FROM secret_pay", CAROL);

        assertThat(result.rows()).as("nothing is denied that should not be").hasSize(2);
    }

    @Test
    void aViewThatIsItsOwnSourceIsUnaffected() {
        // Provenance left empty: the view was constructed directly rather than planned from SQL,
        // which is what the embedded API and most tests do. Nothing derived it, so there is nothing
        // behind it to check, and the view's own name remains the whole decision.
        ViewQuery queries = new ViewQuery(catalog, (principal, view) -> AccessDecision.allow(), audit);

        assertThat(secretPay.derivedFrom()).isEmpty();
        assertThat(queries.execute("SELECT employee FROM secret_pay", CAROL).rows())
                .hasSize(2);
    }

    @Test
    void aRowFilterOnTheUnderlyingStreamIsAppliedToTheDerivedView() {
        // bob is entitled to an EU slice of payroll. Reading it through a view named something else
        // must not hand him the other regions.
        secretPay.derivedFrom(List.of("payroll"));
        ViewQuery queries = new ViewQuery(
                catalog,
                (principal, view) -> view.equals("payroll")
                        ? AccessDecision.allowWithRowFilter("region = 'EU'")
                        : AccessDecision.allow(),
                audit);

        ViewQuery.Result result = queries.execute("SELECT employee FROM secret_pay", CAROL);

        assertThat(result.rows())
                .as("the filter belongs to the stream, so it has to travel with the data into every view "
                        + "derived from it — otherwise renaming is all it takes to shed an entitlement")
                .hasSize(1);
    }
}

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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Row filters and masks on the read path: scans, point reads, prepared reads (ADR-059 §4). */
class ViewQueryNarrowingTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("payments")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("card", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal ANA = new Principal("ana", "public", Set.of(), Map.of("region", "EU"));
    private static final Principal OPS = new Principal("ops", "public", Set.of("payments_ops"), Map.of());

    /** A policy that allows everyone and narrows ana: EU rows only, cards masked. */
    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }

        @Override
        public Narrowing narrowing(Principal principal, String object) {
            return principal.hasRole("payments_ops")
                    ? Narrowing.NONE
                    : new Narrowing(
                            Optional.of("region = 'EU'"),
                            Map.of("card", "'XXXX-' || SUBSTRING(card FROM 6)"),
                            List.of("because"));
        }
    };

    private ViewCatalog catalog;
    private AuditSink.InMemory audit;
    private ViewQuery queries;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("payments", SCHEMA, List.of(0), 10_000);
        catalog = new ViewCatalog().register(view);
        view.applyValues(new Object[] {"p1", "EU", "4111-1111", 10L}, 1, 100);
        view.applyValues(new Object[] {"p2", "US", "4222-2222", 20L}, 1, 100);
        view.applyValues(new Object[] {"p3", "EU", "4333-3333", 30L}, 1, 100);
        view.commit(100);
        audit = new AuditSink.InMemory();
        queries = new ViewQuery(catalog, POLICY, audit);
    }

    private static List<String> rows(ViewQuery.Result result) {
        return result.rows().stream().map(Arrays::toString).sorted().toList();
    }

    @Test
    void aScanShowsOnlyKeptRowsWithTheirMasksAndAnExemptReaderEverything() {
        assertThat(rows(queries.execute("SELECT * FROM payments", ANA)))
                .containsExactly("[p1, EU, XXXX-1111, 10]", "[p3, EU, XXXX-3333, 30]");
        assertThat(rows(queries.execute("SELECT * FROM payments", OPS))).hasSize(3);
        assertThat(audit.events()).anyMatch(e -> e.action().equals("query.narrowed"));
    }

    @Test
    void aPointReadByKeyIsNarrowedTooEvenForARowTheFilterDrops() {
        assertThat(rows(queries.execute("SELECT card FROM payments WHERE id = 'p1'", ANA)))
                .containsExactly("[XXXX-1111]");
        assertThat(queries.execute("SELECT card FROM payments WHERE id = 'p2'", ANA)
                        .rows())
                .isEmpty();
    }

    @Test
    void aQueryComputesOverWhatTheReaderIsShown() {
        assertThat(rows(queries.execute("SELECT SUM(amount) AS s FROM payments", ANA)))
                .containsExactly("[40]");
        assertThat(rows(queries.execute("SELECT UPPER(card) AS c FROM payments WHERE amount > 15", ANA)))
                .containsExactly("[XXXX-3333]");
    }

    @Test
    void aMaskedColumnComparedIsRefusedByName() {
        for (String sql : List.of(
                "SELECT id FROM payments WHERE card = '4111-1111'",
                "SELECT card, COUNT(*) AS n FROM payments GROUP BY card",
                "SELECT MAX(card) AS m FROM payments")) {
            assertThatThrownBy(() -> queries.execute(sql, ANA))
                    .as(sql)
                    .isInstanceOfSatisfying(
                            PravahaException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(SecurityErrors.MASKED_COLUMN_USE));
        }
        // The exempt reader is not narrowed, so nothing of theirs is masked.
        assertThat(queries.execute("SELECT id FROM payments WHERE card = '4111-1111'", OPS)
                        .rows())
                .hasSize(1);
        assertThat(audit.events()).anyMatch(e -> !e.allowed() && e.action().equals("query"));
    }

    @Test
    void aPreparedReadIsNarrowedOnEveryExecution() {
        ViewQuery.Prepared prepared = queries.prepare("SELECT id, card FROM payments WHERE amount >= ?", ANA);
        assertThat(rows(queries.execute(prepared, BoundParameters.of(new Object[] {15L}), ANA)))
                .containsExactly("[p3, XXXX-3333]");
        assertThat(queries.execute(prepared, BoundParameters.of(new Object[] {15L}), OPS)
                        .rows())
                .hasSize(2);
    }

    @Test
    void changesKeepTheirWeightsAndDroppedRowsGo() {
        RowNarrowing narrowing = RowNarrowing.of(SCHEMA, POLICY.narrowing(ANA, "payments"));
        List<ViewChange> changes = narrowing.applyChanges(List.of(
                new ViewChange(new Object[] {"p1", "EU", "4111-1111", 10L}, -1),
                new ViewChange(new Object[] {"p2", "US", "4222-2222", 20L}, 1),
                new ViewChange(new Object[] {"p1", "EU", "4111-1111", 11L}, 1)));
        assertThat(changes)
                .extracting(c -> Arrays.toString(c.values()) + "x" + c.weight())
                .containsExactly("[p1, EU, XXXX-1111, 10]x-1", "[p1, EU, XXXX-1111, 11]x1");
        assertThat(RowNarrowing.of(SCHEMA, Narrowing.NONE).applyChanges(changes))
                .isSameAs(changes);
        assertThat(RowNarrowing.of(SCHEMA, POLICY.narrowing(ANA, "payments"))).isSameAs(narrowing);
    }
}

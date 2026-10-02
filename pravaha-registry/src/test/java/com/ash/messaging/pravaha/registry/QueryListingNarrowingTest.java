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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
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
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LISTCOUNT-1: the totals a listing shows are withheld from a reader narrowed by a row filter the
 * policy keeps as an object -- the catalogue's {@code CREATE ROW FILTER} -- exactly as SX-18 withholds
 * them for a filter carried on the access decision.
 */
class QueryListingNarrowingTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal OPS = new Principal("ops", "public", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "public", Set.of("analyst"), Map.of());
    private static final Principal MAL = new Principal("mal", "public", Set.of("analyst"), Map.of());

    /** Everyone reads; ana is narrowed by a row filter, mal by one that cannot be bound to them. */
    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return AccessDecision.allow();
        }

        @Override
        public Narrowing narrowing(Principal principal, String object) {
            if (principal.id().equals("mal")) {
                throw new PravahaException(SecurityErrors.FORBIDDEN, "the filter reads a claim mal does not carry");
            }
            return principal.id().equals("ana")
                    ? new Narrowing(Optional.of("amount > 10"), Map.of(), List.of("region_scope"))
                    : Narrowing.NONE;
        }
    };

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), POLICY, AuditSink.NONE, TXN);
        new ContinuousQueryStatements(registry, POLICY, AuditSink.NONE)
                .execute(
                        ContinuousStatements.recognize("CREATE CONTINUOUS QUERY spend KEYED BY (user_id) AS "
                                        + "SELECT user_id, amount FROM txn")
                                .orElseThrow(),
                        OPS);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private QueryListing.Entry entryFor(Principal principal) {
        List<QueryListing.Entry> entries = new QueryListing(registry, POLICY, AuditSink.NONE).list(principal, "list");
        assertThat(entries).extracting(QueryListing.Entry::name).containsExactly("spend");
        return entries.get(0);
    }

    @Test
    void aReaderNarrowedByAPolicyObjectIsNotToldTheTotals() {
        QueryListing.Entry ana = entryFor(ANA);
        assertThat(ana.restricted()).isTrue();
        assertThat(ana.rowsIn()).isEqualTo(-1);
        assertThat(ana.rowsWrittenToSink()).isEqualTo(-1);
    }

    @Test
    void aNarrowingThatCannotBeBoundWithholdsTheTotalsRatherThanFailingTheListing() {
        assertThat(entryFor(MAL).rowsIn()).isEqualTo(-1);
    }

    @Test
    void anUnnarrowedReaderIsToldTheTotals() {
        QueryListing.Entry ops = entryFor(OPS);
        assertThat(ops.restricted()).isFalse();
        assertThat(ops.rowsIn()).isZero();
    }

    @Test
    void describingOneNameDecidesAsTheListingDoes() {
        QueryListing listing = new QueryListing(registry, POLICY, AuditSink.NONE);
        assertThat(listing.find(ANA, "spend", "describe").orElseThrow().rowsIn())
                .isEqualTo(-1);
        assertThat(listing.find(OPS, "spend", "describe").orElseThrow().restricted())
                .isFalse();
    }
}

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
package com.ash.messaging.pravaha.security;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a policy decides, and what the audit log remembers about it. */
class AccessDecisionTest {

    @Test
    void aDenialWithoutDetailSaysNothingAboutWhatExists() {
        AccessDecision decision = AccessDecision.deniedWithoutDetail();

        assertThat(decision.allowed()).isFalse();
        // "no such view" and "you may not read that view" must read the same to a caller probing
        // for what a deployment holds.
        assertThat(decision.reason()).doesNotContain("exist");
    }

    @Test
    void aRowFilterRidesAlongWithAnAllow() {
        AccessDecision decision = AccessDecision.allowWithRowFilter("tier = 'gold'");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.rowFilter()).contains("tier = 'gold'");
    }

    @Test
    void theAuditLogRecordsAllowsAsWellAsDenials() {
        AuditSink.InMemory audit = new AuditSink.InMemory();
        Principal dana = new Principal("dana", "acme", Set.of("analyst"), java.util.Map.of());

        audit.record(AuditEvent.of(dana, "query", "user_volume", AccessDecision.allow(), "SELECT 1"));
        audit.record(AuditEvent.of(dana, "query", "payroll", AccessDecision.deny("not an owner"), "SELECT 2"));

        // "Who was stopped" is the easy question. "Who read the payroll view" is the one that gets
        // asked, and a log of refusals alone cannot answer it.
        assertThat(audit.events()).hasSize(2);
        assertThat(audit.denials()).hasSize(1);
        assertThat(audit.forPrincipal("dana")).hasSize(2);
    }

    @Test
    void theAuditLogIsBoundedSoItCannotBeTheThingThatFillsTheHeap() {
        AuditSink.InMemory audit = new AuditSink.InMemory(4);
        for (int i = 0; i < 100; i++) {
            audit.record(AuditEvent.of(Principal.of("dana"), "query", "v", AccessDecision.allow(), "SELECT " + i));
        }

        assertThat(audit.events()).hasSize(4);
        assertThat(audit.events().get(3).detail()).contains("SELECT 99");
    }

    @Test
    void anAuditEventNeverCarriesTheCredential() {
        AuditEvent event = AuditEvent.of(
                new Principal("dana", "acme", Set.of("analyst"), java.util.Map.of("token", "s3cret")),
                "query",
                "user_volume",
                AccessDecision.allow(),
                "SELECT 1");

        assertThat(event.toString()).doesNotContain("s3cret");
    }

    @Test
    void aFilteredReaderMayNotAdministerTheViewTheyAreFilteredOn() {
        // SECX-2. mayAdminister deferred wholesale to mayRead, so a row filter became an
        // administrative right: a principal shown one row of a view could drop, pause or resume it
        // for every other reader, including those entitled to all of it. Being shown a slice of
        // something is the weakest claim on it there is.
        SecurityPolicy filtered = (principal, view) -> AccessDecision.allowWithRowFilter("region = 'EU'");
        Principal bob = new Principal("bob", "acme", java.util.Set.of("reader"), java.util.Map.of());

        assertThat(filtered.mayRead(bob, "sales").allowed()).isTrue();
        assertThat(filtered.mayAdminister(bob, "sales").allowed())
                .as("a filtered read is not a claim on the whole view")
                .isFalse();
        assertThat(filtered.mayAdminister(bob, "sales").reason()).contains("row filter");
    }

    @Test
    void anUnrestrictedReaderStillAdministers() {
        // The default has to stay usable: it exists because the Flight control verbs authorized
        // nothing at all, and a default that refused everybody would simply be turned off.
        SecurityPolicy open = (principal, view) -> AccessDecision.allow();
        Principal ann = new Principal("ann", "acme", java.util.Set.of("analyst"), java.util.Map.of());

        assertThat(open.mayAdminister(ann, "sales").allowed()).isTrue();
        // Anonymous too, under a permissive policy. A single-tenant development node has no
        // credentials and pausing a query on it is an ordinary thing to do; a node that requires
        // them refuses the anonymous *read* this defers to, which is where that belongs. Denying
        // anonymous here as well broke exactly that case, in FlightRegistryTest, within a minute.
        assertThat(open.mayAdminister(Principal.ANONYMOUS, "sales").allowed()).isTrue();
    }

    @Test
    void aReaderWhoIsDeniedIsStillDeniedWithTheOriginalReason() {
        SecurityPolicy closed = (principal, view) -> AccessDecision.deny("not an analyst");
        Principal carol = new Principal("carol", "acme", java.util.Set.of("intern"), java.util.Map.of());

        assertThat(closed.mayAdminister(carol, "sales").reason()).isEqualTo("not an analyst");
    }
}

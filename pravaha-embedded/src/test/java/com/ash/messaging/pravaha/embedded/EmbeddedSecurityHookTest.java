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
package com.ash.messaging.pravaha.embedded;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * POLICYPLUG-1: an embedded host can decide who may do what with a policy of its own, and see every
 * decision in a sink of its own. Before, the engine was permissive and discarded every decision, with
 * no way to say otherwise.
 */
class EmbeddedSecurityHookTest {

    /** Reads of anything but {@code payroll}; registrations allowed (the default refuses the anonymous caller). */
    private static final class NoPayroll implements SecurityPolicy {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return view.toLowerCase(java.util.Locale.ROOT).contains("payroll")
                    ? AccessDecision.deny("payroll is not for this host")
                    : AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return AccessDecision.allow();
        }
    }

    @Test
    void theHostsPolicyDecidesAndItsSinkRecordsTheDecisions() {
        AuditSink.InMemory audit = new AuditSink.InMemory();
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.securedBy(new NoPayroll()).auditingTo(audit);
            engine.declareStream("payroll", "id:INT64,amount:INT64");
            engine.declareStream("txn", "id:INT64,amount:INT64");
            engine.start();

            engine.register("big", "SELECT id, amount FROM txn WHERE amount > 100", "id");
            engine.push("txn", new Object[] {1L, 300L});
            assertThat(engine.query("SELECT id FROM big").rows()).hasSize(1);

            assertThatThrownBy(() -> engine.register("pay", "SELECT id, amount FROM payroll", "id"))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-7002")
                    .hasMessageContaining("payroll is not for this host");
        }

        assertThat(audit.events())
                .as("allows as well as denials")
                .anySatisfy(event -> assertThat(event.allowed()).isTrue());
        assertThat(audit.denials()).isNotEmpty();
    }

    @Test
    void theHookIsADeclarationAndRefusedOnceStarted() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.start();
            assertThatThrownBy(() -> engine.securedBy(SecurityPolicy.PERMISSIVE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("set the security policy");
        }
    }
}

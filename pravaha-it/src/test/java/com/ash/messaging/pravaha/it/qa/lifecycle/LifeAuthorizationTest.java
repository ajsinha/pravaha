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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LIFE-036..041 -- authorization: who may register, over what, and who may pause/resume/drop. */
@Tag("qa")
class LifeAuthorizationTest extends LifecycleTestSupport {

    /** guest may register but may not read payroll; alice may read and register anything. */
    private static final SecurityPolicy CLOSED = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            if (principal.id().equals("guest") && view.equals("payroll")) {
                return AccessDecision.deny("guest may not read payroll");
            }
            return AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return principal.id().equals("nobody")
                    ? AccessDecision.deny("nobody may not register a query")
                    : AccessDecision.allow();
        }
    };

    @Test
    void life036_aPrincipalWhoMayNotRegisterIsRefusedBeforeAnythingIsBuilt() {
        AuditSink.InMemory audit = new AuditSink.InMemory();
        QueryRegistry closed = new QueryRegistry(views, CLOSED, audit, TXN);
        try {
            Principal nobody = Principal.of("nobody");
            assertThatThrownBy(() -> closed.register("v", S1, List.of(0), nobody))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("may not register");
            assertThat(closed.names()).isEmpty();
            assertThat(audit.events())
                    .as("one audit event, recorded and denied")
                    .anySatisfy(event -> {
                        assertThat(event.action()).isEqualTo("register");
                        assertThat(event.allowed()).isFalse();
                    });
        } finally {
            closed.close();
        }
    }

    @Test
    void life037_aPrincipalWhoMayRegisterButMayNotReadTheSourceIsRefused() {
        AuditSink.InMemory audit = new AuditSink.InMemory();
        StreamSchemaHolder holder = new StreamSchemaHolder();
        QueryRegistry closed = new QueryRegistry(views, CLOSED, audit, TXN, holder.payroll());
        try {
            assertThatThrownBy(() -> closed.register("leak", "SELECT * FROM payroll", List.of(0), GUEST))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("payroll")
                    .hasMessageContaining("standing read");

            assertThat(audit.events())
                    .as("two events: register (allowed) and register:source (denied)")
                    .anySatisfy(e -> assertThat(e.action()).isEqualTo("register"))
                    .anySatisfy(e -> {
                        assertThat(e.action()).isEqualTo("register:source");
                        assertThat(e.allowed()).isFalse();
                    });

            // Control: the same principal registering over txn, which they may read, succeeds.
            assertThat(closed.register("ok", S1, List.of(0), GUEST)).isNotNull();
        } finally {
            closed.close();
        }
    }

    @Test
    void life039_rowFiltersAreSortedIntoTheFingerprint() {
        SecurityPolicy filtering = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return switch (principal.id()) {
                    case "p1" -> AccessDecision.allowWithRowFilter("region='EU' AND tier='gold'");
                    case "p2" -> AccessDecision.allowWithRowFilter("region='EU' AND tier='gold'");
                    default -> AccessDecision.allow();
                };
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        QueryRegistry policed = new QueryRegistry(views, filtering, AuditSink.NONE, TXN);
        try {
            policed.register("as_p1", S1, List.of(0), Principal.of("p1"));
            policed.register("as_p2", S1, List.of(0), Principal.of("p2"));
            policed.register("as_p3", S1, List.of(0), Principal.of("p3"));

            assertThat(policed.require("as_p1").fingerprint())
                    .as("identical filter sets share, whatever order the policy built them in")
                    .isEqualTo(policed.require("as_p2").fingerprint());
            assertThat(policed.require("as_p3").fingerprint())
                    .as("no filter at all is a different computation")
                    .isNotEqualTo(policed.require("as_p1").fingerprint());
            assertThat(policed.size()).as("two computations, three names").isEqualTo(2);
            assertThat(policed.names()).contains("as_p1", "as_p2", "as_p3");
        } finally {
            policed.close();
        }
    }

    @Test
    void life040_pauseResumeAndDropDefaultToMayRead() {
        // mayAdminister's own javadoc: "Defaults to mayRead ... the weakest defensible rule". A
        // policy that never overrides it means read access is destroy access -- verified here
        // against the shipped default rather than assumed.
        QueryRegistry policed = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        try {
            policed.register("v1", S1, List.of(0), Principal.ANONYMOUS);
            Principal reader = Principal.of("reader");

            assertThat(policed.policy().mayAdminister(reader, "v1").allowed())
                    .as("PERMISSIVE never overrides mayAdminister, so it delegates to mayRead, which allows "
                            + "everyone -- a reader with no ownership stake can pause, resume and drop")
                    .isTrue();

            policed.pause("v1");
            assertThat(policed.require("v1").state().name()).isEqualTo("PAUSED");
            policed.resume("v1");
            policed.drop("v1");
            assertThat(policed.names())
                    .as("a mere reader destroyed another principal's computation")
                    .isEmpty();
        } finally {
            policed.close();
        }
    }

    private static final class StreamSchemaHolder {
        com.ash.messaging.pravaha.api.data.StreamSchema payroll() {
            return com.ash.messaging.pravaha.api.data.StreamSchema.builder("payroll")
                    .field("id", com.ash.messaging.pravaha.api.data.Types.int64())
                    .field("salary", com.ash.messaging.pravaha.api.data.Types.int64())
                    .build();
        }
    }
}

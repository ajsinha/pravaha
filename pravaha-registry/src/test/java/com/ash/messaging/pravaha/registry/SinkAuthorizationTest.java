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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Naming a sink in a registration is a permission of its own, and the audit says which sink
 * (SINK-3).
 *
 * <p>Registration asked whether a principal may register at all and whether they may read every
 * stream the plan names, and nothing about the destination. A sink is a standing write to a store
 * outside Pravaha, under the node's own credentials, read by people who never talk to Pravaha at
 * all -- and the register audit event recorded the SQL and never said where the answer was going,
 * so "who put this in that table" was a question the trail could not answer.
 *
 * <p>The default still allows: a sink is a binding an operator wrote into this node's own
 * configuration. What is new is that the question is asked, so a deployment whose bindings are not
 * all equally trusted has somewhere to answer it.
 */
class SinkAuthorizationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String ROWS = "SELECT user_id, amount FROM txn";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private SinkDeliveryTest.RecordingSinks sinks;
    private AuditSink.InMemory audit;
    private final List<String> asked = new ArrayList<>();
    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        sinks = new SinkDeliveryTest.RecordingSinks();
        sinks.bind("orders", SinkCapabilities.appendOnly());
        audit = new AuditSink.InMemory();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void aSinkThePolicyRefusesIsRefusedAtRegistration() {
        registry = registryUnder(sink -> AccessDecision.deny("'" + sink + "' is the billing team's table"));

        assertThatThrownBy(() -> registry.registerWritingTo("q", ROWS, List.of(0), DANA, "orders"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("orders")
                .hasMessageContaining("the billing team's table");

        assertThat(asked).containsExactly("orders");
        assertThat(sinks.opened())
                .as("refused before a connection to the sink was paid for")
                .isZero();
        assertThat(registry.names()).isEmpty();
        assertThat(denied()).containsExactly("register:sink orders");
    }

    @Test
    void anAllowedSinkIsNamedInTheAudit() {
        registry = registryUnder(sink -> AccessDecision.allow());
        registry.registerWritingTo("q", ROWS, List.of(0), DANA, "orders");

        assertThat(audit.events())
                .as("the register event records the SQL; this one records the destination, which is "
                        + "what makes 'who put this there' answerable")
                .anySatisfy(event -> {
                    assertThat(event.action()).isEqualTo("register:sink");
                    assertThat(event.target()).isEqualTo("orders");
                    assertThat(event.principal().id()).isEqualTo("dana");
                    assertThat(event.allowed()).isTrue();
                });
    }

    @Test
    void aRegistrationWithNoSinkAsksNothingAboutOne() {
        registry = registryUnder(sink -> AccessDecision.deny("no sink may be written to here"));
        registry.register("q", ROWS, List.of(0), DANA);

        assertThat(asked).isEmpty();
        assertThat(audit.events().stream().map(AuditEvent::action))
                .as("a query that writes nowhere has no destination to authorize or to record")
                .doesNotContain("register:sink");
    }

    @Test
    void aSinkWriteAllowedOnlyThroughARowFilterIsRefusedRatherThanWrittenWhole() {
        registry = registryUnder(sink -> AccessDecision.allowWithRowFilter("user_id = 'dana'"));

        assertThatThrownBy(() -> registry.registerWritingTo("q", ROWS, List.of(0), DANA, "orders"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7005")
                .hasMessageContaining("user_id = 'dana'")
                .hasMessageContaining("whole changelog");

        assertThat(sinks.opened()).isZero();
        assertThat(registry.names()).isEmpty();
    }

    /** A registry whose policy answers {@code mayWriteTo} with {@code answer}, recording the asks. */
    private QueryRegistry registryUnder(java.util.function.Function<String, AccessDecision> answer) {
        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayWriteTo(Principal principal, String sink) {
                asked.add(sink);
                return answer.apply(sink);
            }
        };
        return new QueryRegistry(new ViewCatalog(), policy, audit, TXN).writingTo(sinks);
    }

    private List<String> denied() {
        return audit.denials().stream()
                .map(event -> event.action() + " " + event.target())
                .toList();
    }
}

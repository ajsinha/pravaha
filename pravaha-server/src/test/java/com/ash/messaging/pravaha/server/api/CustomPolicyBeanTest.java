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
package com.ash.messaging.pravaha.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.PravahaNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POLICYPLUG-1, on a whole server: a {@code SecurityPolicy} and an {@code AuditSink} bean of the
 * application's own are what the node -- engine, Flight and HTTP alike -- decides and records with, and
 * injecting either type still yields one object.
 */
@SpringBootTest(properties = {"pravaha.security.allow-anonymous=true", "pravaha.flight.port=0"})
class CustomPolicyBeanTest {

    /** A policy of the deployment's own. */
    static final class OwnPolicy implements SecurityPolicy {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.deny("own policy");
        }
    }

    static final AuditSink.InMemory OWN_SINK = new AuditSink.InMemory();

    @TestConfiguration
    static class Own {
        @Bean
        SecurityPolicy ownPolicy() {
            return new OwnPolicy();
        }

        @Bean
        AuditSink ownSink() {
            return OWN_SINK;
        }
    }

    @Autowired
    private PravahaNode node;

    @Autowired
    private SecurityPolicy injectedPolicy;

    @Autowired
    private AuditSink injectedSink;

    @Test
    void theApplicationsBeansAreWhatTheNodeUses() {
        assertThat(node.securityPolicy()).isInstanceOf(OwnPolicy.class);
        assertThat(node.registry().orElseThrow().policy()).isInstanceOf(OwnPolicy.class);
        assertThat(injectedPolicy)
                .as("the HTTP surface's policy is the same object")
                .isSameAs(node.securityPolicy());
        assertThat(injectedSink).isInstanceOf(AuditTrail.class);
        assertThat(((AuditTrail) injectedSink).delegate()).isSameAs(OWN_SINK);
    }
}

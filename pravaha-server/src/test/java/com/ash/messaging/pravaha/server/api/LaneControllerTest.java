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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.LaneRebalancer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code /api/v1/lanes/rebalance}: an administrator's action, and a dry run that changes nothing. */
class LaneControllerTest {

    private static final Principal ADMIN = new Principal("root", "acme", Set.of("admin"), Map.of());
    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @Test
    void onlyAnAdministratorMayRebalanceAndADryRunMovesNothing() {
        StreamSchema a = StreamSchema.builder("a")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), a).multiplexingLanes(1, 300, 2)) {
            registry.register("q_one", "SELECT user_id, amount FROM a", List.of(0), DANA);
            registry.register("q_two", "SELECT user_id, amount FROM a WHERE amount > 1", List.of(0), DANA);
            registry.register("q_three", "SELECT user_id, amount FROM a WHERE amount > 2", List.of(0), DANA);
            registry.drop("q_one");
            LaneController controller = new LaneController(
                    new RegistryAccess(registry, null, AuditSink.NONE),
                    new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE));

            assertThatThrownBy(() -> controller.start(true, as(DANA)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("admin role");
            assertThatThrownBy(() -> controller.rebalance(as(DANA))).isInstanceOf(PravahaException.class);

            LaneRebalancer.Plan plan = controller.start(true, as(ADMIN));
            assertThat(plan.mode()).isEqualTo("auto");
            assertThat(plan.autoFrom()).isEqualTo(2);
            assertThat(plan.running()).isFalse();
            assertThat(plan.moves()).extracting(LaneRebalancer.Move::name).containsExactly("q_three");
            assertThat(registry.sharedLaneOf("q_three"))
                    .as("a dry run moves nothing")
                    .isPresent();
            assertThat(controller.rebalance(as(ADMIN)).moves()).hasSize(1);
        }
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }
}

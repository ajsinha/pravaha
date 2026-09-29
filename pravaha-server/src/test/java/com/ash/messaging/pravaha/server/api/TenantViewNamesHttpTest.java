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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 over REST: {@code /api/v1/queries}, {@code /api/v1/views}, dead letters, replacements and
 * {@code /me/permissions} each answer a tenant with its own {@code orders}, and a name another tenant
 * holds exactly as a name nothing holds.
 */
class TenantViewNamesHttpTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String BIG = "SELECT user_id, amount FROM txn WHERE amount > 100";
    private static final String SMALL = "SELECT user_id, amount FROM txn WHERE amount <= 100";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal OMAR = new Principal("omar", "globex", Set.of("analyst"), Map.of());
    private static final Principal ROOT = new Principal("root", "public", Set.of("admin"), Map.of());

    private QueryRegistry registry;
    private QueryController queries;
    private ViewController views;
    private DeadLetterController deadLetters;
    private ReplacementController replacements;
    private PermissionsController permissions;

    @BeforeEach
    void start() {
        registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        registry.register("orders", BIG, List.of(0), DANA);
        registry.register("orders", SMALL, List.of(0), OMAR);
        registry.register("payroll", BIG, List.of(0, 1), DANA);

        StreamCatalog streams = new StreamCatalog();
        streams.register(TXN);
        HttpAuthorizer authorizer =
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE, () -> Optional.of(registry));
        RegistryAccess access = new RegistryAccess(registry, null, AuditSink.NONE);
        queries = new QueryController(streams, new DtoMapper(), authorizer, access);
        views = new ViewController(new DtoMapper(), authorizer, access);
        deadLetters = new DeadLetterController(authorizer, access);
        replacements = new ReplacementController(access, authorizer);
        permissions = new PermissionsController(authorizer, access, streams, "permissive");
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void eachTenantListsAndDescribesItsOwnOrders() {
        assertThat(queries.list(as(DANA)))
                .extracting(ApiDtos.QueryDetail::name)
                .containsExactlyInAnyOrder("orders", "payroll");
        assertThat(queries.list(as(OMAR))).extracting(ApiDtos.QueryDetail::name).containsExactly("orders");
        assertThat(queries.get("orders", as(DANA)).sql()).isEqualTo(BIG);
        assertThat(queries.get("orders", as(OMAR)).sql()).isEqualTo(SMALL);
        assertThat(views.describe("orders", as(OMAR)).name()).isEqualTo("orders");
        assertThat(permissions.permissions(as(OMAR)).views())
                .extracting(AdminDtos.ObjectPermission::name)
                .containsExactly("orders");

        // An admin sees every tenant's, by catalogue name outside its own.
        assertThat(queries.list(as(ROOT)))
                .extracting(ApiDtos.QueryDetail::name)
                .containsExactlyInAnyOrder("acme.default.orders", "globex.default.orders", "acme.default.payroll");
        assertThat(queries.get("acme.default.orders", as(ROOT)).sql()).isEqualTo(BIG);
    }

    @Test
    void anotherTenantsNameAnswersEveryEndpointAsANameNobodyHolds() {
        List<Function<String, Object>> endpoints = List.of(
                name -> queries.get(name, as(OMAR)),
                name -> queries.plan(name, as(OMAR)),
                name -> views.describe(name, as(OMAR)),
                name -> deadLetters.count(name, as(OMAR)),
                name -> replacements.get(name, as(OMAR)),
                name -> replacements.cutOver(name, as(OMAR)));
        for (Function<String, Object> endpoint : endpoints) {
            assertThat(refusal(() -> endpoint.apply("payroll")).replace("payroll", "X"))
                    .isEqualTo(refusal(() -> endpoint.apply("nothing")).replace("nothing", "X"));
        }
        assertThat(refusal(() -> queries.get("acme.default.payroll", as(OMAR))).replace("payroll", "X"))
                .as("qualified, refused alike held or not")
                .isEqualTo(refusal(() -> queries.get("acme.default.nothing", as(OMAR)))
                        .replace("nothing", "X"));
    }

    private static String refusal(java.util.function.Supplier<Object> call) {
        try {
            call.get();
            return "answered";
        } catch (PravahaException e) {
            return e.getMessage();
        }
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }
}

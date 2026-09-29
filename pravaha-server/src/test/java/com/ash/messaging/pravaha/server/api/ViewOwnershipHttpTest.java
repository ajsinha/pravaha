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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Administration;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Over HTTP, a view is administered by its owner, a grant or an admin -- not by a caller who may read
 * it -- and the owner is shown where the query is described.
 */
class ViewOwnershipHttpTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal RAY = new Principal("ray", "acme", Set.of("analyst"), Map.of());
    private static final Principal ROOT = new Principal("root", "acme", Set.of("admin"), Map.of());

    private QueryRegistry registry;
    private HttpAuthorizer authorizer;
    private QueryController queries;

    @BeforeEach
    void start() {
        registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        authorizer = new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE, () -> Optional.of(registry));
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);
        queries = new QueryController(
                catalog, new DtoMapper(), authorizer, new RegistryAccess(registry, null, AuditSink.NONE));
        registry.register("orders", "SELECT user_id, amount FROM txn", List.of(0), DANA);
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void aReaderWhoDoesNotOwnTheViewIsRefusedTheOwnerAndAnAdminAreNot() {
        assertThat(authorizer.mayRead(as(RAY), "orders")).isTrue();
        assertThatThrownBy(() -> authorizer.requireAdminister(as(RAY), "orders"))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(SecurityErrors.FORBIDDEN));
        assertThat(authorizer.mayAdminister(as(RAY), "orders")).isFalse();

        authorizer.requireAdminister(as(DANA), "orders");
        authorizer.requireAdminister(as(ROOT), "orders");
    }

    @Test
    void aStreamHasNoOwnerAndIsDecidedByThePolicyAsBefore() {
        authorizer.requireAdminister(as(RAY), "txn");
    }

    @Test
    void legacyReadLetsTheReaderAdministerAgain() {
        registry.owners().administering(Administration.Rule.LEGACY_READ);

        authorizer.requireAdminister(as(RAY), "orders");
    }

    @Test
    void theQueryDetailNamesItsOwner() {
        assertThat(queries.get("orders", as(RAY)).owner()).isEqualTo("dana");
        assertThat(queries.list(as(RAY)))
                .singleElement()
                .satisfies(detail -> assertThat(detail.owner()).isEqualTo("dana"));
    }

    private static MockHttpServletRequest as(Principal who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return request;
    }
}

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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP surface authorizing against the same policy the engine does.
 *
 * <p>It authorized against nothing. {@code BearerTokenFilter} resolved a token to a principal and
 * put it in a request attribute, and no controller read it -- so a principal denied every
 * payroll-named view on Flight received byte-identical responses here: payroll's full schema
 * including its salary column from {@code /streams/payroll}, full unfiltered plans for payroll
 * queries from {@code /explain}, and an unchecked {@code POST /streams} that let them publish an
 * arbitrary schema. Authentication was enforced and authorization was not, which is the half that
 * looks secure.
 */
class HttpAuthorizationTest {

    private static final StreamSchema PAYROLL = StreamSchema.builder("payroll")
            .field("employee_id", Types.string())
            .field("salary", Types.int64())
            .build();

    private static final StreamSchema PUBLIC =
            StreamSchema.builder("orders").field("order_id", Types.string()).build();

    private static final Principal ANALYST =
            new Principal("ann", "acme", java.util.Set.of("analyst"), java.util.Map.of());
    private static final Principal INTERN =
            new Principal("carol", "acme", java.util.Set.of("intern"), java.util.Map.of());

    /** Analysts read everything; interns read anything that is not payroll. */
    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return principal.hasRole("analyst") || !view.contains("payroll")
                    ? AccessDecision.allow()
                    : AccessDecision.deny("not an analyst");
        }

        @Override
        public AccessDecision mayAdminister(Principal principal, String what) {
            return principal.hasRole("analyst") ? AccessDecision.allow() : AccessDecision.deny("not an analyst");
        }
    };

    private static StreamCatalog catalogue() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(PAYROLL);
        catalog.register(PUBLIC);
        return catalog;
    }

    private static org.springframework.mock.web.MockHttpServletRequest requestAs(Principal principal) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    private static StreamController streams() {
        return new StreamController(catalogue(), new DtoMapper(), new HttpAuthorizer(POLICY, AuditSink.NONE));
    }

    @Test
    void listingStreamsShowsOnlyTheOnesTheCallerMayRead() {
        List<ApiDtos.StreamSummary> forIntern = streams().list(requestAs(INTERN));
        assertThat(forIntern).extracting(ApiDtos.StreamSummary::name).containsExactly("orders");

        List<ApiDtos.StreamSummary> forAnalyst = streams().list(requestAs(ANALYST));
        assertThat(forAnalyst)
                .as("and an entitled caller still sees everything")
                .extracting(ApiDtos.StreamSummary::name)
                .containsExactlyInAnyOrder("payroll", "orders");
    }

    @Test
    void fetchingAStreamTheCallerMayNotReadIsRefusedRatherThanDisclosed() {
        assertThatThrownBy(() -> streams().get("payroll", requestAs(INTERN)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("may not read 'payroll'");

        // The salary column is what made this worth refusing, and an entitled caller still gets it.
        assertThat(streams().get("payroll", requestAs(ANALYST)).fields())
                .extracting(ApiDtos.FieldInfo::name)
                .contains("salary");
    }

    @Test
    void publishingAStreamIsAnAdministrativeActRatherThanSomethingAnyTokenCanDo() {
        // This applied no check beyond "a token verified", so a principal denied every existing view
        // could still add one of their own.
        assertThatThrownBy(() -> streams()
                        .register(
                                new StreamController.RegisterStreamRequest("invented", "id:INT64"), requestAs(INTERN)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("may not change 'invented'");

        assertThat(streams()
                        .register(new StreamController.RegisterStreamRequest("allowed", "id:INT64"), requestAs(ANALYST))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
    }

    @Test
    void aPlanIsNotAWayToReadAStreamTheCallerMayNot() {
        QueryController queries =
                new QueryController(catalogue(), new DtoMapper(), new HttpAuthorizer(POLICY, AuditSink.NONE));

        // A plan names the columns, which is most of what the data is.
        assertThatThrownBy(() -> queries.validate(
                        new QueryController.ValidateRequest("SELECT salary FROM payroll"), requestAs(INTERN)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("may not read 'payroll'");

        assertThatThrownBy(() -> queries.explain(
                        new QueryController.ValidateRequest("SELECT salary FROM payroll"),
                        "physical",
                        requestAs(INTERN)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("may not read 'payroll'");

        // A query over a stream the caller may read is planned as before.
        assertThat(queries.validate(
                                new QueryController.ValidateRequest("SELECT order_id FROM orders"), requestAs(INTERN))
                        .valid())
                .isTrue();
    }
}

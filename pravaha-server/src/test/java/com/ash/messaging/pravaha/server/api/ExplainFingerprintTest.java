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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXPLAINFP-1: {@code /explain} answers the fingerprint a registration of the SQL would get for the
 * caller -- the same value {@code register} then answers, because it is computed by the
 * registration's own preparation -- and nothing when it is not asked for (no keys).
 */
class ExplainFingerprintTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SQL = "SELECT order_id, amount FROM orders WHERE amount > 5";

    private static final Principal ANN = new Principal("ann", "acme", Set.of("analyst"), Map.of());
    private static final Principal BOB = new Principal("bob", "acme", Set.of("sliced"), Map.of());
    private static final Principal ZED = new Principal("zed", "other", Set.of("analyst"), Map.of());
    private static final Principal INTERN = new Principal("carol", "acme", Set.of("intern"), Map.of());

    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return principal.hasRole("sliced")
                    ? AccessDecision.allowWithRowFilter("amount > 100")
                    : AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return principal.hasRole("intern")
                    ? AccessDecision.deny("interns do not register")
                    : AccessDecision.allow();
        }
    };

    private QueryRegistry registry;
    private PluginSinks sinks;
    private QueryController queries;

    @BeforeEach
    void start(@TempDir Path dir) {
        sinks = new PluginSinks()
                .bind(new SinkBinding(
                        "orders_out",
                        "filesystem",
                        Map.of("path", dir.resolve("out.csv").toString(), "schema", "order_id:STRING,amount:INT64")));
        registry = new QueryRegistry(new ViewCatalog(), POLICY, new AuditSink.InMemory(), ORDERS).writingTo(sinks);
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(ORDERS);
        AuditSink.InMemory audit = new AuditSink.InMemory();
        queries = new QueryController(
                catalog,
                new DtoMapper(),
                new HttpAuthorizer(POLICY, audit),
                new RegistryAccess(registry, sinks, audit));
    }

    @AfterEach
    void stop() throws Exception {
        registry.close();
        sinks.close();
    }

    @Test
    void theFingerprintIsTheOneRegistrationThenAnswers() {
        ApiDtos.ExplainResult explained = explain(ANN, List.of(0), null, null);
        assertThat(explained.fingerprint()).hasSize(12);
        assertThat(explained.fingerprintRefusal()).isNull();
        assertThat(explained.plan()).contains("Scan");

        String registered = registry.register("big_orders", SQL, List.of(0), ANN)
                .fingerprint()
                .shortForm();
        assertThat(explained.fingerprint()).isEqualTo(registered);
    }

    @Test
    void keysRetentionSinkRowFiltersAndTenantAreEachPartOfIt() {
        String plain = explain(ANN, List.of(0), null, null).fingerprint();
        assertThat(explain(ANN, List.of(0, 1), null, null).fingerprint()).isNotEqualTo(plain);
        assertThat(explain(ZED, List.of(0), null, null).fingerprint())
                .as("another tenant")
                .isNotEqualTo(plain);
        String sliced = explain(BOB, List.of(0), null, null).fingerprint();
        assertThat(sliced).as("a row-filtered principal is another computation").isNotEqualTo(plain);
        assertThat(sliced)
                .isEqualTo(registry.register("bobs", SQL, List.of(0), BOB)
                        .fingerprint()
                        .shortForm());

        String hour = explain(ANN, List.of(0), "PT1H", null).fingerprint();
        assertThat(hour).isNotEqualTo(plain);
        assertThat(hour)
                .isEqualTo(registry.register("hourly", SQL, List.of(0), ANN, Retention.ofAge(Duration.ofHours(1)))
                        .fingerprint()
                        .shortForm());

        // A sink changes the retention a registration keeps by default -- forever rather than the
        // node's -- so it is asked for, and judged, even though its name is not in the fingerprint.
        String toSink = explain(ANN, List.of(0), null, "orders_out").fingerprint();
        assertThat(toSink)
                .isEqualTo(registry.registerWritingTo("sunk", SQL, List.of(0), ANN, "orders_out")
                        .fingerprint()
                        .shortForm());
    }

    @Test
    void withoutKeysThereIsNoFingerprintAndARefusedRegistrationSaysWhy() {
        ApiDtos.ExplainResult unasked = explain(ANN, null, null, null);
        assertThat(unasked.fingerprint()).isNull();
        assertThat(unasked.fingerprintRefusal()).isNull();

        ApiDtos.ExplainResult refused = explain(INTERN, List.of(0), null, null);
        assertThat(refused.plan()).as("the plan is still explained").contains("Scan");
        assertThat(refused.fingerprint()).isNull();
        assertThat(refused.fingerprintRefusal().code()).isEqualTo("PRV-7002");
        assertThat(refused.fingerprintRefusal().message()).contains("interns do not register");

        assertThat(explain(ANN, List.of(0), null, "no_such_sink").fingerprintRefusal())
                .as("a sink registration would refuse")
                .isNotNull();
    }

    @Test
    void aRetentionOrKeysThatCannotBeReadAreRefused() {
        assertThatThrownBy(() -> explain(ANN, List.of(0), "a day", null))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1051")
                .hasMessageContaining("not a retention");
        assertThatThrownBy(() -> explain(ANN, List.of(-1), null, null))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1051");
        assertThat(explain(ANN, List.of(0), "forever", null).fingerprint()).isNotNull();
    }

    @Test
    void theRequestBodyCarriesTheRegistrationsFieldsAndTheOldBodyStillReads() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        QueryController.ValidateRequest full = json.readValue(
                "{\"sql\":\"SELECT 1\",\"keys\":[1,0],\"retention\":\"PT1H\",\"sink\":\"s\",\"name\":\"n\"}",
                QueryController.ValidateRequest.class);
        assertThat(full).isEqualTo(new QueryController.ValidateRequest("SELECT 1", List.of(1, 0), "PT1H", "s", "n"));
        assertThat(json.readValue("{\"sql\":\"SELECT 1\"}", QueryController.ValidateRequest.class))
                .isEqualTo(new QueryController.ValidateRequest("SELECT 1"));
    }

    private ApiDtos.ExplainResult explain(Principal who, List<Integer> keys, String retention, String sink) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return queries.explain(
                new QueryController.ValidateRequest(SQL, keys, retention, sink, null), "physical", "text", request);
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServingErrors;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The HTTP API's description of registered queries, views and sinks, and what it will not disclose.
 *
 * <p>The console could only learn a query's keys, retention and sink by parsing and re-validating,
 * could not list sinks at all, and drew plans by counting spaces. Each of those is now an endpoint --
 * and each endpoint is a new way to ask "what exists here", which is the question the Flight listing
 * spent SX-5, SX-8, SX-11 and SX-18 learning to answer carefully. These pin that the new surface
 * answers it the same way, because it calls the same code, and that no sink option -- where the
 * credentials live -- is ever part of an answer.
 */
class RegistryEndpointsTest {

    private static final StreamSchema PAYROLL = StreamSchema.builder("payroll")
            .field("employee_id", Types.string())
            .field("salary", Types.int64())
            .build();

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String ORDERS_SQL = "SELECT order_id, amount FROM orders";

    /** The one credential in this configuration. It must never appear in any response. */
    private static final String PASSWORD = "s3cr3t-hunter2-value";

    private static final Principal ROOT = new Principal("root", "acme", Set.of("analyst"), Map.of());
    private static final Principal ANALYST = new Principal("ann", "acme", Set.of("analyst"), Map.of());
    /** Denied everything named or reading payroll. */
    private static final Principal INTERN = new Principal("carol", "acme", Set.of("intern"), Map.of());
    /** Entitled to a row-filtered slice of everything. */
    private static final Principal SLICED = new Principal("bob", "acme", Set.of("sliced"), Map.of());

    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            if (principal.hasRole("analyst")) {
                return AccessDecision.allow();
            }
            if (principal.hasRole("sliced")) {
                return AccessDecision.allowWithRowFilter("amount > 0");
            }
            return view.contains("payroll") ? AccessDecision.deny("not an analyst") : AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return AccessDecision.allow();
        }
    };

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AuditSink.InMemory audit;
    private QueryRegistry registry;
    private PluginSinks sinks;
    private Path output;
    private QueryController queries;
    private ViewController views;
    private SinkController sinkController;

    @BeforeEach
    void start(@TempDir Path dir) {
        output = dir.resolve("orders-out.csv");
        audit = new AuditSink.InMemory();
        sinks = new PluginSinks()
                .bind(new SinkBinding(
                        "orders_out",
                        "filesystem",
                        Map.of(
                                "path",
                                output.toString(),
                                "schema",
                                "order_id:STRING,amount:INT64",
                                "password",
                                PASSWORD)))
                .bind(new SinkBinding(
                        "payroll_out",
                        "filesystem",
                        Map.of("path", dir.resolve("p.csv").toString())))
                .bind(new SinkBinding("broken_out", "no-such-plugin", Map.of("token", PASSWORD)));
        registry = new QueryRegistry(new ViewCatalog(), POLICY, audit, PAYROLL, ORDERS).writingTo(sinks);

        registry.registerWritingTo(
                "orders_view", ORDERS_SQL, List.of(0), ROOT, "orders_out", Retention.ofAge(Duration.ofHours(2)));
        // Named innocently, reading payroll: the SX-11 shape.
        registry.register("secret_pay", "SELECT employee_id, salary FROM payroll", List.of(0), ROOT);
        registry.register("payroll_totals", "SELECT salary, employee_id FROM payroll", List.of(1), ROOT);
        // The same question as orders_view under a name the intern may not know: one computation.
        registry.registerWritingTo(
                "payroll_orders_alias",
                ORDERS_SQL,
                List.of(0),
                ROOT,
                "orders_out",
                Retention.ofAge(Duration.ofHours(2)));

        StreamCatalog catalog = new StreamCatalog();
        catalog.register(PAYROLL);
        catalog.register(ORDERS);
        HttpAuthorizer authorizer = new HttpAuthorizer(POLICY, audit);
        RegistryAccess access = new RegistryAccess(registry, sinks, audit);
        queries = new QueryController(catalog, new DtoMapper(), authorizer, access);
        views = new ViewController(new DtoMapper(), authorizer, access);
        sinkController = new SinkController(new DtoMapper(), authorizer, access);
    }

    @AfterEach
    void stop() throws Exception {
        registry.close();
        sinks.close();
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    // ------------------------------------------------------------------ what exists

    @Test
    void theListingIsFlightsListingNameProvenanceAndAll() {
        assertThat(queries.list(as(ANALYST)))
                .extracting(ApiDtos.QueryDetail::name)
                .containsExactlyInAnyOrder("orders_view", "secret_pay", "payroll_totals", "payroll_orders_alias");

        // Denied payroll_totals and payroll_orders_alias by name, and secret_pay by what it reads.
        assertThat(queries.list(as(INTERN)))
                .extracting(ApiDtos.QueryDetail::name)
                .containsExactly("orders_view");
        assertThat(audit.forPrincipal("carol"))
                .as("the provenance refusal is recorded against the stream, as Flight records it (SX-8)")
                .anySatisfy(event -> {
                    assertThat(event.target()).isEqualTo("payroll");
                    assertThat(event.detail().orElse("")).contains("secret_pay");
                });
    }

    @Test
    void aDeniedNameIsRefusedIdenticallyWhetherOrNotItExists() {
        for (java.util.function.Function<String, Object> call : List.<java.util.function.Function<String, Object>>of(
                name -> queries.get(name, as(INTERN)),
                name -> queries.plan(name, as(INTERN)),
                name -> views.describe(name, as(INTERN)))) {
            PravahaException real = catchThrowableOfType(PravahaException.class, () -> call.apply("payroll_totals"));
            PravahaException invented =
                    catchThrowableOfType(PravahaException.class, () -> call.apply("payroll_invented"));

            assertThat(real.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
            assertThat(invented.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
            assertThat(real.getMessage().replace("payroll_totals", "X"))
                    .isEqualTo(invented.getMessage().replace("payroll_invented", "X"));
        }
    }

    @Test
    void aViewHiddenByWhatItReadsAnswersExactlyAsANameThatWasNeverRegistered() {
        // SX-5's shape on SX-11's view. secret_pay's name is allowed and the stream behind it is not; if
        // it answered 403 while an unregistered name answered 404, the intern would learn that a view
        // over payroll exists and what it is called.
        for (java.util.function.Function<String, Object> call : List.<java.util.function.Function<String, Object>>of(
                name -> queries.get(name, as(INTERN)),
                name -> queries.plan(name, as(INTERN)),
                name -> views.describe(name, as(INTERN)))) {
            PravahaException hidden = catchThrowableOfType(PravahaException.class, () -> call.apply("secret_pay"));
            PravahaException absent = catchThrowableOfType(PravahaException.class, () -> call.apply("ghost_view"));

            assertThat(hidden.errorCode()).isEqualTo(absent.errorCode());
            assertThat(hidden.errorCode()).isIn(RegistryErrors.NO_SUCH_QUERY, ServingErrors.NO_SUCH_VIEW);
            assertThat(hidden.getMessage().replace("secret_pay", "X"))
                    .isEqualTo(absent.getMessage().replace("ghost_view", "X"));
            assertThat(ApiExceptionHandler.statusFor(hidden.errorCode()).value())
                    .isEqualTo(404);
        }
    }

    @Test
    void aSharedComputationDoesNotNameItsOtherNamesToSomeoneDeniedThem() {
        ApiDtos.QueryDetail forAnalyst = queries.get("orders_view", as(ANALYST));
        ApiDtos.QueryDetail forIntern = queries.get("orders_view", as(INTERN));

        assertThat(forAnalyst.sharedWith()).containsExactly("payroll_orders_alias");
        assertThat(forIntern.sharedWith()).isEmpty();
    }

    @Test
    void aSinkDoesNotNameAHiddenWriterAndASinkNameThePolicyDeniesIsNotListed() {
        List<ApiDtos.SinkSummary> forIntern = sinkController.list(as(INTERN));

        assertThat(forIntern).extracting(ApiDtos.SinkSummary::name).containsExactly("broken_out", "orders_out");
        ApiDtos.SinkSummary orders = forIntern.stream()
                .filter(sink -> sink.name().equals("orders_out"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.writers()).containsExactly("orders_view");

        assertThat(sinkController.list(as(ANALYST)))
                .filteredOn(sink -> sink.name().equals("orders_out"))
                .singleElement()
                .satisfies(sink -> assertThat(sink.writers()).containsExactly("orders_view", "payroll_orders_alias"));
    }

    // ------------------------------------------------------------------ what is said about it

    @Test
    void aQueryIsDescribedWithItsKeysRetentionAndSink() {
        ApiDtos.QueryDetail detail = queries.get("orders_view", as(ANALYST));

        assertThat(detail.state()).isEqualTo("RUNNING");
        assertThat(detail.sql()).isEqualTo(ORDERS_SQL);
        assertThat(detail.fingerprint())
                .isEqualTo(registry.require("orders_view").fingerprint().shortForm());
        assertThat(detail.keyColumns()).containsExactly(new ApiDtos.KeyColumn("order_id", 0));
        assertThat(detail.retention()).isEqualTo("PT2H");
        assertThat(detail.sink().name()).isEqualTo("orders_out");
        assertThat(detail.sink().attached()).isTrue();
        assertThat(detail.sink().failure()).isNull();
        assertThat(detail.sink().rowsWritten()).isZero();
        assertThat(detail.countsWithheld()).isFalse();
        assertThat(detail.rowsIn()).isZero();
        assertThat(detail.reads())
                .as("from the plan's provenance, not the text")
                .containsExactly("orders");

        ApiDtos.ViewDescription view = views.describe("orders_view", as(ANALYST));
        assertThat(view.schema()).extracting(ApiDtos.FieldInfo::name).containsExactly("order_id", "amount");
        assertThat(view.keyColumns()).containsExactly(new ApiDtos.KeyColumn("order_id", 0));
        assertThat(view.retention()).isEqualTo("PT2H");
        assertThat(view.sink()).isEqualTo("orders_out");
        assertThat(view.fingerprint()).isEqualTo(detail.fingerprint());
    }

    @Test
    void aRowFilteredCallerSeesTheQueryAndNotItsTotals() {
        // SX-18 on the new surface: the view exists for bob, its cardinality is not his.
        ApiDtos.QueryDetail detail = queries.get("orders_view", as(SLICED));

        assertThat(detail.countsWithheld()).isTrue();
        assertThat(detail.rowsIn()).isEqualTo(-1);
        assertThat(detail.sink().rowsWritten()).isEqualTo(-1);

        ApiDtos.PlanGraph plan = queries.plan("orders_view", as(SLICED));
        assertThat(plan.query().rowsIn()).isEqualTo(-1);
        assertThat(plan.query().viewSize()).isEqualTo(-1);
        assertThat(plan.query().stateHeld()).isEqualTo(-1);
    }

    @Test
    void aRegisteredPlanIsAGraphAndSaysPlainlyWhyItHasNoPerOperatorNumbers() {
        // pravaha.metrics.operators is off here, as it is by default, so the counters were never
        // built into this query's stages. The note has to say that rather than leave a client to
        // read a null as "the engine cannot do this" -- see PlanOperatorMetricsTest for the
        // measured case.
        ApiDtos.PlanGraph plan = queries.plan("orders_view", as(ANALYST));

        assertThat(plan.nodes()).isNotEmpty();
        assertThat(plan.nodes().get(0).id()).isEqualTo("n0");
        assertThat(plan.nodes()).extracting(ApiDtos.PlanNode::operator).contains("Scan");
        assertThat(plan.edges()).hasSize(plan.nodes().size() - 1);
        assertThat(plan.operatorMetrics())
                .as("nothing was counting them, which is not the same as every count being zero")
                .isNull();
        assertThat(plan.bottleneck()).isNull();
        assertThat(plan.metricsNote()).contains("not published").contains("pravaha.metrics.operators is off");
        assertThat(plan.query()).isNotNull();
        assertThat(plan.query().rowsIn()).isZero();
        assertThat(plan.query().backpressureWaits())
                .as("backpressure is measured whether or not per-operator detail is on")
                .isZero();
        assertThat(plan.query().inboxCells()).isPositive();
    }

    @Test
    void explainCanAnswerWithAGraphForSqlThatIsNotRegistered() {
        ApiDtos.ExplainResult explained = queries.explain(
                new QueryController.ValidateRequest("SELECT order_id FROM orders WHERE amount > 5"),
                "physical",
                "graph",
                as(ANALYST));

        assertThat(explained.plan()).contains("Scan");
        assertThat(explained.graph().nodes())
                .extracting(ApiDtos.PlanNode::operator)
                .contains("Scan", "Filter");
        assertThat(explained.graph().query())
                .as("nothing is registered, so nothing is measured")
                .isNull();
        assertThat(queries.explain(
                                new QueryController.ValidateRequest("SELECT order_id FROM orders"),
                                "physical",
                                as(ANALYST))
                        .graph())
                .as("text is still the default")
                .isNull();
    }

    @Test
    void aValidationDiagnosticCarriesItsPositionFromTheParserRatherThanFromItsWording() {
        ApiDtos.ValidationResult result = queries.validate(
                new QueryController.ValidateRequest("SELECT order_id,\n  amont\nFROM orders"), as(ANALYST));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics().get(0).range()).isEqualTo(new ApiDtos.SourceRange(2, 3, 2, 7));
    }

    @Test
    void aSinkIsDescribedByWhatItAcceptsAndABrokenOneWithoutItsPluginsWords() {
        ApiDtos.SinkSummary orders = sinkController.list(as(ANALYST)).stream()
                .filter(sink -> sink.name().equals("orders_out"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.plugin()).isEqualTo("filesystem");
        assertThat(orders.fields()).extracting(ApiDtos.FieldInfo::name).containsExactly("order_id", "amount");
        assertThat(orders.emitModes()).containsExactly("APPEND");
        assertThat(orders.acceptsRetractions()).isFalse();
        assertThat(orders.problem()).isNull();

        ApiDtos.SinkSummary broken = sinkController.list(as(ANALYST)).stream()
                .filter(sink -> sink.name().equals("broken_out"))
                .findFirst()
                .orElseThrow();
        assertThat(broken.problem().code()).isEqualTo("PRV-5093");
        assertThat(broken.problem().message()).doesNotContain(PASSWORD);
    }

    @Test
    void noSinkOptionIsEverSerialisedByAnyOfTheseEndpoints() throws Exception {
        StringBuilder everything = new StringBuilder();
        for (Principal principal : List.of(ANALYST, INTERN, SLICED)) {
            everything.append(json.writeValueAsString(sinkController.list(as(principal))));
            everything.append(json.writeValueAsString(queries.list(as(principal))));
            everything.append(json.writeValueAsString(queries.get("orders_view", as(principal))));
            everything.append(json.writeValueAsString(queries.plan("orders_view", as(principal))));
            everything.append(json.writeValueAsString(views.describe("orders_view", as(principal))));
        }

        assertThat(everything.toString())
                .isNotBlank()
                .doesNotContain(PASSWORD)
                .doesNotContain(output.toString())
                .doesNotContain("\"options\"")
                .doesNotContain("password");
    }

    @Test
    void aSinkFailureMessageLosesAnyConfiguredCredentialBeforeItLeaves() {
        RegistryAccess access = new RegistryAccess(registry, sinks, audit);

        String redacted = access.redact("jdbc refused login with " + PASSWORD + " at " + output);

        assertThat(redacted)
                .doesNotContain(PASSWORD)
                .doesNotContain(output.toString())
                .contains("[redacted");
    }

    @Test
    void theHttpListingIsAuditedUnderItsOwnVerb() {
        queries.list(as(INTERN));

        assertThat(audit.forPrincipal("carol"))
                .extracting(AuditEvent::action)
                .contains("http.list")
                .doesNotContain("list");
    }
}

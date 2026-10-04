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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.connect.PluginRegistry;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.AuthenticatedOnlyPolicy;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The administrative endpoints: the audit trail, the plugin manifests, and a principal's own
 * permissions -- and, for each, what it will not disclose.
 *
 * <p>The audit trail is the sensitive one. It names every principal that read anything and the SQL
 * they read it with, so the property pinned first is that being allowed to read <em>views</em> does
 * not make it readable, and that looking at it is itself on the record.
 */
class AdminEndpointsTest {

    private static final StreamSchema ORDERS =
            StreamSchema.builder("orders").field("order_id", Types.string()).build();
    private static final StreamSchema PAYROLL = StreamSchema.builder("payroll")
            .field("employee_id", Types.string())
            .field("salary", Types.int64())
            .build();

    /** Where a sink binding's credential would live. It must never appear in any response. */
    private static final String PASSWORD = "hunter2-s3cr3t-credential";

    private static final Principal ADMIN = new Principal("root", "acme", Set.of("admin"), Map.of());
    /** May read every view on the node, and is still not an auditor. */
    private static final Principal ANALYST = new Principal("ann", "acme", Set.of("analyst"), Map.of());

    private static final Principal SLICED = new Principal("bob", "acme", Set.of("sliced"), Map.of());

    /** The shipped policy for an authenticated node, with its shipped default: admin reads the trail. */
    private static final SecurityPolicy AUTHENTICATED = new AuthenticatedOnlyPolicy();

    private AuditTrail trail;
    private HttpAuthorizer authorizer;
    private QueryRegistry registry;

    @BeforeEach
    void start() {
        trail = new AuditTrail(new AuditSink.InMemory(), "memory", 1_000);
        authorizer = new HttpAuthorizer(AUTHENTICATED, trail);
        registry = new QueryRegistry(new ViewCatalog(), AUTHENTICATED, trail, ORDERS, PAYROLL);
        registry.register("orders_view", "SELECT order_id FROM orders", List.of(0), ADMIN);
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    private AdminDtos.AuditPage read(
            Principal who,
            @Nullable String principal,
            @Nullable String decision,
            @Nullable Integer limit,
            @Nullable String cursor) {
        return new AuditController(authorizer, trail)
                .read(null, null, principal, null, null, decision, limit, cursor, as(who));
    }

    // ------------------------------------------------------------------ the audit trail

    @Test
    void aPrincipalWhoMayReadEveryViewMayNotReadWhoElseReadThem() {
        assertThat(AUTHENTICATED.mayRead(ANALYST, "payroll").allowed())
                .as("the premise: this principal may read everything on the node")
                .isTrue();

        PravahaException refused =
                catchThrowableOfType(PravahaException.class, () -> read(ANALYST, null, null, null, null));

        assertThat(refused).as("reading the trail is not a read of a view").isNotNull();
        assertThat(refused.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
        assertThat(ApiExceptionHandler.statusFor(refused.errorCode()).value()).isEqualTo(403);
        assertThat(refused.getMessage()).contains("audit");
    }

    @Test
    void anAnonymousCallerIsRefusedEvenWhereThePolicyWouldLetThemReadData() {
        PravahaException refused =
                catchThrowableOfType(PravahaException.class, () -> read(Principal.ANONYMOUS, null, null, null, null));
        assertThat(refused.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN);
    }

    @Test
    void anAuditorGetsTheTrailNewestFirstWithWhoWhatAndTheSql() {
        trail.record(AuditEvent.of(ANALYST, "query", "payroll", AccessDecision.allow(), "SELECT salary FROM payroll"));

        AdminDtos.AuditPage page = read(ADMIN, "ann", null, null, null);

        assertThat(page.recording()).isTrue();
        assertThat(page.sink()).isEqualTo("memory");
        assertThat(page.events()).first().satisfies(entry -> {
            assertThat(entry.principal()).isEqualTo("ann");
            assertThat(entry.target()).isEqualTo("payroll");
            assertThat(entry.decision()).isEqualTo("ALLOW");
            assertThat(entry.detail()).isEqualTo("SELECT salary FROM payroll");
            assertThat(entry.roles()).containsExactly("analyst");
        });
    }

    @Test
    void readingTheTrailIsItselfOnTheTrailWhetherAllowedOrRefused() {
        assertThat(catchThrowableOfType(PravahaException.class, () -> read(ANALYST, null, null, null, null)))
                .as("the analyst is refused")
                .isNotNull();
        read(ADMIN, "ann", "deny", 5, null);

        AdminDtos.AuditPage reads = new AuditController(authorizer, trail)
                .read(null, null, null, null, "http.audit.read", null, 10, null, as(ADMIN));

        assertThat(reads.events())
                .as("newest first: this read, the admin's filtered read, and the analyst's refused attempt")
                .extracting(AdminDtos.AuditEntry::principal, AdminDtos.AuditEntry::decision)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("root", "ALLOW"),
                        org.assertj.core.groups.Tuple.tuple("root", "ALLOW"),
                        org.assertj.core.groups.Tuple.tuple("ann", "DENY"));
        assertThat(reads.events().get(1).detail())
                .as("with what was asked for, so 'who searched for ann's refusals' has an answer")
                .isEqualTo("principal=ann&decision=deny");
    }

    @Test
    void pagesWalkTheTrailByCursorWithoutRepeats() {
        for (int i = 0; i < 12; i++) {
            trail.record(AuditEvent.of(ANALYST, "query", "v" + i, AccessDecision.allow(), ""));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            AdminDtos.AuditPage page = read(ADMIN, "ann", null, 5, cursor);
            page.events().forEach(entry -> seen.add(entry.target()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(12).doesNotHaveDuplicates().startsWith("v11").endsWith("v0");
    }

    @Test
    void aMalformedFilterIsRefusedByNameRatherThanIgnored() {
        AuditController audit = new AuditController(authorizer, trail);
        for (Runnable call : List.<Runnable>of(
                () -> audit.read("yesterday", null, null, null, null, null, null, null, as(ADMIN)),
                () -> audit.read(null, null, null, null, null, "maybe", null, null, as(ADMIN)),
                () -> audit.read(null, null, null, null, null, null, null, "abc", as(ADMIN)))) {
            PravahaException refused = catchThrowableOfType(PravahaException.class, call::run);
            assertThat(refused.errorCode()).isEqualTo(ApiErrors.INVALID_PARAMETER);
            assertThat(ApiExceptionHandler.statusFor(refused.errorCode()).value())
                    .isEqualTo(400);
        }
    }

    @Test
    @SuppressWarnings("NullAway") // no audit trail at all, on purpose
    void aNodeThatDoesNotAuditSaysSoRatherThanShowingAnEmptyTrail() {
        AdminDtos.AuditPage page = new AuditController(
                        new HttpAuthorizer(AUTHENTICATED, AuditSink.NONE), (AuditTrail) null)
                .read(null, null, null, null, null, null, null, null, as(ADMIN));
        assertThat(page.recording()).isFalse();
        assertThat(page.events()).isEmpty();
        assertThat(page.note()).contains("pravaha.security.audit=none");
    }

    @Test
    void theRolesThatMayReadTheTrailAreConfigurable() {
        SecurityPolicy auditors = new AuthenticatedOnlyPolicy(List.of("auditor"));
        assertThat(auditors.mayReadAudit(ADMIN).allowed())
                .as("admin is the default, not a built-in")
                .isFalse();
        assertThat(auditors.mayReadAudit(new Principal("aud", "acme", Set.of("auditor"), Map.of()))
                        .allowed())
                .isTrue();
        assertThat(new AuthenticatedOnlyPolicy(List.of()).mayReadAudit(ADMIN).allowed())
                .as("an empty list closes the trail to everybody")
                .isFalse();
    }

    // ------------------------------------------------------------------ plugins

    private PluginController plugins(SecurityPolicy policy, @Nullable PluginRegistry registered) {
        SourceBindingProperties sources = new SourceBindingProperties();
        SourceBindingProperties.Spec orders = new SourceBindingProperties.Spec();
        orders.setPlugin("filesystem");
        orders.setOptions(Map.of("path", "/data/orders", "password", PASSWORD));
        sources.getSources().put("orders", orders);
        SourceBindingProperties.Spec payroll = new SourceBindingProperties.Spec();
        payroll.setPlugin("filesystem");
        payroll.setOptions(Map.of("secret", PASSWORD));
        sources.getSources().put("payroll", payroll);
        SinkBindingProperties sinks = new SinkBindingProperties();
        SinkBindingProperties.Spec broken = new SinkBindingProperties.Spec();
        // A plugin this project does not ship. It was "kafka" until 2026-09-26, when every shipped
        // connector moved into the server jar and "kafka" stopped being absent; the case is about a
        // binding that names a plugin nobody installed, so it names one that cannot be.
        broken.setPlugin("iceberg");
        broken.setOptions(Map.of("sasl.password", PASSWORD));
        sinks.getSinks().put("events_out", broken);
        return new PluginController(
                () -> registered,
                sources,
                sinks,
                new HttpAuthorizer(policy, AuditSink.NONE),
                PluginController::discover);
    }

    /** Payroll's name is readable only by analysts. */
    private static final SecurityPolicy NAMES = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return principal.hasRole("analyst") || !view.contains("payroll")
                    ? AccessDecision.allow()
                    : AccessDecision.deny("not an analyst");
        }
    };

    @Test
    void everyLoadablePluginIsListedWithItsManifestAndDeclaredCapabilities() {
        List<AdminDtos.PluginInfo> listed = plugins(NAMES, null).list(as(ANALYST));

        AdminDtos.PluginInfo filesystem = listed.stream()
                .filter(plugin -> plugin.name().equals("filesystem"))
                .findFirst()
                .orElseThrow();
        assertThat(filesystem.loaded()).isTrue();
        assertThat(filesystem.compatible()).isTrue();
        assertThat(filesystem.version()).isNotBlank();
        assertThat(filesystem.requiredApiVersion()).isNotBlank();
        assertThat(filesystem.kinds())
                .as("what its code can be, from the SPIs it implements")
                .contains("source", "sink");
        assertThat(java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(filesystem.capabilities())
                                .source())
                        .guarantee())
                .isEqualTo("EXACTLY_ONCE");
        assertThat(java.util.Objects.requireNonNull(filesystem.capabilities().sink())
                        .emitModes())
                .containsExactly("APPEND");
        assertThat(filesystem.health().reported())
                .as("no instance the node holds reports for it, and the answer says so")
                .isFalse();
        assertThat(filesystem.bindings())
                .extracting(AdminDtos.PluginBinding::name)
                .containsExactly("orders", "payroll");
    }

    @Test
    void aBindingTheCallerMayNotReadIsNotShownAndAMissingPluginIsNotLoadedRatherThanDropped() {
        List<AdminDtos.PluginInfo> listed = plugins(NAMES, null).list(as(SLICED));

        assertThat(listed.stream().filter(plugin -> plugin.name().equals("filesystem")))
                .singleElement()
                .satisfies(plugin -> assertThat(plugin.bindings())
                        .extracting(AdminDtos.PluginBinding::name)
                        .containsExactly("orders"));
        assertThat(listed.stream().filter(plugin -> plugin.name().equals("iceberg")))
                .singleElement()
                .satisfies(plugin -> {
                    assertThat(plugin.loaded()).isFalse();
                    assertThat(plugin.health().state()).isEqualTo("UNKNOWN");
                    assertThat(plugin.bindings()).containsExactly(new AdminDtos.PluginBinding("sink", "events_out"));
                });
    }

    @Test
    void noBindingOptionReachesTheResponseAndARegisteredPluginReportsLiveHealth() throws Exception {
        PluginRegistry registered = new PluginRegistry()
                .register(
                        new com.ash.messaging.pravaha.api.plugin.PluginManifest(
                                "vault",
                                new com.ash.messaging.pravaha.api.plugin.Version(2, 1, 0),
                                com.ash.messaging.pravaha.api.plugin.Version.apiVersion(),
                                "com.example.Vault",
                                Map.of("endpoint", "where the vault is", "token", "the credential")),
                        new DegradedPlugin());

        List<AdminDtos.PluginInfo> listed = plugins(NAMES, registered).list(as(ANALYST));
        String body = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(listed);

        assertThat(body)
                .as("options are where credentials live; nothing in the response is built from them")
                .doesNotContain(PASSWORD)
                .doesNotContain("/data/orders");
        AdminDtos.PluginInfo vault = listed.stream()
                .filter(plugin -> plugin.name().equals("vault"))
                .findFirst()
                .orElseThrow();
        assertThat(vault.version()).isEqualTo("2.1.0");
        assertThat(vault.settings())
                .as("the setting names the manifest declares -- names, never values")
                .containsExactly("endpoint", "token");
        assertThat(vault.health()).isEqualTo(new AdminDtos.PluginHealth("DEGRADED", "slow", true));
    }

    /** A plugin registered with the engine directly, reporting a health of its own. */
    private static final class DegradedPlugin implements com.ash.messaging.pravaha.api.plugin.PravahaPlugin {
        @Override
        public String name() {
            return "vault";
        }

        @Override
        public com.ash.messaging.pravaha.api.plugin.Version version() {
            return new com.ash.messaging.pravaha.api.plugin.Version(2, 1, 0);
        }

        @Override
        public void configure(com.ash.messaging.pravaha.api.plugin.PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}

        @Override
        public com.ash.messaging.pravaha.api.plugin.HealthStatus health() {
            return com.ash.messaging.pravaha.api.plugin.HealthStatus.degraded("slow");
        }
    }

    // ------------------------------------------------------------------ permissions

    private PermissionsController permissions(SecurityPolicy policy) {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(ORDERS);
        catalog.register(PAYROLL);
        HttpAuthorizer checks = new HttpAuthorizer(policy, trail);
        return new PermissionsController(checks, new RegistryAccess(registry, null, trail), catalog, "custom");
    }

    @Test
    void permissionsAreThePolicysOwnAnswersForTheCaller() {
        AdminDtos.Permissions admin = permissions(AUTHENTICATED).permissions(as(ADMIN));
        assertThat(admin.principal()).isEqualTo("root");
        assertThat(admin.readAudit().allowed()).isTrue();
        assertThat(admin.register().allowed()).isTrue();
        assertThat(admin.views())
                .containsExactly(
                        new AdminDtos.ObjectPermission("orders_view", "full", new AdminDtos.Decision(true, null)));

        AdminDtos.Permissions analyst = permissions(AUTHENTICATED).permissions(as(ANALYST));
        assertThat(analyst.readAudit().allowed()).isFalse();
        assertThat(analyst.readAudit().reason()).contains("admin");
    }

    @Test
    void permissionsNameOnlyWhatTheCallerCouldAlreadySeeAndNeverARowFilter() {
        SecurityPolicy sliced = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                if (view.contains("payroll")) {
                    return AccessDecision.deny("not yours");
                }
                return AccessDecision.allowWithRowFilter("desk = 'emea-secret-desk'");
            }
        };
        AdminDtos.Permissions answer = permissions(sliced).permissions(as(SLICED));

        assertThat(answer.streams())
                .as("payroll is refused by name, so it is absent rather than listed with a no")
                .extracting(AdminDtos.ObjectPermission::name)
                .containsExactly("orders");
        assertThat(answer.views()).singleElement().satisfies(view -> {
            assertThat(view.read()).isEqualTo("filtered");
            assertThat(view.administer().allowed()).isFalse();
            assertThat(view.administer().reason()).doesNotContain("emea-secret-desk");
        });
        assertThat(answer.readAudit().allowed())
                .as("a lambda-shaped policy keeps the trail closed")
                .isFalse();
    }
}

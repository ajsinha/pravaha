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
package com.ash.messaging.pravaha.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code LIST} discloses, and what it records about having decided (SX-8, SX-18).
 *
 * <p>Two findings about the same block, and they are separate defects in the same decision. SX-8:
 * the per-view authorization filtering produced <strong>zero</strong> audit events, so a principal
 * probing what a node holds could be refused every view in the catalogue and leave no trace of it --
 * the only verb in the audit-completeness matrix whose decisions were invisible. SX-18: a principal
 * entitled to a row-filtered slice was told the view's <em>unfiltered</em> row count, true
 * cardinality beyond their entitlement, on a field the CLI parses.
 */
@Timeout(60)
class FlightListingDisclosureTest {

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .build();

    private static final String SQL = "SELECT trade_id, product_type FROM trade";

    /** Sees everything, including true counts. */
    private static final String AUDITOR_TOKEN = "auditor-token";

    /** Entitled to a slice of every view: may see that they exist, may not have their totals. */
    private static final String SLICED_TOKEN = "sliced-token";

    /** Denied the stream behind every view: sees nothing, and every refusal is recorded. */
    private static final String OUTSIDER_TOKEN = "outsider-token";

    /** Denied by name: the other refusal branch. */
    private static final String STRANGER_TOKEN = "stranger-token";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private QueryRegistry registry;
    private RowArena arena;
    private AuditSink.InMemory audit;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        audit = new AuditSink.InMemory();

        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                if (principal.hasRole("auditor")) {
                    return AccessDecision.allow();
                }
                if (principal.hasRole("sliced")) {
                    return AccessDecision.allowWithRowFilter("product_type = 'swap'");
                }
                if (principal.hasRole("outsider")) {
                    // Allowed the view's *name* and denied the stream behind it, which is the
                    // provenance branch SX-11 added and SX-8 says nothing records.
                    return "trade".equals(view)
                            ? AccessDecision.deny("the trade stream is not for outsiders")
                            : AccessDecision.allow();
                }
                return AccessDecision.deny("no listing for " + principal.id());
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };

        StaticTokenVerifier verifier = StaticTokenVerifier.of(
                        AUDITOR_TOKEN, new Principal("dana", "acme", Set.of("auditor"), Map.of()))
                .and(SLICED_TOKEN, new Principal("bob", "acme", Set.of("sliced"), Map.of()))
                .and(OUTSIDER_TOKEN, new Principal("carol", "acme", Set.of("outsider"), Map.of()))
                .and(STRANGER_TOKEN, new Principal("erin", "acme", Set.of("stranger"), Map.of()));

        registry = new QueryRegistry(views, policy, audit, TRADE);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        registry.register("sales_view", SQL, List.of(0), new Principal("root", "acme", Set.of("auditor"), Map.of()));

        server = new PravahaFlightServer(views, allocator)
                .hosting(registry)
                .authenticatedBy(verifier)
                .authorizedBy(policy, audit)
                .start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();

        // Four rows in, which is the number the finding quotes: bob's own read of this view returns
        // two, and the listing was telling him four.
        feed("t1", "swap");
        feed("t2", "swap");
        feed("t3", "future");
        feed("t4", "future");
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        registry.close();
        arena.close();
        if (allocator != null) {
            allocator.close();
        }
    }

    private static CallOption[] bearing(String token) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return new CallOption[] {new HeaderCallOption(headers)};
    }

    private List<List<String>> list(String token) {
        List<List<String>> results = new ArrayList<>();
        client.doAction(new Action(ControlWire.LIST, ControlWire.encode()), bearing(token))
                .forEachRemaining(result -> results.add(ControlWire.decode(result.getBody())));
        return results;
    }

    private void feed(String tradeId, String product) {
        RegisteredQuery query = registry.require("sales_view");
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tradeId);
        writer.setString(1, product);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(java.time.Duration.ofSeconds(10));
        query.commit();
    }

    private List<AuditEvent> listingEventsFor(String principal) {
        return audit.forPrincipal(principal).stream()
                .filter(event -> event.action().equals("list"))
                .toList();
    }

    // ------------------------------------------------------------------------------- SX-8

    @Test
    void aViewHiddenByNameIsRecordedAsARefusal() {
        assertThat(list(STRANGER_TOKEN)).isEmpty();

        assertThat(listingEventsFor("erin"))
                .as("a principal probing what a node holds was refused and left no trace at all")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.allowed()).isFalse();
                    assertThat(event.target()).isEqualTo("sales_view");
                    assertThat(event.reason()).contains("no listing for erin");
                });
    }

    @Test
    void aViewHiddenByWhatItReadsIsRecordedAgainstTheStreamThatHidIt() {
        assertThat(list(OUTSIDER_TOKEN)).isEmpty();

        assertThat(listingEventsFor("carol"))
                .as("'who tried to reach the trade stream' has to be one grep over one field")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.allowed()).isFalse();
                    assertThat(event.target()).isEqualTo("trade");
                    assertThat(event.detail())
                            .hasValueSatisfying(detail -> assertThat(detail).contains("sales_view"));
                });
    }

    @Test
    void aViewThatIsShownIsRecordedToo() {
        assertThat(list(AUDITOR_TOKEN)).hasSize(1);

        // Recorded whether allowed or denied, for the reason this repository states everywhere else
        // it audits: a log holding only refusals answers "who was stopped" and not "who was shown
        // the payroll views", which is the question that gets asked.
        assertThat(listingEventsFor("dana")).singleElement().satisfies(event -> {
            assertThat(event.allowed()).isTrue();
            assertThat(event.target()).isEqualTo("sales_view");
        });
    }

    // ------------------------------------------------------------------------------ SX-18

    @Test
    void aPrincipalEntitledToASliceIsNotToldTheViewsTrueRowCount() {
        List<List<String>> listed = list(SLICED_TOKEN);

        assertThat(listed).singleElement().satisfies(row -> {
            // Still listed, and that is deliberate: bob may legitimately read part of this view, so
            // hiding it from him would be the wrong fix. What he may not have is its cardinality.
            assertThat(row.get(0)).isEqualTo("sales_view");
            assertThat(row.get(4))
                    .as("the withheld convention: a decimal long that no true count can equal, "
                            + "parsed unchanged by both shipped SDKs")
                    .isEqualTo("-1");
        });
    }

    @Test
    void anUnrestrictedPrincipalStillGetsTheRealCount() {
        // The other half of the contract: withholding is for the principal whose access is
        // conditional, not a blanket refusal that costs every operator the number they came for.
        assertThat(list(AUDITOR_TOKEN)).singleElement().satisfies(row -> {
            assertThat(row.get(0)).isEqualTo("sales_view");
            assertThat(row.get(4)).isEqualTo("4");
        });
    }

    @Test
    void sx1_aDeniedSubscriberCannotTellAnExistingViewFromAnAbsentOne() {
        // SX-1, and the ordering that closed it. streamSubscription resolved the name before it
        // authorized it, so the registry answered first: a real-but-forbidden view gave a policy
        // refusal, an absent one gave "no query named 'zzz' is registered; this node has [...]"
        // with the whole catalogue attached. A principal denied every view could map the node by
        // misspelling one name.
        //
        // Both halves are fixed -- mayRead runs before require, and require no longer enumerates --
        // and neither had a test. The enumeration is pinned by LifeDropTest and LifePauseTest at
        // the registry; the ORDER is pinned here, because swapping those two lines back restores
        // the oracle without failing anything else.
        String existing = subscribeRefusal(STRANGER_TOKEN, "sales_view");
        String absent = subscribeRefusal(STRANGER_TOKEN, "zzz_nope");

        assertThat(existing)
                .as("a denied subscriber must actually be refused, or this test proves nothing")
                .isNotEqualTo(NOT_REFUSED);
        assertThat(absent).isNotEqualTo(NOT_REFUSED);

        // The channel that mattered: the name of a view this principal may not see, handed to them
        // because they asked for a different name that does not exist.
        assertThat(absent)
                .as("a refusal for an absent name must not disclose a view the caller may not read")
                .doesNotContain("sales_view");

        // And the codes must agree. Different codes for "forbidden" and "absent" are the same
        // oracle spelled in a number rather than in a list -- which is SX-5's remaining channel on
        // the query path, and must not be reintroduced here.
        assertThat(codeIn(absent))
                .as(
                        "existing and absent must refuse identically to a principal authorized for "
                                + "neither; got '%s' for the real view and '%s' for the absent one",
                        existing, absent)
                .isEqualTo(codeIn(existing));
    }

    /** What a subscription attempt reports, or {@link #NOT_REFUSED} when it was allowed. */
    private String subscribeRefusal(String token, String view) {
        try (org.apache.arrow.flight.FlightStream stream = client.getStream(
                new org.apache.arrow.flight.Ticket(ControlWire.subscribeTicket(view, List.of())), bearing(token))) {
            while (stream.next()) {
                // Draining. Reaching here at all is the failure this test is about.
            }
            return NOT_REFUSED;
        } catch (Exception refused) {
            return String.valueOf(refused.getMessage());
        }
    }

    private static final String NOT_REFUSED = "not refused";

    /** The {@code PRV-} code a refusal carries, which is the part a caller can branch on. */
    private static String codeIn(String message) {
        java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("PRV-\\d{4}").matcher(String.valueOf(message));
        return found.find() ? found.group() : "no code";
    }
}

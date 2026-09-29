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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
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
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registering and subscribing over the wire.
 *
 * <p>Flight SQL has no vocabulary for either -- it was designed for asking questions, not for
 * standing up computations -- so both arrive as Flight actions and a Flight ticket, which is the
 * extension the protocol provides. Driven here with the stock Flight client, because whatever this
 * test does is what the SDKs have to be able to do.
 */
@Timeout(60)
class FlightRegistryTest {

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .build();

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        // A policy that permits registration, as an embedded or development server would have. The
        // *default* refuses it to an anonymous caller -- registering commits the node to work for as
        // long as the query runs -- and there is a test below that it still does.
        // PERMISSIVE, as an embedded or development server would have: it permits registration as
        // well as reading. The *default* policy refuses registration to an anonymous caller, and
        // there is a test below that it still does.
        registry = new QueryRegistry(
                views,
                com.ash.messaging.pravaha.security.SecurityPolicy.PERMISSIVE,
                com.ash.messaging.pravaha.security.AuditSink.NONE,
                TRADE);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);

        server = new PravahaFlightServer(views, allocator).hosting(registry).start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
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

    private List<List<String>> act(String type, String... fields) {
        List<List<String>> results = new ArrayList<>();
        client.doAction(new Action(type, ControlWire.encode(fields)))
                .forEachRemaining(result -> results.add(ControlWire.decode(result.getBody())));
        return results;
    }

    private void feed(String tradeId, String product) {
        RegisteredQuery query = registry.require("trade_feed");
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
        // Applied on the lane's thread now, so the commit has to wait for it: committing first
        // would publish a frontier the row had not reached yet, and the subscriber would be told
        // about a change it cannot see.
        query.awaitApplied(java.time.Duration.ofSeconds(10));
        query.commit();
    }

    @Test
    void aQueryCanBeRegisteredOverTheWire() {
        List<List<String>> results =
                act(ControlWire.REGISTER, "trade_feed", "SELECT trade_id, product_type FROM trade", "0");

        assertThat(results).singleElement().satisfies(row -> {
            assertThat(row.get(0)).isEqualTo("trade_feed");
            assertThat(row.get(1)).isEqualTo("RUNNING");
        });
        assertThat(registry.names()).contains("trade_feed");
    }

    @Test
    void registeredQueriesCanBeListedPausedResumedAndDropped() {
        act(ControlWire.REGISTER, "trade_feed", "SELECT trade_id, product_type FROM trade", "0");

        assertThat(act(ControlWire.LIST)).singleElement().satisfies(row -> {
            assertThat(row.get(0)).isEqualTo("trade_feed");
            assertThat(row.get(1)).isEqualTo("RUNNING");
            assertThat(row.get(2)).contains("SELECT");
        });

        assertThat(act(ControlWire.PAUSE, "trade_feed").get(0).get(1)).isEqualTo("PAUSED");
        assertThat(act(ControlWire.RESUME, "trade_feed").get(0).get(1)).isEqualTo("RUNNING");
        assertThat(act(ControlWire.DROP, "trade_feed").get(0).get(1)).isEqualTo("DROPPED");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void theListingCarriesKeysSinkAndRetentionAsTrailingFieldsAfterTheOriginalFive() {
        act(ControlWire.REGISTER, "trade_feed", "SELECT trade_id, product_type FROM trade", "0,1", "", "PT24H");

        assertThat(act(ControlWire.LIST)).singleElement().satisfies(row -> {
            // The first five are exactly what a client built before these existed reads.
            assertThat(row.subList(0, 2)).containsExactly("trade_feed", "RUNNING");
            assertThat(row.get(ControlWire.listField("rows_in"))).isEqualTo("0");
            assertThat(row).hasSize(ControlWire.LIST_FIELDS.size());
            assertThat(row.get(ControlWire.listField("key_ordinals")))
                    .as("key ordinals, as REGISTER takes them")
                    .isEqualTo("0,1");
            assertThat(row.get(ControlWire.listField("sink")))
                    .as("no sink is an empty field, not a missing one")
                    .isEmpty();
            assertThat(row.get(ControlWire.listField("retention"))).isEqualTo("PT24H");
            // FEED-1, trailing after these: nothing is bound here, so no feed and no stop.
            assertThat(row.subList(ControlWire.listField("feed_state"), ControlWire.listField("sink_state")))
                    .containsExactly("NONE", "", "", "", "");
            // SINK-3, trailing after the feed: this query writes nowhere, so there is no sink to
            // be attached or detached and no failure.
            assertThat(row.subList(ControlWire.listField("sink_state"), ControlWire.listField("owner")))
                    .containsExactly("NONE", "", "");
            // Last, the owner: who registered it, and so who may drop it without a grant.
            assertThat(row.get(ControlWire.listField("owner"))).isEqualTo(Principal.ANONYMOUS.id());
        });
    }

    @Test
    void aRetentionOnTheWireIsTheOneTheViewKeeps() {
        act(ControlWire.REGISTER, "day_view", "SELECT trade_id, product_type FROM trade", "0", "", "pt2h");
        act(ControlWire.REGISTER, "all_view", "SELECT product_type, trade_id FROM trade", "0", "", "forever");
        act(ControlWire.REGISTER, "default_view", "SELECT trade_id FROM trade", "0");

        assertThat(registry.require("day_view").view().retention())
                .isEqualTo(com.ash.messaging.pravaha.serving.Retention.ofAge(java.time.Duration.ofHours(2)));
        assertThat(registry.require("all_view").view().retention().isForever()).isTrue();
        assertThat(registry.require("default_view").view().retention())
                .as("no fifth field is this registry's default, as before the field existed")
                .isEqualTo(registry.defaultRetention());
    }

    @Test
    void aRetentionThatCannotBeReadIsRefusedRatherThanDefaulted() {
        assertThatThrownBy(() -> act(ControlWire.REGISTER, "v", "SELECT trade_id FROM trade", "0", "", "a day"))
                .isInstanceOf(FlightRuntimeException.class)
                .hasMessageContaining("is not a retention");
        assertThatThrownBy(() -> act(ControlWire.REGISTER, "v", "SELECT trade_id FROM trade", "0", "", "-PT1H"))
                .isInstanceOf(FlightRuntimeException.class)
                .hasMessageContaining("is not a retention");
        assertThat(registry.names()).doesNotContain("v");
    }

    /** A subscriber running on its own thread, collecting trade ids until cancelled. */
    private final class Reader implements AutoCloseable {
        private final List<String> received = new ArrayList<>();
        private final CountDownLatch got;
        private final java.util.concurrent.atomic.AtomicReference<FlightStream> open =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final Thread thread;

        Reader(String view, List<String> filters, int expect) {
            this.got = new CountDownLatch(expect);
            this.thread = Thread.ofVirtual().start(() -> {
                try (FlightStream stream = client.getStream(new Ticket(ControlWire.subscribeTicket(view, filters)))) {
                    open.set(stream);
                    while (stream.next()) {
                        VectorSchemaRoot root = stream.getRoot();
                        VarCharVector ids = (VarCharVector) root.getVector("trade_id");
                        for (int i = 0; i < root.getRowCount(); i++) {
                            synchronized (received) {
                                received.add(new String(ids.get(i), StandardCharsets.UTF_8));
                            }
                            got.countDown();
                        }
                    }
                } catch (Exception e) {
                }
            });
        }

        boolean await() throws InterruptedException {
            return got.await(20, TimeUnit.SECONDS);
        }

        /**
         * Waits until the server has actually attached this subscription.
         *
         * <p>A subscription starts from now, not from the beginning of time, so a change committed
         * before it attaches is published to nobody. Sleeping "long enough" makes that a race that
         * passes on a quiet machine and fails on a busy one; asking the query how many subscribers
         * it has makes it a fact.
         */
        void awaitAttached() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                if (registry.require("trade_feed").subscriberCount() > 0) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("the subscription never attached");
        }

        List<String> received() {
            synchronized (received) {
                return List.copyOf(received);
            }
        }

        @Override
        public void close() throws InterruptedException {
            // Cancelled, not interrupted. Interrupting leaves the server's call parked holding an
            // Arrow root, and the allocator then reports leaked memory at teardown -- which is Arrow
            // being right, and a real bug if it happened outside a test.
            FlightStream stream = open.get();
            if (stream != null) {
                stream.cancel("done", null);
            }
            thread.join(5_000);
            // And wait for the *server* to notice and release its Arrow root. Cancelling tells the
            // client to stop; the server's loop finds out on its next poll. Closing the allocator
            // before then reports leaked memory, and Arrow is right to -- at that instant the memory
            // genuinely is still held.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline
                    && registry.find("trade_feed")
                                    .map(RegisteredQuery::subscriberCount)
                                    .orElse(0)
                            > 0) {
                Thread.sleep(20);
            }
        }
    }

    @Test
    void aSubscriberReceivesChangesAsTheyAreCommitted() throws Exception {
        act(ControlWire.REGISTER, "trade_feed", "SELECT trade_id, product_type FROM trade", "0");

        try (Reader reader = new Reader("trade_feed", List.of(), 2)) {
            reader.awaitAttached();
            feed("T-1", "SWAP");
            feed("T-2", "EQUITY");

            assertThat(reader.await()).as("changes should arrive over the wire").isTrue();
            assertThat(reader.received()).contains("T-1", "T-2");
        }
    }

    @Test
    void aSubscriberCanFilterAtTheTap() throws Exception {
        act(ControlWire.REGISTER, "trade_feed", "SELECT trade_id, product_type FROM trade", "0");

        try (Reader reader = new Reader("trade_feed", List.of("product_type", "SWAP"), 1)) {
            reader.awaitAttached();
            feed("T-equity", "EQUITY");
            feed("T-swap", "SWAP");

            assertThat(reader.await()).isTrue();
            // The equity trade never crosses the wire. One computation, many filtered taps.
            assertThat(reader.received()).containsExactly("T-swap");
        }
    }

    @Test
    void subscribingToSomethingUnregisteredIsRefused() {
        assertThatThrownBy(() -> {
                    try (FlightStream stream =
                            client.getStream(new Ticket(ControlWire.subscribeTicket("nope", List.of())))) {
                        stream.next();
                    }
                })
                .isInstanceOf(FlightRuntimeException.class)
                .hasMessageContaining("PRV-8002");
    }

    @Test
    void theDefaultPolicyRefusesAnonymousRegistration() throws Exception {
        ViewCatalog views = new ViewCatalog();
        // A lambda policy: it implements mayRead and keeps the interface's default
        // mayRegisterQuery, which is what almost anybody writing a custom policy will do.
        // One policy object, given to both. The server and the registry each hold one, and
        // passing it to only one of them is now refused -- registering would be judged by one
        // set of rules and reading by the other.
        com.ash.messaging.pravaha.security.SecurityPolicy readable =
                (principal, view) -> com.ash.messaging.pravaha.security.AccessDecision.allow();

        try (QueryRegistry strict =
                        new QueryRegistry(views, readable, com.ash.messaging.pravaha.security.AuditSink.NONE, TRADE);
                PravahaFlightServer guarded = new PravahaFlightServer(views)
                        .authorizedBy(readable, com.ash.messaging.pravaha.security.AuditSink.NONE)
                        .hosting(strict)
                        .start("localhost", 0);
                FlightClient other = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", guarded.port()))
                        .build()) {

            // Reading is allowed to an anonymous caller and registering is not, because they are
            // different risks: a read costs a scan and ends, a registration commits the node to
            // memory and a share of every lane for as long as it exists.
            assertThatThrownBy(() -> other.doAction(new Action(
                                    ControlWire.REGISTER, ControlWire.encode("t", "SELECT trade_id FROM trade", "0")))
                            .forEachRemaining(result -> {}))
                    .isInstanceOf(FlightRuntimeException.class)
                    .hasMessageContaining("PRV-7002");
        }
    }

    @Test
    void aConditionalEntitlementCannotBeSubscribedTo() throws Exception {
        ViewCatalog views = new ViewCatalog();
        // A policy that grants access *conditionally* -- the principal may read this view, but only
        // the rows matching a predicate. This is the shape ADR-031 calls a row filter, and it is how
        // a real deployment expresses "this desk sees its own region".
        com.ash.messaging.pravaha.security.SecurityPolicy conditional =
                new com.ash.messaging.pravaha.security.SecurityPolicy() {
                    @Override
                    public com.ash.messaging.pravaha.security.AccessDecision mayRead(
                            com.ash.messaging.pravaha.security.Principal principal, String view) {
                        return com.ash.messaging.pravaha.security.AccessDecision.allowWithRowFilter(
                                "product_type = 'SWAP'");
                    }

                    @Override
                    public com.ash.messaging.pravaha.security.AccessDecision mayRegisterQuery(
                            com.ash.messaging.pravaha.security.Principal principal) {
                        return com.ash.messaging.pravaha.security.AccessDecision.allow();
                    }
                };

        try (QueryRegistry filtered = new QueryRegistry(
                        views, conditional, com.ash.messaging.pravaha.security.AuditSink.NONE, TRADE);
                PravahaFlightServer guarded = new PravahaFlightServer(views)
                        // The server carries its OWN policy, separate from the registry's. Passing
                        // it to only one of the two is how a read ends up authorized by a different
                        // rule than a registration -- worth knowing when writing a deployment.
                        .authorizedBy(conditional, com.ash.messaging.pravaha.security.AuditSink.NONE)
                        .hosting(filtered)
                        .start("localhost", 0);
                FlightClient client = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", guarded.port()))
                        .build()) {

            client.doAction(new Action(
                            ControlWire.REGISTER,
                            ControlWire.encode("conditional", "SELECT trade_id, product_type FROM trade", "0")))
                    .forEachRemaining(result -> {});

            // The leak this closes: the subscribe path read the AccessDecision, checked `allowed()`,
            // and threw the row filter away -- so a principal entitled to swaps received every
            // trade. A subscription has no plan to AND a predicate into; it delivers each change as
            // the view commits it. So it fails closed and says why, rather than over-serving in
            // silence. Reading the same view still works, with the predicate applied.
            assertThatThrownBy(() -> client.getStream(new org.apache.arrow.flight.Ticket(
                                    ControlWire.subscribeTicket("conditional", List.of())))
                            .next())
                    .hasMessageContaining("PRV-7002")
                    .hasMessageContaining("cannot enforce a filter");
        }
    }

    @Test
    void aServerWithoutARegistrySaysSoRatherThanDoingNothing() throws Exception {
        try (PravahaFlightServer plain = new PravahaFlightServer(new ViewCatalog()).start("localhost", 0);
                FlightClient other = FlightClient.builder(
                                allocator, Location.forGrpcInsecure("localhost", plain.port()))
                        .build()) {

            assertThatThrownBy(() -> other.doAction(
                                    new Action(ControlWire.REGISTER, ControlWire.encode("a", "SELECT 1", "0")))
                            .forEachRemaining(result -> {}))
                    .isInstanceOf(FlightRuntimeException.class)
                    .hasMessageContaining("does not host a registry");
        }
    }
}

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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A running subscription re-proving that it may still be running.
 *
 * <p>Authorization was checked once, when the subscription opened, and then it delivered for as long
 * as the client kept the socket. Revoking a credential mid-stream did nothing: a row committed ten
 * seconds after revocation still arrived, while a <em>fresh</em> subscribe was correctly refused.
 * The exposure had no bound -- an open subscription outlived the credential that authorised it for
 * as long as the connection stayed up, which is a caller who is no longer authenticated still
 * receiving data.
 *
 * <p>Both halves are tested because they fail differently: a credential can be revoked or expire,
 * and a policy can stop allowing a principal whose credential is still perfectly good.
 */
@Timeout(120)
class SubscriptionRevocationTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private com.ash.messaging.pravaha.registry.QueryRegistry registry;

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    /** Starts a server whose credential and policy can both be withdrawn while a stream runs. */
    private void startWith(TokenVerifier verifier, SecurityPolicy policy) {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        // A hosted registry, because subscribing is a registry verb: a server with only a view
        // catalogue answers PRV-6101 and the stream never starts, which is what the first draft of
        // this test actually measured.
        registry = new com.ash.messaging.pravaha.registry.QueryRegistry(views, policy, AuditSink.NONE, SCHEMA);
        server = new PravahaFlightServer(views, allocator)
                .hosting(registry)
                .authenticatedBy(verifier)
                .authorizedBy(policy, AuditSink.NONE)
                .start("localhost", 0);
        // Registered as the principal the verifier issues, not anonymously: the default policy
        // refuses anonymous registration, which is correct and is not what this test is about.
        registry.register(
                "user_volume",
                "SELECT user_id, total FROM user_volume",
                List.of(0),
                new Principal("dana", "acme", Set.of("analyst"), Map.of()));
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    /**
     * Opens a subscription on its own thread and reports how it ended.
     *
     * <p>The assertion is that it ends at all. Before this, it did not: it went on delivering, and
     * the only thing that stopped it was the client choosing to disconnect.
     */
    private AtomicReference<String> subscribeUntilItEnds(String token) {
        AtomicReference<String> ended = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            var options = new org.apache.arrow.flight.CallOption[] {
                new org.apache.arrow.flight.HeaderCallOption(new org.apache.arrow.flight.FlightCallHeaders() {
                    {
                        insert("authorization", "Bearer " + token);
                    }
                })
            };
            try (FlightStream stream =
                    client.getStream(new Ticket(ControlWire.subscribeTicket("user_volume", List.of())), options)) {
                while (stream.next()) {
                    // Draining. What matters is how the loop exits.
                }
                ended.set("completed");
            } catch (Exception e) {
                // Whatever ends it, the message is the evidence. Before this fix nothing ended it
                // at all: the loop ran until the client disconnected.
                ended.set(String.valueOf(e.getMessage()));
            }
        });
        // No setDaemon: a virtual thread is already one, and setting it after start throws.
        return ended;
    }

    private static boolean endedWithin(AtomicReference<String> ended, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (ended.get() != null) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void revokingACredentialEndsASubscriptionThatIsAlreadyRunning() throws Exception {
        AtomicBoolean revoked = new AtomicBoolean();
        TokenVerifier revocable = token -> {
            if (revoked.get()) {
                throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "the credential presented was not accepted");
            }
            return new Principal("dana", "acme", Set.of("analyst"), Map.of());
        };
        startWith(revocable, (principal, view) -> AccessDecision.allow());

        AtomicReference<String> ended = subscribeUntilItEnds("good-token");
        Thread.sleep(500);
        assertThat(ended.get())
                .as("it is running before the credential is withdrawn")
                .isNull();

        revoked.set(true);

        assertThat(endedWithin(ended, 30))
                .as("a revoked credential ends the stream rather than going on feeding it")
                .isTrue();
        assertThat(ended.get()).contains(SecurityErrors.UNAUTHENTICATED.code());
    }

    /**
     * FLIGHTPRINCIPAL-1: a credential that still verifies, but now as a different principal, is not
     * the credential the subscription was authorised with -- the same rule the PostgreSQL gateway
     * applies (PGREVOKE-1). Only the id changes here: the policy allows everyone, so nothing but the
     * principal comparison can end the stream.
     */
    @Test
    void aCredentialThatNowVerifiesAsSomebodyElseEndsASubscription() throws Exception {
        AtomicBoolean swapped = new AtomicBoolean();
        java.util.concurrent.CountDownLatch openedAsDana = new java.util.concurrent.CountDownLatch(1);
        TokenVerifier shifting = token -> {
            if (swapped.get()) {
                return new Principal("mallory", "acme", Set.of("analyst"), Map.of());
            }
            openedAsDana.countDown();
            return new Principal("dana", "acme", Set.of("analyst"), Map.of());
        };
        startWith(shifting, (principal, view) -> AccessDecision.allow());

        AtomicReference<String> ended = subscribeUntilItEnds("good-token");
        // The swap must come after the subscription opened as dana: under a loaded build the
        // subscribe can start more than half a second late, open as mallory, and then rightly never
        // end -- the principal it was authorised as has not changed.
        assertThat(openedAsDana.await(30, TimeUnit.SECONDS))
                .as("the subscription opened")
                .isTrue();
        Thread.sleep(500);
        assertThat(ended.get()).as("running as dana").isNull();

        swapped.set(true);

        assertThat(endedWithin(ended, 30))
                .as("a credential now standing for somebody else ends the stream")
                .isTrue();
        assertThat(ended.get()).contains(SecurityErrors.UNAUTHENTICATED.code());
    }

    @Test
    void withdrawingAccessInThePolicyEndsASubscriptionThatIsAlreadyRunning() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        TokenVerifier constant = token -> new Principal("dana", "acme", Set.of("analyst"), Map.of());
        startWith(
                constant,
                (principal, view) ->
                        allowed.get() ? AccessDecision.allow() : AccessDecision.deny("entitlement withdrawn"));

        AtomicReference<String> ended = subscribeUntilItEnds("good-token");
        Thread.sleep(500);
        assertThat(ended.get()).isNull();

        allowed.set(false);

        assertThat(endedWithin(ended, 30))
                .as("a principal whose entitlement is withdrawn stops receiving rows")
                .isTrue();
        assertThat(ended.get()).contains("entitlement withdrawn");
    }
}

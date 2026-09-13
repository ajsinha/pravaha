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
package com.ash.messaging.pravaha.registry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registering SQL as a computation with a name, a state and an end (ADR-025).
 *
 * <p>The behaviour that matters most here is sharing. Two people asking the same question must get
 * one computation and one copy of the state, and neither must be able to take the answer away from
 * the other by dropping their own name -- because neither knows the other exists.
 */
class QueryRegistryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("status", Types.string())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @Test
    void aPrincipalCannotRegisterOverAStreamTheyMayNotRead() {
        // The bypass this closes: registration asked only whether somebody may register
        // *anything*, never whether they may read what the query names. So a principal who
        // could register could name a stream they had no access to, give the view a name of
        // their own choosing, and read it back -- because the read check is against the view's
        // name and the policy was never told what the view derives from.
        SecurityPolicy noTxn = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return "txn".equals(view)
                        ? AccessDecision.deny("not cleared for transaction data")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };

        try (QueryRegistry guarded = new QueryRegistry(new ViewCatalog(), noTxn, AuditSink.NONE, TXN)) {
            assertThatThrownBy(() -> guarded.register("laundered", "SELECT user_id, amount FROM txn", List.of(0), DANA))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-7002")
                    .hasMessageContaining("which they may not read")
                    .hasMessageContaining("standing read");
        }
    }

    @Test
    void principalsWithDifferentRowFiltersDoNotShareOneComputation() {
        // Sharing is by fingerprint, and the fingerprint used to ignore row filters entirely --
        // so a principal restricted to one slice and a principal restricted to none produced the
        // same fingerprint and shared one computation with one copy of the state. The read path
        // was the only thing between that and the restricted principal seeing everything.
        SecurityPolicy perPrincipal = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return "dana".equals(principal.id())
                        ? AccessDecision.allowWithRowFilter("status = 'SETTLED'")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        Principal rob = new Principal("rob", "acme", Set.of("analyst"), Map.of());

        try (QueryRegistry guarded = new QueryRegistry(new ViewCatalog(), perPrincipal, AuditSink.NONE, TXN)) {
            String sql = "SELECT user_id, amount, status FROM txn";
            RegisteredQuery restricted = guarded.register("dana_view", sql, List.of(0), DANA);
            RegisteredQuery unrestricted = guarded.register("rob_view", sql, List.of(0), rob);

            assertThat(restricted.fingerprint())
                    .as("a filtered principal and an unfiltered one must not share state")
                    .isNotEqualTo(unrestricted.fingerprint());
            assertThat(restricted.names()).containsExactly("dana_view");
            assertThat(unrestricted.names()).containsExactly("rob_view");
        }
    }

    @Test
    void thesameFilterStillShares() {
        // The other half: sharing must still happen when the entitlements match, or the claim
        // that ten analysts asking one question cost one computation stops being true the moment
        // a row filter exists.
        SecurityPolicy sameForAll = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allowWithRowFilter("status = 'SETTLED'");
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        Principal rob = new Principal("rob", "acme", Set.of("analyst"), Map.of());

        try (QueryRegistry shared = new QueryRegistry(new ViewCatalog(), sameForAll, AuditSink.NONE, TXN)) {
            String sql = "SELECT user_id, amount, status FROM txn";
            RegisteredQuery first = shared.register("a", sql, List.of(0), DANA);
            RegisteredQuery second = shared.register("b", sql, List.of(0), rob);

            assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
            assertThat(second.names()).containsExactlyInAnyOrder("a", "b");
        }
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    /** Feeds one transaction into a registered query. */
    private void feed(RegisteredQuery query, String user, long amount, String status) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.setString(2, status);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        // The engine applies rows on the lane's thread, so a test that fed and then read would be
        // racing it rather than testing it. Live ingest needs none of this; a definite answer at a
        // definite moment does.
        query.awaitApplied(java.time.Duration.ofSeconds(10));
    }

    @Test
    void aRegisteredQueryMaintainsAViewThatCanBeRead() {
        RegisteredQuery query = registry.register(
                "completed", "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'", List.of(0), DANA);

        feed(query, "u1", 300L, "COMPLETED");
        feed(query, "u2", 50L, "PENDING");
        query.commit();

        // Registered, running, and readable through the ordinary query path by its name.
        assertThat(query.state()).isEqualTo(QueryState.RUNNING);
        assertThat(new ViewQuery(views)
                        .execute("SELECT user_id, amount FROM completed")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void theSameQuestionAskedTwiceIsOneComputation() {
        RegisteredQuery first = registry.register("a", "SELECT user_id FROM txn WHERE amount > 10", List.of(0), DANA);
        // Different text, different alias, same normalised plan.
        RegisteredQuery second =
                registry.register("b", "SELECT t.user_id FROM txn AS t WHERE t.amount > 10", List.of(0), DANA);

        assertThat(second).isSameAs(first);
        assertThat(registry.size()).as("one computation").isEqualTo(1);
        assertThat(registry.names()).containsExactly("a", "b");
        assertThat(first.names()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void differentQuestionsAreDifferentComputations() {
        registry.register("a", "SELECT user_id FROM txn WHERE amount > 10", List.of(0), DANA);
        registry.register("b", "SELECT user_id FROM txn WHERE amount > 11", List.of(0), DANA);

        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void droppingOneNameDoesNotTakeTheAnswerFromTheOther() {
        RegisteredQuery shared = registry.register("a", "SELECT user_id FROM txn WHERE amount > 10", List.of(0), DANA);
        registry.register("b", "SELECT user_id FROM txn WHERE amount > 10", List.of(0), DANA);

        registry.drop("a");

        // Neither registrant knows the other exists, so dropping on the first name would be an
        // outage caused by somebody tidying up their own query.
        assertThat(shared.state()).isEqualTo(QueryState.RUNNING);
        assertThat(registry.find("a")).isEmpty();
        assertThat(registry.find("b")).isPresent();

        registry.drop("b");
        assertThat(shared.state()).isEqualTo(QueryState.DROPPED);
    }

    @Test
    void aNameCannotBeQuietlyReused() {
        registry.register("a", "SELECT user_id FROM txn", List.of(0), DANA);

        assertThatThrownBy(() -> registry.register("a", "SELECT amount FROM txn", List.of(0), DANA))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8001");
    }

    @Test
    void aPausedQueryKeepsAnsweringAndStopsAdvancing() {
        RegisteredQuery query = registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        feed(query, "u1", 300L, "COMPLETED");
        query.commit();

        registry.pause("q");
        feed(query, "u2", 50L, "COMPLETED");
        query.commit();

        // The row fed while paused is not in the view, and the earlier answer still is. That is what
        // a pause promises: the answers stay available and stop moving.
        assertThat(query.state()).isEqualTo(QueryState.PAUSED);
        assertThat(new ViewQuery(views).execute("SELECT user_id FROM q").size()).isEqualTo(1);

        registry.resume("q");
        feed(query, "u3", 70L, "COMPLETED");
        query.commit();
        assertThat(new ViewQuery(views).execute("SELECT user_id FROM q").size()).isEqualTo(2);
    }

    @Test
    void aDroppedQueryCannotBePausedOrResumed() {
        registry.register("q", "SELECT user_id FROM txn", List.of(0), DANA);
        RegisteredQuery query = registry.require("q");
        registry.drop("q");

        assertThatThrownBy(query::pause).isInstanceOf(PravahaException.class).hasMessageContaining("PRV-8003");
        assertThatThrownBy(query::resume).isInstanceOf(PravahaException.class).hasMessageContaining("PRV-8003");
    }

    @Test
    void anUnknownNameIsRefusedWithTheNamesThatExist() {
        registry.register("q", "SELECT user_id FROM txn", List.of(0), DANA);

        assertThatThrownBy(() -> registry.require("nope"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8002")
                .hasMessageContaining("q");
    }

    @Test
    void registrationIsAuthorizedSeparatelyFromReading() {
        AuditSink.InMemory audit = new AuditSink.InMemory();
        QueryRegistry restricted = new QueryRegistry(views, (principal, view) -> AccessDecision.allow(), audit, TXN);

        // The default policy refuses an anonymous registration while allowing anonymous reads:
        // registering commits the node to work for as long as it runs, which is a different risk.
        assertThatThrownBy(() -> restricted.register("q", "SELECT user_id FROM txn", List.of(0), Principal.ANONYMOUS))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
        assertThat(audit.denials()).hasSize(1);
        restricted.close();
    }

    @Test
    void aRegistrationNeedsAKey() {
        assertThatThrownBy(() -> registry.register("q", "SELECT user_id FROM txn", List.of(), DANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("log");
    }

    @Test
    void aKeyColumnMustExistInTheOutput() {
        assertThatThrownBy(() -> registry.register("q", "SELECT user_id FROM txn", List.of(7), DANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the query's output");
    }

    @Test
    void aFingerprintIncludesTheSecurityPredicatesApplied() {
        RegisteredQuery query = registry.register("q", "SELECT user_id FROM txn", List.of(0), DANA);

        // Two principals with different entitlements must not share a computation. Asserted on the
        // fingerprint directly because that is the mechanism -- if it ever stopped distinguishing
        // them, sharing would start leaking rows and nothing else would notice.
        QueryFingerprint unfiltered = query.fingerprint();
        QueryFingerprint filtered = QueryFingerprint.of(
                new com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder()
                        .build(com.ash.messaging.pravaha.sql.SqlPlanner.withStreams(TXN)
                                .plan("SELECT user_id FROM txn")),
                List.of("tier = 'gold'"));

        assertThat(filtered).isNotEqualTo(unfiltered);
    }

    @Test
    void closingTheRegistryReleasesEverything() {
        registry.register("a", "SELECT user_id FROM txn", List.of(0), DANA);
        RegisteredQuery a = registry.require("a");

        registry.close();

        assertThat(a.state()).isEqualTo(QueryState.DROPPED);
        assertThat(registry.size()).isZero();
    }

    @Test
    void aSecondNameForOneComputationIsQueryableUnderThatName() {
        // Sharing is a documented user-facing feature and it made the second name useless: register
        // acknowledged RUNNING, `queries` listed it, and reading it answered "Object not found",
        // because the shared path skipped view registration entirely. A QA pass reported the second
        // name still unqueryable after the first fix, so this pins all three halves rather than the
        // one that was easiest to see.
        RegisteredQuery first = registry.register(
                "first_view", "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'", List.of(0), DANA);
        RegisteredQuery second = registry.register(
                "second_view", "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'", List.of(0), DANA);

        assertThat(second).as("the same question is one computation").isSameAs(first);

        feed(first, "u1", 300L, "COMPLETED");
        first.commit();

        assertThat(new ViewQuery(views)
                        .execute("SELECT user_id FROM first_view")
                        .size())
                .isEqualTo(1);
        assertThat(new ViewQuery(views)
                        .execute("SELECT user_id FROM second_view")
                        .size())
                .as("the second name must answer, not report itself missing")
                .isEqualTo(1);
    }

    @Test
    void droppingOneNameOfASharedComputationLeavesTheOtherAnswering() {
        registry.register("alpha", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        RegisteredQuery shared = registry.register("beta", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        feed(shared, "u1", 10L, "COMPLETED");
        shared.commit();

        registry.drop("alpha");

        // Removing a name must remove that name and nothing else. A dropped view has to stop
        // answering -- otherwise it serves whatever the closed computation last committed, for ever
        // -- but the computation is still held open by the other name.
        assertThat(new ViewQuery(views).execute("SELECT user_id FROM beta").size())
                .isEqualTo(1);
        assertThatThrownBy(() -> new ViewQuery(views).execute("SELECT user_id FROM alpha"))
                .isInstanceOf(PravahaException.class);
    }
}

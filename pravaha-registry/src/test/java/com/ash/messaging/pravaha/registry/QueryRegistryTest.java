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
                .hasMessageContaining("PRV-5001");
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

        assertThatThrownBy(query::pause).isInstanceOf(PravahaException.class).hasMessageContaining("PRV-5003");
        assertThatThrownBy(query::resume).isInstanceOf(PravahaException.class).hasMessageContaining("PRV-5003");
    }

    @Test
    void anUnknownNameIsRefusedWithTheNamesThatExist() {
        registry.register("q", "SELECT user_id FROM txn", List.of(0), DANA);

        assertThatThrownBy(() -> registry.require("nope"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5002")
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
}

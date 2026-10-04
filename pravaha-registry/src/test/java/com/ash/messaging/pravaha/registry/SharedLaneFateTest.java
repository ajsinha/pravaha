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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Sharing a lane shares its fate and its pace, and nothing wider (LANEFATE-1).
 *
 * <p>Two shared lanes and a lane of its own. A pipeline that throws on one shared lane takes down
 * the queries on that lane -- all of them, because they share its one thread -- and no query on the
 * other shared lane or on its own lane; those keep answering. A query on a shared lane that stops
 * making progress backs up that lane's one inbox, so its lane-mates stop being accepted too, while
 * the other lanes take rows as before; and once it moves again nothing was lost.
 */
@Timeout(120)
class SharedLaneFateTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN).multiplexingLanes(2, 10);
        arena = new RowArena(MemoryAccess.best(), 1 << 22, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    /** A count and a sum, filtered so that each is a computation of its own. */
    private RegisteredQuery count(String name, int floor) {
        return registry.register(
                name, "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount > " + floor, List.of(0), DANA);
    }

    private RegisteredQuery ownLane(String name, int floor) {
        new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(
                        ContinuousStatements.recognize("CREATE CONTINUOUS QUERY " + name
                                        + " KEYED BY (n) WITH (lane = 'dedicated') AS SELECT COUNT(*) AS n, "
                                        + "SUM(amount) AS total FROM txn WHERE amount > " + floor)
                                .orElseThrow(),
                        DANA);
        return registry.require(name);
    }

    @Test
    void aPipelineFailingOnASharedLaneTakesDownExactlyTheQueriesOnThatLane() {
        // Least loaded lane first, lowest index on a tie: 0, 1, 0, 1.
        RegisteredQuery doomed = registry.register("doomed", "SELECT MIN(amount) AS lo FROM txn", List.of(0), DANA);
        RegisteredQuery across = count("across", -100);
        RegisteredQuery mate = count("mate", -101);
        RegisteredQuery across2 = count("across2", -102);
        RegisteredQuery own = ownLane("own", -103);
        assertThat(registry.sharedLaneOf("doomed")).contains(0);
        assertThat(registry.sharedLaneOf("mate")).contains(0);
        assertThat(registry.sharedLaneOf("across")).contains(1);
        assertThat(registry.sharedLaneOf("across2")).contains(1);
        assertThat(registry.sharedLaneOf("own")).isEmpty();
        List<RegisteredQuery> survivors = List.of(across, across2, own);

        for (RegisteredQuery query : List.of(doomed, mate, across, across2, own)) {
            feed(query, "ann", 10, 1);
        }
        for (RegisteredQuery query : survivors) {
            assertThat(answer(query)).isEqualTo(List.of(1L, 10L));
        }

        // A MIN cannot take a retraction without the multiset it does not keep: the pipeline throws
        // on the lane's thread, and the lane with it.
        // Whether this call sees the failure is timing; the states below are what is asserted.
        var _ = catchThrowable(() -> feed(doomed, "ann", 10, -1));
        awaitState(doomed, QueryState.FAILED);
        // Its lane-mate is not fed anything: it fails because its lane did, not because it was touched.
        awaitState(mate, QueryState.FAILED);
        assertThat(mate.failure()).as("the lane-mate says why it stopped").isPresent();
        assertThat(mate.view().failure())
                .as("and its view refuses to serve the rows it holds as live (E-13)")
                .isPresent();

        for (RegisteredQuery query : survivors) {
            assertThat(query.state()).as(query.name()).isEqualTo(QueryState.RUNNING);
            feed(query, "cat", 30, 1);
            assertThat(answer(query)).as("%s keeps answering", query.name()).isEqualTo(List.of(2L, 40L));
        }

        // And a query registered now is not placed on the dead lane.
        RegisteredQuery late = count("late", -104);
        assertThat(registry.sharedLaneOf("late"))
                .as("the dead lane 0 was the least loaded by count, but a dead lane takes nothing")
                .contains(1);
        feed(late, "dan", 5, 1);
        assertThat(late.state()).isEqualTo(QueryState.RUNNING);
        assertThat(answer(late)).as("a query placed after the failure answers").isEqualTo(List.of(1L, 5L));
    }

    @Test
    void aStalledQueryOnASharedLaneBackpressuresItsLaneAndOnlyItsLane() throws Exception {
        // A projection, so every row reaches its view on the lane's thread as it is processed; an
        // aggregate's view is written when it publishes.
        RegisteredQuery stalled =
                registry.register("stalled", "SELECT amount, user_id FROM txn WHERE amount > -100", List.of(0), DANA);
        RegisteredQuery across = count("across", -101);
        RegisteredQuery mate = count("mate", -102);
        RegisteredQuery own = ownLane("own", -103);
        assertThat(registry.sharedLaneOf("stalled")).contains(0);
        assertThat(registry.sharedLaneOf("mate")).contains(0);
        assertThat(registry.sharedLaneOf("across")).contains(1);

        long accepted;
        long mateAccepted = 0;
        // Holding the stalled query's view is holding its pipeline: the lane thread applies each
        // batch to the view under that monitor, and waits for it.
        synchronized (stalled.view()) {
            accepted = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (offer(stalled, accepted) && System.nanoTime() < deadline) {
                accepted++;
            }
            assertThat(System.nanoTime()).as("the stalled lane's inbox filled").isLessThan(deadline);
            // Its lane-mate shares that inbox: refused too, for as long as the stall lasts.
            for (int i = 0; i < 50; i++) {
                if (offer(mate, i)) {
                    mateAccepted++;
                }
            }
            assertThat(mateAccepted)
                    .as("the lane-mate is backpressured with it")
                    .isZero();
            // The other shared lane and a lane of its own take rows and answer.
            for (RegisteredQuery query : List.of(across, own)) {
                for (int i = 1; i <= 1000; i++) {
                    assertThat(offer(query, i))
                            .as("%s accepts row %d", query.name(), i)
                            .isTrue();
                }
                query.awaitApplied(Duration.ofSeconds(10));
                query.commit();
                assertThat(answer(query)).as(query.name()).isEqualTo(List.of(1000L, 500_500L));
            }
            assertThat(stalled.state()).isEqualTo(QueryState.RUNNING);
        }

        // Released: the lane drains, and nothing it accepted was lost.
        assertThat(stalled.awaitApplied(Duration.ofSeconds(30))).isTrue();
        stalled.commit();
        assertThat((long) stalled.view().size())
                .as("every row the stalled query accepted, once")
                .isEqualTo(accepted);
        for (int i = 1; i <= 10; i++) {
            while (!offer(mate, i)) {
                Thread.onSpinWait();
            }
        }
        mate.awaitApplied(Duration.ofSeconds(10));
        mate.commit();
        assertThat(answer(mate)).isEqualTo(List.of(10L, 55L));
    }

    // ---------------------------------------------------------------------------------------

    private boolean offer(RegisteredQuery query, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, "u");
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private void feed(RegisteredQuery query, String user, long amount, long weight) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .as("%s accepts", query.name())
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }

    private static List<Long> answer(RegisteredQuery query) {
        for (Object[] row : query.view().scan()) {
            return List.of(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return List.of(0L, 0L);
    }

    private static void awaitState(RegisteredQuery query, QueryState state) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (query.state() != state) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(query.name() + " is " + query.state() + ", expected " + state);
            }
            Thread.onSpinWait();
        }
    }
}

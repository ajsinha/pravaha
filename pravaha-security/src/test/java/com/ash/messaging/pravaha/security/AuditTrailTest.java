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
package com.ash.messaging.pravaha.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The readable half of the audit trail: what it keeps, how it pages, and that it can never be the
 * reason a query fails.
 */
class AuditTrailTest {

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal EVE = new Principal("eve", "acme", Set.of("intern"), Map.of());

    private static AuditEvent at(long second, Principal who, String action, String target, boolean allowed) {
        return new AuditEvent(
                Instant.ofEpochSecond(second),
                who,
                action,
                target,
                allowed,
                allowed ? "allowed" : "not permitted",
                Optional.of("SELECT " + second));
    }

    @Test
    void everyEventReachesTheDurableSinkAsWellAsTheRing() {
        AuditSink.InMemory durable = new AuditSink.InMemory();
        AuditTrail trail = new AuditTrail(durable, "memory", 16);

        trail.record(at(1, DANA, "query", "orders", true));
        trail.record(at(2, EVE, "query", "payroll", false));

        assertThat(durable.events()).hasSize(2);
        assertThat(trail.read(AuditTrail.Filter.ANY, 10, 0).entries())
                .extracting(entry -> entry.event().target())
                .as("newest first")
                .containsExactly("payroll", "orders");
    }

    @Test
    void aDurableSinkThatThrowsDoesNotFailTheCallThatWasBeingAudited() {
        AuditTrail trail = new AuditTrail(
                event -> {
                    throw new IllegalStateException("the audit store is down");
                },
                "custom",
                8);

        assertThatCode(() -> trail.record(at(1, DANA, "query", "orders", true)))
                .as("an audit outage must not become a query outage")
                .doesNotThrowAnyException();
        assertThat(trail.delegateFailures()).isEqualTo(1);
        assertThat(trail.read(AuditTrail.Filter.ANY, 10, 0).entries())
                .as("and the decision is still readable while the durable sink is failing")
                .hasSize(1);
    }

    @Test
    void pagesWalkTheWholeTrailNewestFirstWithoutRepeatsOrGaps() {
        AuditTrail trail = new AuditTrail(AuditSink.NONE, "none", 100);
        for (int i = 1; i <= 25; i++) {
            trail.record(at(i, DANA, "query", "v" + i, true));
        }

        List<String> seen = new ArrayList<>();
        long cursor = 0;
        int pages = 0;
        do {
            AuditTrail.Page page = trail.read(AuditTrail.Filter.ANY, 10, cursor);
            page.entries().forEach(entry -> seen.add(entry.event().target()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != 0);

        assertThat(pages).isEqualTo(3);
        assertThat(seen)
                .hasSize(25)
                .doesNotHaveDuplicates()
                .startsWith("v25", "v24")
                .endsWith("v1");
    }

    @Test
    void aCursorStaysValidWhileNewDecisionsArrive() {
        AuditTrail trail = new AuditTrail(AuditSink.NONE, "none", 100);
        for (int i = 1; i <= 6; i++) {
            trail.record(at(i, DANA, "query", "v" + i, true));
        }
        AuditTrail.Page first = trail.read(AuditTrail.Filter.ANY, 3, 0);
        // Three more arrive between the two pages -- an offset would now re-serve v4..v6.
        for (int i = 7; i <= 9; i++) {
            trail.record(at(i, DANA, "query", "v" + i, true));
        }
        AuditTrail.Page second = trail.read(AuditTrail.Filter.ANY, 3, first.nextCursor());

        assertThat(second.entries()).extracting(entry -> entry.event().target()).containsExactly("v3", "v2", "v1");
        assertThat(second.nextCursor()).as("the last page says so").isZero();
    }

    @Test
    void filtersCombineAndPagingCountsOnlyMatches() {
        AuditTrail trail = new AuditTrail(AuditSink.NONE, "none", 100);
        trail.record(at(10, DANA, "query", "Payroll", true));
        trail.record(at(20, EVE, "query", "payroll", false));
        trail.record(at(30, EVE, "http.read", "payroll", false));
        trail.record(at(40, EVE, "query", "orders", true));

        assertThat(trail.read(new AuditTrail.Filter(null, null, "eve", "PAYROLL", null, false), 10, 0)
                        .entries())
                .extracting(entry -> entry.event().action())
                .containsExactly("http.read", "query");
        assertThat(trail.read(
                                new AuditTrail.Filter(
                                        Instant.ofEpochSecond(20),
                                        Instant.ofEpochSecond(40),
                                        null,
                                        null,
                                        "query",
                                        null),
                                10,
                                0)
                        .entries())
                .as("since is inclusive and until is exclusive")
                .extracting(entry -> entry.event().principal().id())
                .containsExactly("eve");
        assertThat(trail.read(AuditTrail.Filter.ANY, 10, 0).actions()).containsExactly("http.read", "query");
    }

    @Test
    void theRingIsBoundedAndSaysWhatItHasForgotten() {
        AuditTrail trail = new AuditTrail(AuditSink.NONE, "none", 4);
        for (int i = 1; i <= 10; i++) {
            trail.record(at(i, DANA, "query", "v" + i, true));
        }
        AuditTrail.Page page = trail.read(AuditTrail.Filter.ANY, 100, 0);

        assertThat(page.entries()).extracting(entry -> entry.event().target()).containsExactly("v10", "v9", "v8", "v7");
        assertThat(page.retained()).isEqualTo(4);
        assertThat(page.evicted()).isEqualTo(6);
        assertThat(page.oldest()).isEqualTo(Instant.ofEpochSecond(7));
    }

    @Test
    void aPageIsClampedSoOneRequestCannotCopyTheWholeRingOut() {
        AuditTrail trail = new AuditTrail(AuditSink.NONE, "none", 2_000);
        for (int i = 1; i <= 1_000; i++) {
            trail.record(at(i, DANA, "query", "v", true));
        }
        assertThat(trail.read(AuditTrail.Filter.ANY, 1_000_000, 0).entries()).hasSize(AuditTrail.MAX_PAGE);
        assertThat(trail.read(AuditTrail.Filter.ANY, -5, 0).entries()).hasSize(1);
    }

    @Test
    void anEmptyTrailIsAnEmptyPageNotAnError() {
        AuditTrail.Page page = new AuditTrail(AuditSink.NONE, "none", 4).read(AuditTrail.Filter.ANY, 10, 0);
        assertThat(page.entries()).isEmpty();
        assertThat(page.nextCursor()).isZero();
        assertThat(page.oldest()).isNull();
        assertThatThrownBy(() -> new AuditTrail(AuditSink.NONE, "none", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * SX-9. Appending past the limit was O(n) per event, and so was appending below it.
     *
     * <p>{@code InMemory} held a {@code CopyOnWriteArrayList} and called {@code events.remove(0)}
     * once the limit was reached: the copy-on-write array is copied whole on every {@code add},
     * and {@code remove(0)} shifts it again. Measured at a 10,000-event limit, the first 10,000
     * records took 144 ms and the next 10,000 took 498 ms — 3.5x slower for equal volume, arriving
     * exactly when a node is under the load that generates the most events to audit.
     *
     * <p><strong>A budget, not a ratio.</strong> A ratio between two short measurements on a
     * machine running several agents measures the machine. The numbers behind the budget were
     * taken directly, both implementations, same JVM, 200,000 appends: at a 1,000-event limit
     * copy-on-write took 268 ms and a deque 5 ms; at 10,000 it was 986 ms against 1 ms. So the
     * cost is linear in the limit for one and flat for the other, and 2,000,000 appends at the
     * default 10,000-event limit is about ten seconds against about thirty milliseconds. Three
     * seconds sits between them with two orders of magnitude of headroom on the side that passes
     * and a factor of three on the side that must fail, so load does not decide it.
     *
     * <p>One event instance, recorded many times, because allocating two million of them would
     * measure the allocator. Eviction order is asserted by the test below, with distinct events.
     */
    @Test
    void sx9_appendingFarPastTheLimitStaysCheap() {
        AuditSink.InMemory sink = new AuditSink.InMemory(10_000);
        AuditEvent one = at(1, DANA, "query", "orders", true);
        int appends = 2_000_000;

        long startNanos = System.nanoTime();
        for (int i = 0; i < appends; i++) {
            sink.record(one);
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        System.out.println("SX-9: " + appends + " appends past a 10,000-event limit in " + elapsedMillis + " ms");

        assertThat(elapsedMillis)
                .as(
                        "%d appends took %d ms; measured directly, the copy-on-write implementation this "
                                + "replaced needs about ten seconds for the same volume and this one about "
                                + "thirty milliseconds",
                        appends, elapsedMillis)
                .isLessThan(3_000L);
        assertThat(sink.events()).as("the limit is a limit").hasSize(10_000);
    }

    /** SX-9's correctness half: oldest-first eviction, in order, with the limit respected. */
    @Test
    void sx9_evictionDropsTheOldestAndKeepsTheRestInOrder() {
        AuditSink.InMemory sink = new AuditSink.InMemory(1_000);
        for (int i = 0; i < 20_000; i++) {
            sink.record(at(i, DANA, "query", "orders", true));
        }

        List<AuditEvent> kept = sink.events();
        assertThat(kept).hasSize(1_000);
        assertThat(kept.get(0).at())
                .as("the oldest kept event is the 19,000th recorded")
                .isEqualTo(Instant.ofEpochSecond(19_000));
        assertThat(kept.get(kept.size() - 1).at())
                .as("and the newest is the last recorded")
                .isEqualTo(Instant.ofEpochSecond(19_999));
        for (int i = 1; i < kept.size(); i++) {
            assertThat(kept.get(i).at())
                    .as("kept in the order they were recorded")
                    .isAfter(kept.get(i - 1).at());
        }
    }

    /** A sink that keeps nothing while reporting that it audits is refused by name. */
    @Test
    void sx9_aLimitOfZeroIsRefusedRatherThanSilentlyRecordingNothing() {
        assertThatThrownBy(() -> new AuditSink.InMemory(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one event");
    }

    @Test
    void readingTheAuditTrailIsItsOwnPermissionAndClosedByDefault() {
        // Everything readable is not the same as the trail being readable: a policy that lets a
        // principal read every view has still said nothing about who else read them.
        SecurityPolicy readsEverything = (principal, view) -> AccessDecision.allow();

        assertThat(readsEverything.mayRead(DANA, "payroll").allowed()).isTrue();
        assertThat(readsEverything.mayReadAudit(DANA).allowed()).isFalse();
        assertThat(SecurityPolicy.PERMISSIVE.mayReadAudit(Principal.ANONYMOUS).allowed())
                .as("PERMISSIVE already lets every caller read and drop everything, and says so")
                .isTrue();
    }
}

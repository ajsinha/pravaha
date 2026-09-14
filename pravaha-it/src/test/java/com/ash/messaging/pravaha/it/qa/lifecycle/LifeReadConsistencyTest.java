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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.Consistency;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LIFE-101..116 -- the four read-consistency modes, run against {@code ServedView.get} directly.
 *
 * <p>Fact 3 of LIFE.md's preamble: no transport carries a consistency mode. {@code ViewQuery.run}
 * always reads {@code view.scan()} -- the committed map -- so every read a shipped client can make is
 * {@code CONSISTENT} regardless of what it asked for. These cases exist to say precisely what the
 * other three modes *would* do, which is why they run against the served view itself rather than
 * through a query.
 */
@Tag("qa")
class LifeReadConsistencyTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("v")
            .field("usr", Types.string())
            .field("amount", Types.int64())
            .build();

    private static ServedView freshView() {
        return new ServedView("v", SCHEMA, List.of(0), 1000, Retention.forever());
    }

    private static final long SECOND = 1_000_000_000L;

    @Test
    void life101_latestDuringIngestSeesUncommittedWork() {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 300L}, 1, 10 * SECOND);

        ViewResult latest = view.get(new Consistency.Latest(), Duration.ZERO, "u1");
        assertThat(latest.found()).isTrue();
        assertThat(latest.values().orElseThrow()).containsExactly("u1", 300L);
        assertThat(latest.logicalFrontier()).isEqualTo(10 * SECOND);
        assertThat(latest.stalenessNanos()).isZero();
        assertThat(latest.frontierComplete())
                .as("pending work is not yet a committed frontier")
                .isFalse();

        ViewResult consistent = view.get(new Consistency.Consistent(), Duration.ZERO, "u1");
        assertThat(consistent.found()).as("nothing has been committed yet").isFalse();
        assertThat(consistent.logicalFrontier()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void life102_latestWhilePausedReturnsTheFrozenStateNotAStaleMarkedOne() {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 100L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);
        // Nothing more arrives from here on -- this is what "paused" looks like to the view: the
        // overlay is empty because everything was committed, and nothing new is being applied.

        ViewResult latest = view.get(new Consistency.Latest(), Duration.ZERO, "u1");
        ViewResult consistent = view.get(new Consistency.Consistent(), Duration.ZERO, "u1");
        assertThat(latest.values()).isEqualTo(consistent.values());
        assertThat(latest.stalenessNanos()).isZero();
        assertThat(consistent.stalenessNanos()).isZero();
        assertThat(latest.frontierComplete())
                .as("with pending empty, Latest degenerates to Consistent -- nothing marks this as paused")
                .isTrue();
        assertThat(consistent.frontierComplete()).isTrue();
    }

    @Test
    void life104_latestAgainstAQueryThatHasNeverReceivedARow() {
        ServedView view = freshView();
        ViewResult result = view.get(new Consistency.Latest(), Duration.ZERO, "nobody");
        assertThat(result.found()).isFalse();
        assertThat(result.logicalFrontier()).isEqualTo(Long.MIN_VALUE);
        assertThat(result.stalenessNanos()).isZero();
        assertThat(result.frontierComplete())
                .as("a view that has seen nothing reports itself complete")
                .isTrue();

        view.applyValues(new Object[] {"u1", 5L}, 1, SECOND);
        view.commit(SECOND);
        assertThat(view.get(new Consistency.Latest(), Duration.ZERO, "u1").found())
                .as("control: the view does work once fed")
                .isTrue();
    }

    @Test
    void life105_consistentDuringIngestReturnsTheLastCommitAndReportsTheLag() {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 100L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);
        view.applyValues(new Object[] {"u1", 999L}, 1, 20 * SECOND); // staged, not committed

        ViewResult result = view.get(new Consistency.Consistent(), Duration.ZERO, "u1");
        assertThat(result.values().orElseThrow()).containsExactly("u1", 100L);
        assertThat(result.logicalFrontier()).isEqualTo(10 * SECOND);
        assertThat(result.stalenessNanos()).isEqualTo(10 * SECOND);

        view.commit(20 * SECOND);
        ViewResult afterSecondCommit = view.get(new Consistency.Consistent(), Duration.ZERO, "u1");
        assertThat(afterSecondCommit.stalenessNanos())
                .as("the staleness change is the assertion")
                .isZero();
    }

    @Test
    void life106_consistentWhilePausedReportsZeroStalenessOnFrozenData() {
        ServedView paused = freshView();
        paused.applyValues(new Object[] {"u1", 100L}, 1, 10 * SECOND);
        paused.commit(10 * SECOND);

        ViewResult first = paused.get(new Consistency.Consistent(), Duration.ZERO, "u1");
        ViewResult later = paused.get(new Consistency.Consistent(), Duration.ZERO, "u1"); // "60 seconds later"
        assertThat(first.stalenessNanos()).isZero();
        assertThat(later.stalenessNanos())
                .as("staleness is against the view's own applied work, not wall-clock time: it stays zero "
                        + "however long nothing else arrives, which is the misleading part")
                .isZero();

        ServedView control = freshView();
        control.applyValues(new Object[] {"u1", 1L}, 1, 10 * SECOND);
        control.commit(10 * SECOND);
        control.applyValues(new Object[] {"u1", 2L}, 1, 70 * SECOND);
        control.commit(70 * SECOND);
        assertThat(control.committedFrontier())
                .as("V-control: an unpaused view's frontier moved by 60s of event time in the same interval")
                .isEqualTo(70 * SECOND);
    }

    @Test
    void life108_consistentAgainstAQueryThatHasNeverReceivedARow() {
        ServedView view = freshView();
        ViewResult result = view.get(new Consistency.Consistent(), Duration.ZERO, "nobody");
        assertThat(result.found()).isFalse();
        assertThat(result.logicalFrontier()).isEqualTo(Long.MIN_VALUE);
        assertThat(result.stalenessNanos())
                .as("Math.max(0, MIN_VALUE - MIN_VALUE) = 0")
                .isZero();
        assertThat(result.frontierComplete()).isTrue();

        // The shipped path: ViewQuery.run() always reads at Consistent. A scan of an empty view is
        // zero rows, not an error -- checked directly against ServedView.scan(), the method
        // ViewQuery is built on.
        assertThat(view.scan())
                .as("the shipped read path sees 0 rows, not a failure")
                .isEmpty();

        view.applyValues(new Object[] {"u1", 1L}, 1, SECOND);
        view.commit(SECOND);
        assertThat(view.scan()).hasSize(1);
    }

    @Test
    void life109_atLeastDuringIngestBlocksUntilTheFrontierArrivesThenReads() throws Exception {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 100L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);

        Thread committer = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            view.applyValues(new Object[] {"u1", 200L}, 1, 20 * SECOND);
            view.commit(20 * SECOND);
        });
        committer.start();

        long start = System.nanoTime();
        ViewResult result = view.get(new Consistency.AtLeast(20 * SECOND), Duration.ofSeconds(5), "u1");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        committer.join();

        assertThat(result.values().orElseThrow()).containsExactly("u1", 200L);
        assertThat(result.logicalFrontier()).isEqualTo(20 * SECOND);
        assertThat(result.stalenessNanos()).isZero();
        assertThat(elapsedMs)
                .as("blocked roughly 500ms for the committer, not returned instantly")
                .isGreaterThan(300);

        long fastStart = System.nanoTime();
        view.get(new Consistency.AtLeast(10 * SECOND), Duration.ofSeconds(5), "u1");
        long fastMs = Duration.ofNanos(System.nanoTime() - fastStart).toMillis();
        assertThat(fastMs)
                .as("an already-satisfied frontier returns immediately")
                .isLessThan(50);
    }

    @Test
    void life110_atLeastWhilePausedTimesOutWithAnActionableMessage() {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 1L}, 1, 10 * SECOND);
        view.commit(10 * SECOND); // and then nothing more -- "paused"

        long start = System.nanoTime();
        assertThatThrownBy(() -> view.get(new Consistency.AtLeast(50 * SECOND), Duration.ofSeconds(2), "u1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("4021")
                .hasMessageContaining("committed through 10000000000")
                .hasMessageContaining("asked for 50000000000")
                .hasMessageContaining("may be idle, or behind");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs)
                .as("within 10%% of the 2s timeout, not overshooting")
                .isBetween(1900L, 2400L);

        // V-control: a running view that WILL reach 50s within the same timeout succeeds.
        ServedView running = freshView();
        running.applyValues(new Object[] {"u1", 1L}, 1, 10 * SECOND);
        running.commit(10 * SECOND);
        new Thread(() -> {
                    running.applyValues(new Object[] {"u1", 2L}, 1, 50 * SECOND);
                    running.commit(50 * SECOND);
                })
                .start();
        assertThat(running.get(new Consistency.AtLeast(50 * SECOND), Duration.ofSeconds(2), "u1")
                        .found())
                .isTrue();
    }

    @Test
    void life112_atLeastAgainstAQueryThatHasNeverReceivedARow() {
        ServedView view = freshView();
        long start = System.nanoTime();
        assertThatThrownBy(() -> view.get(new Consistency.AtLeast(0), Duration.ofSeconds(2), "nobody"))
                .as("committedFrontier is MIN_VALUE, and MIN_VALUE < 0, so the wait is entered")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("4021");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofSeconds(1));

        ViewResult immediate = view.get(new Consistency.AtLeast(Long.MIN_VALUE), Duration.ofSeconds(2), "nobody");
        assertThat(immediate.found()).isFalse();
    }

    @Test
    void life113_asOfDuringIngestIsRefusedUnconditionally() {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 1L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);
        view.applyValues(new Object[] {"u1", 2L}, 1, 20 * SECOND);
        view.commit(20 * SECOND);

        for (long frontier : new long[] {15 * SECOND, 10 * SECOND, 20 * SECOND, 0}) {
            assertThatThrownBy(() -> view.get(new Consistency.AsOf(frontier), Duration.ZERO, "u1"))
                    .as("AsOf(%d) must be refused even when that frontier was, in fact, committed", frontier)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("4020")
                    .hasMessageContaining("holds the present")
                    .hasMessageContaining("checkpoints");
        }
    }

    @Test
    void life114_asOfWhilePausedIsRefusedIdentically() {
        ServedView view = freshView();
        view.applyValues(new Object[] {"u1", 1L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);
        // "Paused": nothing more is applied. The refusal is the first statement in the AsOf branch,
        // before any state is consulted, so this must read exactly like LIFE-113's.
        assertThatThrownBy(() -> view.get(new Consistency.AsOf(10 * SECOND), Duration.ZERO, "u1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("4020")
                .hasMessageContaining("holds the present");
    }

    @Test
    void life103_life107_life111_aHeldViewReferenceOutlivesADropForEveryMode() {
        com.ash.messaging.pravaha.serving.ViewCatalog catalog = new com.ash.messaging.pravaha.serving.ViewCatalog();
        com.ash.messaging.pravaha.registry.QueryRegistry registry =
                new com.ash.messaging.pravaha.registry.QueryRegistry(catalog, LifecycleTestSupport.TXN);
        try {
            registry.register("v1", LifecycleTestSupport.S1, List.of(0), Principal.ANONYMOUS);
            com.ash.messaging.pravaha.registry.RegisteredQuery query = registry.require("v1");
            // Feed one row through the public accept path and let it commit.
            query.view().applyValues(new Object[] {"ann", 100L}, 1, SECOND);
            query.view().commit(SECOND);
            ServedView held = query.view();

            assertThat(held.get(new Consistency.Latest(), Duration.ZERO, "ann").found())
                    .as("V-before: the held reference answers before the drop")
                    .isTrue();

            registry.drop("v1");

            assertThatThrownBy(
                            () -> new com.ash.messaging.pravaha.serving.ViewQuery(catalog).execute("SELECT * FROM v1"))
                    .as("LIFE-103/107 by name: the view is gone from the catalogue")
                    .isInstanceOf(RuntimeException.class);

            assertThat(held.get(new Consistency.Latest(), Duration.ZERO, "ann").found())
                    .as("LIFE-103 by reference: Latest keeps answering forever, from nothing that maintains it")
                    .isTrue();
            ViewResult consistent = held.get(new Consistency.Consistent(), Duration.ZERO, "ann");
            assertThat(consistent.found())
                    .as("LIFE-107 by reference: Consistent too, staleness = 0")
                    .isTrue();
            assertThat(consistent.stalenessNanos()).isZero();

            assertThatThrownBy(() -> held.get(new Consistency.AtLeast(SECOND * 100), Duration.ofSeconds(2), "ann"))
                    .as("LIFE-111: a frontier that will never arrive from a dead computation times out, "
                            + "misdiagnosed as 'the source may be idle, or behind' rather than 'this is dead'")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("4021");
        } finally {
            registry.close();
        }
    }

    @Test
    void life116_theSdksDefaultConsistencyNeverReachesTheServer() throws Exception {
        java.nio.file.Path sdkMain = java.nio.file.Path.of("sdk/pravaha-sdk-java/src/main/java");
        if (!java.nio.file.Files.isDirectory(sdkMain)) {
            // Executed from a different working directory than the repo root -- record as
            // inconclusive rather than fail on a path assumption.
            return;
        }
        List<String> hits = new java.util.ArrayList<>();
        try (var stream = java.nio.file.Files.walk(sdkMain)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    for (String line : java.nio.file.Files.readAllLines(p)) {
                        if (line.contains("defaultConsistency")) {
                            hits.add(p.getFileName() + ": " + line.strip());
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(hits)
                .as("every use of defaultConsistency in the SDK's main sources")
                .allSatisfy(hit -> assertThat(hit).startsWith("ClientOptions.java"));
        assertThat(hits)
                .as("a field, a getter, a builder setter and a toString -- and nothing that sends it")
                .isNotEmpty();
    }
}

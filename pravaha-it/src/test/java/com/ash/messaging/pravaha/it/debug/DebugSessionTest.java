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
package com.ash.messaging.pravaha.it.debug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.DebugSession;
import com.ash.messaging.pravaha.registry.DebugSessions;
import com.ash.messaging.pravaha.registry.DebugStep;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.runtime.exec.OperatorState;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.OWNER;
import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.PER_SECOND;
import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.SECOND;
import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.TOTAL;
import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The time-travel debugger's engine: fork, step, inspect, refuse (ADR-047, design section 16.4).
 *
 * <p>Every test here is about one of the four claims the feature makes, and each is stated as the
 * thing that would be wrong if it were not true:
 *
 * <ul>
 *   <li><strong>A fork does not disturb the live query.</strong> Its view does not move and its
 *       subscribers hear nothing, while the fork consumes rows the live query has not read.
 *   <li><strong>A step is a step.</strong> One row means one row, and the report names it, names
 *       what every operator did with it, and names what changed in the view.
 *   <li><strong>Inspection is a read.</strong> A page of an operator's state is bounded, pageable,
 *       and leaves the answer exactly as it was.
 *   <li><strong>A refusal names the thing.</strong> No checkpoint, no session, a source that
 *       cannot rewind, too many sessions, a query since dropped.
 * </ul>
 */
@Timeout(300)
final class DebugSessionTest {

    private static final List<Integer> ONE_KEY = List.of(0);

    private static final List<String> BEFORE =
            List.of(row("ann", 100, SECOND), row("bob", 250, SECOND), row("ann", 50, 2 * SECOND));

    private static final List<String> BEYOND = List.of(
            row("ann", 7, 3 * SECOND),
            row("bob", 11, 3 * SECOND),
            row("dan", 13, 4 * SECOND),
            row("ann", 17, 4 * SECOND),
            row("eve", 19, 5 * SECOND),
            row("dan", 23, 5 * SECOND));

    @Test
    void aForkConsumesRowsTheLiveQueryHasNotSeenAndTheLiveQueryDoesNotMove(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND)) {
            List<List<ViewChange>> heard = new ArrayList<>();
            AtomicInteger commits = new AtomicInteger();
            engine.query().subscribe(batch -> {
                heard.add(List.copyOf(batch));
                commits.incrementAndGet();
            });
            List<Object[]> liveBefore = engine.query().view().scan();
            assertThat(liveBefore)
                    .as("the live query counted the three rows it was given and was then paused")
                    .hasSize(1);
            assertThat(liveBefore.get(0)).containsExactly(3L, 400L);

            DebugSessions sessions = engine.registry().debugSessions();
            DebugSession.Status forked = sessions.fork("spend", engine.checkpoint(), OWNER);
            assertThat(forked.sinksDisabled()).isTrue();
            assertThat(forked.query()).isEqualTo("spend");
            assertThat(sessions.open()).isEqualTo(1);

            DebugStep step = sessions.step(forked.id(), DebugStep.Request.rows(6), OWNER);
            assertThat(step.rowsIn())
                    .as("the six rows appended past the checkpoint, which the paused query never read")
                    .hasSize(6);
            assertThat(sessions.view(forked.id(), OWNER).stream().map(ViewChange::values))
                    .as("the fork counted the checkpoint's three rows and the six it stepped")
                    .containsExactly(new Object[] {9L, 490L});

            // The whole point. Read after the fork has consumed everything.
            engine.query().commit();
            assertThat(engine.query().view().scan().get(0))
                    .as("the live query's view is exactly what it was: a fork writes into a view of its own")
                    .containsExactly(3L, 400L);
            assertThat(commits.get())
                    .as("and its subscriber heard nothing at all while the fork ran")
                    .isZero();
            assertThat(heard).isEmpty();
            assertThat(engine.views().find("debug:" + forked.id()))
                    .as("the fork's view is in no catalogue, so no reader can resolve a name to it")
                    .isEmpty();

            sessions.end(forked.id(), OWNER);
            assertThat(sessions.open()).isZero();
        }
    }

    @Test
    void aForkThatSharedTheLiveViewIsRefused() {
        // Seed-proof for the isolation above. The guard is the one thing in the fork path that is
        // about an object somebody could pass in by mistake rather than about something the fork
        // does not attach, so it is proven by making the mistake.
        ServedView live = new ServedView("spend", DebugTestSupport.TXN, ONE_KEY, 1000);
        assertThatThrownBy(() -> DebugSession.requireNotTheLiveView(live, live))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the live query's own view");
        ServedView fork = new ServedView("debug:dbg-1", DebugTestSupport.TXN, ONE_KEY, 1000);
        DebugSession.requireNotTheLiveView(live, fork);
    }

    @Test
    void oneRowIsOneRowAndTheStepSaysWhatEveryOperatorDidWithIt(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();

            DebugStep first = sessions.step(id, DebugStep.Request.row(), OWNER);
            assertThat(first.rowsIn()).hasSize(1);
            assertThat(first.rowsIn().get(0).stream()).isEqualTo("txn");
            assertThat(first.rowsIn().get(0).values()).startsWith("ann", "7");
            assertThat(first.rowsIn().get(0).weight()).isEqualTo(1);
            assertThat(first.rowsConsumed()).isEqualTo(1);
            assertThat(first.sequence()).isEqualTo(1);

            assertThat(first.operators())
                    .as("every edge of the plan, named, with what crossed it this step")
                    .isNotEmpty();
            DebugStep.Operator scan = first.operators().stream()
                    .filter(operator -> operator.kind().equals("scan"))
                    .findFirst()
                    .orElseThrow();
            assertThat(scan.label()).isEqualTo("txn");
            assertThat(scan.rowsOut()).as("one row entered the query").isEqualTo(1);

            assertThat(first.viewChanges())
                    .as("the total was (3, 400) and is now (4, 407): the old row withdrawn, the new inserted")
                    .hasSize(2);
            assertThat(first.viewChanges().stream()
                            .filter(ViewChange::isRetraction)
                            .count())
                    .isEqualTo(1);

            DebugStep second = sessions.step(id, DebugStep.Request.rows(2), OWNER);
            assertThat(second.rowsIn()).hasSize(2);
            assertThat(second.rowsConsumed()).isEqualTo(3);
            assertThat(second.sequence()).isEqualTo(2);

            DebugStep rest = sessions.step(id, DebugStep.Request.rows(100), OWNER);
            assertThat(rest.exhausted())
                    .as("the file has nine lines and the fork has now read all of them")
                    .isTrue();
            assertThat(rest.stopped()).contains("no more rows");
            sessions.end(id, OWNER);
        }
    }

    @Test
    void steppingToACommitAndToAWatermark(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, PER_SECOND, List.of(0, 2), BEFORE, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();

            // A windowed query publishes nothing until a window closes, so "to the next commit"
            // reads every remaining row and says so rather than pretending it found one.
            DebugStep toCommit = sessions.step(id, DebugStep.Request.toCommit(), OWNER);
            assertThat(toCommit.viewChanges()).isEmpty();
            assertThat(toCommit.exhausted()).isTrue();
            assertThat(toCommit.stopped()).contains("ran out before the view changed");

            DebugStep fired = sessions.step(id, DebugStep.Request.toWatermark(10 * SECOND), OWNER);
            assertThat(fired.rowsIn()).as("a watermark step consumes no rows").isEmpty();
            assertThat(fired.watermarkNanos()).hasValue(10 * SECOND);
            assertThat(fired.viewChanges())
                    .as("every window the watermark closed, published at once")
                    .isNotEmpty();

            assertThatThrownBy(() -> sessions.step(id, DebugStep.Request.toWatermark(SECOND), OWNER))
                    .as("event time does not go backwards, even in a debugger")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("does not go backwards");
            sessions.end(id, OWNER);
        }
    }

    @Test
    void steppingUntilAPredicateOverTheViewHolds(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();

            DebugStep already = sessions.step(id, DebugStep.Request.until("total", ">", "100"), OWNER);
            assertThat(already.stopped()).contains("total > 100");
            assertThat(already.rowsConsumed())
                    .as("the checkpoint's own total is already 400, so it holds before a row is read")
                    .isZero();

            DebugStep reached = sessions.step(id, DebugStep.Request.until("n", "=", "8"), OWNER);
            assertThat(reached.stopped()).contains("n = 8");
            assertThat(reached.rowsConsumed())
                    .as("three rows were in the checkpoint, so the eighth is the fifth one stepped")
                    .isEqualTo(5);

            assertThatThrownBy(() -> sessions.step(id, DebugStep.Request.until("nope", ">", "1"), OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("'nope' is not a column of this query's view");
            assertThatThrownBy(() -> sessions.step(id, DebugStep.Request.until("total", "~", "1"), OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("'~' is not a comparison");
            sessions.end(id, OWNER);
        }
    }

    @Test
    void anOperatorsStateIsReadableInPagesAndReadingItChangesNothing(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, PER_SECOND, List.of(0, 2), BEFORE, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();
            sessions.step(id, DebugStep.Request.rows(6), OWNER);
            sessions.step(id, DebugStep.Request.toWatermark(6 * SECOND), OWNER);

            List<OperatorState.Slot> slots = sessions.state(id, OWNER);
            assertThat(slots.stream().map(OperatorState.Slot::id))
                    .as("a tumbling aggregate is the only state this plan holds")
                    .containsExactly("window#0");
            assertThat(slots.get(0).entries())
                    .as("windows retained after firing")
                    .isPositive();

            OperatorState.Page firstPage = sessions.inspect(id, "window#0", null, 0, 2, OWNER);
            assertThat(firstPage.entries()).hasSize(2);
            assertThat(firstPage.total()).isGreaterThan(2);
            assertThat(firstPage.hasMore()).isTrue();
            assertThat(firstPage.entries().get(0).values())
                    .as("a window's contents: its bounds, its key and its accumulators")
                    .containsKeys("window_start", "window_end", "key0", "total");

            OperatorState.Page secondPage = sessions.inspect(id, "window#0", null, 2, 2, OWNER);
            assertThat(secondPage.entries()).doesNotContainAnyElementsOf(firstPage.entries());

            OperatorState.Page again = sessions.inspect(id, "window#0", null, 0, 2, OWNER);
            assertThat(again.entries())
                    .as("the same page twice: a page of an unordered collection is not a page")
                    .isEqualTo(firstPage.entries());

            List<ViewChange> before = sessions.view(id, OWNER);
            sessions.inspect(id, "window#0", null, 0, 50, OWNER);
            sessions.inspect(id, "window#0", null, 0, 50, OWNER);
            assertThat(sessions.view(id, OWNER))
                    .as("inspecting a window must not fire it: looking at a query cannot change its answer")
                    .containsExactlyInAnyOrderElementsOf(before);

            assertThatThrownBy(() -> sessions.inspect(id, "window#0", null, 0, 100_000, OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("page through the rest");
            assertThatThrownBy(() -> sessions.inspect(id, "join#0.left", null, 0, 10, OWNER))
                    .as("a join this plan does not have, refused by count rather than by an empty page")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("is out of range: this query has 0 of them");
            assertThatThrownBy(() -> sessions.inspect(id, "nonsense", null, 0, 10, OWNER))
                    .as("and a name that is not a kind of state at all, listing the ones there are")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("is not a piece of state in this query")
                    .hasMessageContaining("window#0");
            sessions.end(id, OWNER);
        }
    }

    @Test
    void anUnwindowedAggregatesAccumulatorsAreReadable(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();
            sessions.step(id, DebugStep.Request.rows(2), OWNER);

            assertThat(sessions.state(id, OWNER).stream().map(OperatorState.Slot::id))
                    .containsExactly("global#0");
            OperatorState.Page page = sessions.inspect(id, "global#0", null, 0, 10, OWNER);
            assertThat(page.entries()).hasSize(1);
            assertThat(page.entries().get(0).values())
                    .as("the accumulators, named as the query named them")
                    .containsEntry("rows", "5")
                    .containsEntry("n", "5")
                    .containsEntry("total", "418");
            sessions.end(id, OWNER);
        }
    }

    @Test
    void whatCannotBeForkedIsRefusedByName(@TempDir Path dir) throws Exception {
        try (QueryRegistry bare = new QueryRegistry(new ViewCatalog(), DebugTestSupport.TXN)) {
            bare.register("nowhere", TOTAL, ONE_KEY, OWNER);
            assertThatThrownBy(() -> bare.debugSessions().fork("nowhere", null, OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8011")
                    .hasMessageContaining("is not being checkpointed");

            assertThatThrownBy(() -> bare.debugSessions().fork("absent", null, OWNER))
                    .as("a name nothing answers to, refused as the registry refuses every other verb")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8002");
        }

        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND)) {
            DebugSessions sessions = engine.registry().debugSessions();

            assertThatThrownBy(() -> sessions.fork("spend", 999_999L, OWNER))
                    .as("a checkpoint id that is not retained, refused with the ones that are")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8011")
                    .hasMessageContaining("has no retained checkpoint 999999");

            assertThatThrownBy(() -> sessions.step("dbg-nothing", DebugStep.Request.row(), OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8013")
                    .hasMessageContaining("there is no debug session 'dbg-nothing'");

            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();
            engine.registry().drop("spend");
            assertThatThrownBy(() -> sessions.step(id, DebugStep.Request.row(), OWNER))
                    .as("the query went away underneath the session, and a step would describe nothing")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8016")
                    .hasMessageContaining("has been dropped since session");
        }
    }

    @Test
    void aSourceThatCannotBeRewoundIsRefusedByName(@TempDir Path dir) throws Exception {
        // Nothing bound: rows would be pushed in by an embedder, so there is no position to rewind
        // to. The refusal says that rather than reporting an empty replay as an exhausted one.
        Configuration settings = Configuration.builder()
                .set("pravaha.checkpoint.interval", "100ms")
                .build();
        try (QueryRegistry unbound = new QueryRegistry(new ViewCatalog(), DebugTestSupport.TXN)
                .checkpointingTo(Files.createDirectories(dir.resolve("cp")), settings)) {
            unbound.register("pushed", TOTAL, ONE_KEY, OWNER);
            DebugTestSupport.await(
                    () -> !unbound.debugSessions()
                            .checkpointsOf("pushed", OWNER)
                            .isEmpty(),
                    "a checkpoint of a query nothing feeds");
            assertThatThrownBy(() -> unbound.debugSessions().fork("pushed", null, OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8012")
                    .hasMessageContaining("pushed in by its embedder");
        }
    }

    @Test
    void aNodeHoldsOnlyAsManySessionsAsItIsAllowed(@TempDir Path dir) throws Exception {
        Configuration settings = Configuration.builder()
                .set("pravaha.checkpoint.interval", "200ms")
                .set("pravaha.checkpoint.keep", "5")
                .set("pravaha.debug.sessions.max", "2")
                .build();
        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND, settings)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String one = sessions.fork("spend", engine.checkpoint(), OWNER).id();
            sessions.fork("spend", engine.checkpoint(), OWNER);
            assertThat(sessions.open()).isEqualTo(2);

            assertThatThrownBy(() -> sessions.fork("spend", engine.checkpoint(), OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8014")
                    .hasMessageContaining("pravaha.debug.sessions.max");

            sessions.end(one, OWNER);
            assertThat(sessions.open()).isEqualTo(1);
            sessions.fork("spend", engine.checkpoint(), OWNER);
            assertThat(sessions.open()).isEqualTo(2);
        }
    }

    @Test
    void aSessionIsReleasedWhenNobodyHasTouchedItForItsLifetime(@TempDir Path dir) throws Exception {
        Configuration settings = Configuration.builder()
                .set("pravaha.checkpoint.interval", "200ms")
                .set("pravaha.checkpoint.keep", "5")
                .set("pravaha.debug.session.ttl", "1ms")
                .build();
        try (DebugTestSupport engine = new DebugTestSupport(dir, TOTAL, ONE_KEY, BEFORE, BEYOND, settings)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();
            Thread.sleep(20);
            assertThat(sessions.all(OWNER))
                    .as("expired on the way in to the next call")
                    .isEmpty();
            assertThatThrownBy(() -> sessions.step(id, DebugStep.Request.row(), OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8013")
                    .hasMessageContaining("it expired");
        }
    }

    @Test
    void openingASessionNeedsTheAdministerPermissionOnTheName(@TempDir Path dir) throws Exception {
        // Administer, not read. A fork exposes the query's SQL, its input rows and its operator
        // state, which is more than reading its view exposes -- so a principal who may read it and
        // not administer it must not be able to open one.
        SecurityPolicy readOnly = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayAdminister(Principal principal, String view) {
                return OWNER.id().equals(principal.id())
                        ? AccessDecision.allow()
                        : AccessDecision.deny("reading is not administering");
            }
        };
        Configuration settings = Configuration.builder()
                .set("pravaha.checkpoint.interval", "100ms")
                .build();
        try (QueryRegistry registry = new QueryRegistry(
                        new ViewCatalog(), readOnly, AuditSink.NONE, DebugTestSupport.TXN)
                .checkpointingTo(Files.createDirectories(dir.resolve("cp")), settings)) {
            registry.register("spend", TOTAL, ONE_KEY, OWNER);
            DebugTestSupport.await(
                    () -> !registry.debugSessions()
                            .checkpointsOf("spend", OWNER)
                            .isEmpty(),
                    "a checkpoint to fork from");
            Principal reader = Principal.of("reader");
            assertThatThrownBy(() -> registry.debugSessions().fork("spend", null, reader))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("may not");
            assertThat(registry.debugSessions().all(reader))
                    .as("a listing is filtered by what the caller may administer, never refused")
                    .isEmpty();
        }
    }
}

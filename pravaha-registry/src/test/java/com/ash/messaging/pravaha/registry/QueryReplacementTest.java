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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blue/green replacement, from the backfill to the rollback (ADR-046, design section 16.3).
 *
 * <p>The assertion that matters, and the one every other case here is a variation of: after the
 * backfill, the splice and the cutover, the name's view is <strong>exactly</strong> the view a
 * registration of the new SQL from scratch over the same history would hold. Not approximately,
 * and not "the right shape" -- a replacement that loses a record or counts one twice produces a
 * view that is still a plausible answer, and nothing downstream can tell.
 *
 * <p>The source here is a {@link ReplayableLog}: an append-only list whose positions name records,
 * which is the whole of what this needs of a source and what every replayable plugin provides.
 */
class QueryReplacementTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    /**
     * A running total over everything the stream has ever carried, in one row.
     *
     * <p>Accumulating on purpose: a projection's view would be the same whether or not the history
     * had been read, so it could not tell a working backfill from one that read nothing.
     */
    private static final String V1 = "SELECT 'all' AS bucket, SUM(amount) AS total FROM txn";

    private static final String V2 = "SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn";

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    private QueryRegistry registry(ReplayableLog log) {
        return registry(log, SecurityPolicy.PERMISSIVE);
    }

    private QueryRegistry registry(ReplayableLog log, SecurityPolicy policy) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), policy, AuditSink.NONE, TXN).feedingFrom(log);
        registries.add(registry);
        return registry;
    }

    private static ReplayableLog history(int rows) {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < rows; i++) {
            log.append("u" + (i % 3), (long) (i + 1));
        }
        return log;
    }

    @Test
    void afterTheBackfillTheSpliceAndTheCutoverTheViewIsTheOneARegistrationFromScratchWouldHold() {
        ReplayableLog log = history(60);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        QueryReplacement.Status started =
                registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        assertThat(started.state()).isIn(QueryReplacement.State.BACKFILLING, QueryReplacement.State.CAUGHT_UP);
        assertThat(started.replacing()).isNotEqualTo(started.candidate());

        // More rows arrive while the backfill runs: they are after the seam, so the candidate reads
        // them from the live stream and the running version reads them too.
        for (int i = 0; i < 30; i++) {
            log.append("u" + (i % 3), 100L + i);
        }
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);

        assertThat(registry.replacements().of("orders").orElseThrow().state())
                .isEqualTo(QueryReplacement.State.CUT_OVER);
        assertThat(registry.find("orders").orElseThrow().sql()).isEqualTo(V2);

        // The comparison: the same SQL, registered from scratch over the same log in a registry of
        // its own, which has read every record once and spliced nothing.
        QueryRegistry fresh = registry(log);
        fresh.register("reference", V2, List.of(0), DANA);
        awaitRows(fresh, "reference", 1);
        awaitSameRows(registry, "orders", fresh, "reference");
    }

    @Test
    void backfillNoneStartsWhereTheRunningVersionIsAndSaysSoRatherThanPretending() {
        ReplayableLog log = history(30);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        registry.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withBackfill(ReplacementOptions.Backfill.NONE));
        awaitCaughtUp(registry, "orders");
        QueryReplacement.Status status = registry.replacements().of("orders").orElseThrow();
        assertThat(status.progress().historyRows())
                .as("nothing was replayed: that is what backfill = none means")
                .isZero();

        log.append("u9", 500L);
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);

        // The new version holds only what arrived after the seam -- it says nothing about the
        // history, which is exactly the trade an operator makes by asking for no backfill.
        awaitRows(registry, "orders", 1);
        await(() -> {
            List<Object[]> rows = registry.find("orders").orElseThrow().view().scan();
            return rows.size() == 1 && Long.valueOf(500L).equals(rows.get(0)[1]);
        });
        List<Object[]> rows = registry.find("orders").orElseThrow().view().scan();
        assertThat(rows.get(0)[2])
                .as("one payment since the seam, and nothing before it")
                .isEqualTo(1L);
    }

    @Test
    void aCutoverBeforeTheCandidateHasCaughtUpIsRefusedRatherThanLeavingAGap() {
        ReplayableLog log = history(200);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        // One record a second: the backfill will not finish inside this test, which is the point.
        registry.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withRateLimit(1));
        assertThatThrownBy(() -> registry.replacements().cutOver("orders", DANA))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4014");

        // And the name still answers the version it always did.
        assertThat(registry.find("orders").orElseThrow().sql()).isEqualTo(V1);
        registry.replacements().abandon("orders", DANA);
        assertThat(registry.replacements().of("orders").orElseThrow().state())
                .isEqualTo(QueryReplacement.State.ABANDONED);
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void everySubscriberIsToldTheViewWasReplacedRatherThanHandedTheOtherQuerysChanges() {
        ReplayableLog log = history(12);
        QueryRegistry registry = registry(log);
        RegisteredQuery v1 = registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        List<ViewChange> plain = new CopyOnWriteArrayList<>();
        Subscription plainSubscription = v1.subscribe(plain::addAll);
        List<ViewChange> snapshotRows = new CopyOnWriteArrayList<>();
        AtomicInteger snapshots = new AtomicInteger();
        Subscription snapshotSubscription = v1.subscribeFromSnapshot(new SubscriptionListener() {
            @Override
            public void onSnapshot(List<ViewChange> rows, long frontier) {
                snapshots.incrementAndGet();
                snapshotRows.addAll(rows);
            }

            @Override
            public void onCommit(@Nullable List<ViewChange> changes, long frontier) {
                snapshotRows.addAll(changes);
            }
        });
        await(() -> snapshots.get() == 1);

        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);

        assertThat(plainSubscription.isClosed()).isTrue();
        assertThat(plainSubscription.failure())
                .hasValueSatisfying(failure -> assertThat(failure.getMessage()).contains("PRV-4019"));
        assertThat(snapshotSubscription.isClosed()).isTrue();
        assertThat(snapshotSubscription.failure())
                .hasValueSatisfying(failure -> assertThat(failure.getMessage()).contains("PRV-4019"));
        assertThat(snapshots)
                .as("the snapshot subscriber was never sent a second snapshot of a different query")
                .hasValue(1);

        // What it did see is the old version's answer, whole: two columns, never three.
        assertThat(snapshotRows)
                .allSatisfy(change -> assertThat(change.values()).hasSize(2));
        assertThat(plain).allSatisfy(change -> assertThat(change.values()).hasSize(2));

        // And a subscription to the retired version is refused rather than followed for ever.
        assertThatThrownBy(() -> v1.subscribe(changes -> {}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4019");

        // Subscribing again to the name gets the new version, from its own snapshot.
        List<ViewChange> after = new CopyOnWriteArrayList<>();
        try (Subscription ignored = registry.find("orders")
                .orElseThrow()
                .subscribeFromSnapshot(new SubscriptionListener() {
                    @Override
                    public void onSnapshot(List<ViewChange> rows, long frontier) {
                        after.addAll(rows);
                    }

                    @Override
                    public void onCommit(@Nullable List<ViewChange> changes, long frontier) {
                        after.addAll(changes);
                    }
                })) {
            await(() -> !after.isEmpty());
            assertThat(after).allSatisfy(change -> assertThat(change.values()).hasSize(3));
        }
    }

    @Test
    void aRollbackPutsTheVersionItReplacedBackExactly() {
        ReplayableLog log = history(40);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);
        assertThat(registry.find("orders").orElseThrow().sql()).isEqualTo(V2);

        // The world moves on while the new version is serving, and the retained one keeps up with
        // it -- which is what makes the rollback one swap rather than a second backfill.
        for (int i = 0; i < 20; i++) {
            log.append("u" + (i % 3), 1_000L + i);
        }
        awaitRowsAtLeast(registry, "orders", 1);
        registry.replacements().rollBack("orders", DANA);

        assertThat(registry.find("orders").orElseThrow().sql()).isEqualTo(V1);
        assertThat(registry.replacements().of("orders").orElseThrow().state())
                .isEqualTo(QueryReplacement.State.ROLLED_BACK);

        QueryRegistry fresh = registry(log);
        fresh.register("reference", V1, List.of(0), DANA);
        awaitRows(fresh, "reference", 1);
        awaitSameRows(registry, "orders", fresh, "reference");
    }

    @Test
    void afterTheReplacementIsFinishedThereIsNothingToRollBackTo() {
        ReplayableLog log = history(10);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);
        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);

        assertThat(registry.replacements().of("orders").orElseThrow().rollbackAvailable())
                .isTrue();
        registry.replacements().finish("orders", DANA);
        assertThat(registry.replacements().of("orders").orElseThrow().rollbackAvailable())
                .isFalse();
        assertThatThrownBy(() -> registry.replacements().rollBack("orders", DANA))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8003");
    }

    @Test
    void theRollbackWindowClosesOnItsOwnAndReleasesTheReplacedVersion() {
        ReplayableLog log = history(6);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);
        registry.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withRollbackRetention(Duration.ofMillis(1)));
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);

        await(() -> registry.replacements().of("orders").orElseThrow().state() == QueryReplacement.State.FINISHED);
        assertThat(registry.replacements().of("orders").orElseThrow().rollbackAvailable())
                .isFalse();
    }

    @Test
    void anAutomaticCutoverHappensAsSoonAsTheCandidateHasCaughtUp() {
        ReplayableLog log = history(20);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);
        registry.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withCutover(ReplacementOptions.Cutover.AUTO));

        await(() -> registry.replacements().of("orders").orElseThrow().state() == QueryReplacement.State.CUT_OVER);
        assertThat(registry.find("orders").orElseThrow().sql()).isEqualTo(V2);
    }

    @Test
    void replacingIsAdministeringTheNameAndIsRefusedToAPrincipalWhoMayNot() {
        ReplayableLog log = history(6);
        SecurityPolicy readOnly = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayAdminister(Principal principal, String view) {
                return AccessDecision.deny("only the owner may administer '" + view + "'");
            }
        };
        QueryRegistry registry = registry(log, readOnly);
        // Registered by somebody else: dana may read it, and the policy grants her nothing over it.
        registry.register("orders", V1, List.of(0), new Principal("erin", "public", Set.of("analyst"), Map.of()));

        assertThatThrownBy(() ->
                        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
    }

    @Test
    void whatIsRefusedByName() {
        ReplayableLog log = history(6);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        // A name nothing is replacing.
        assertThatThrownBy(() -> registry.replacements().cutOver("orders", DANA))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4016");

        // A new version that is the same computation as the old one: a cutover to itself.
        assertThatThrownBy(() ->
                        registry.replacements().replace("orders", V1, List.of(0), DANA, ReplacementOptions.defaults()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4017");

        // An option the design describes and this engine does not build, named rather than ignored.
        assertThatThrownBy(() -> ReplacementOptions.with(ReplacementOptions.defaults(), "backfill.window", "P7D"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("backfill.window");

        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        // A second shadow for one name.
        assertThatThrownBy(() -> registry.replacements()
                        .replace(
                                "orders",
                                V2 + " HAVING SUM(amount) > 0",
                                List.of(0),
                                DANA,
                                ReplacementOptions.defaults()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4017");
        // And a drop while the candidate is still running would leave it running for nobody.
        assertThatThrownBy(() -> registry.drop("orders"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4017");
    }

    @Test
    void aStreamNothingIsBoundToCannotBeBackfilledAndSaysSo() {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN);
        registries.add(registry);
        registry.register("orders", V1, List.of(0), DANA);
        assertThatThrownBy(() ->
                        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("txn");
    }

    @Test
    void theBackfillIsThrottledPausedAndResumedWhileItRuns() {
        ReplayableLog log = history(400);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        registry.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withRateLimit(20));
        QueryReplacement.Status throttled = registry.replacements().throttle("orders", 5, DANA);
        assertThat(throttled.progress().rateLimit()).isEqualTo(5);

        registry.replacements().pause("orders", DANA);
        long paused =
                registry.replacements().of("orders").orElseThrow().progress().historyRows();
        sleep(Duration.ofMillis(200));
        assertThat(registry.replacements().of("orders").orElseThrow().progress().historyRows())
                .as("a paused backfill reads nothing")
                .isEqualTo(paused);
        assertThat(registry.replacements().of("orders").orElseThrow().progress().paused())
                .isTrue();

        registry.replacements().resume("orders", DANA);
        await(() ->
                registry.replacements().of("orders").orElseThrow().progress().historyRows() > paused);

        // Above the ceiling the replacement was started with is refused: it is a ceiling.
        assertThatThrownBy(() -> registry.replacements().throttle("orders", 5_000, DANA))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018");
    }

    // ------------------------------------------------------------------ waiting

    @Test
    void aBackfillWhoseFeedStopsFailsTheReplacementAndReleasesTheCandidate() {
        // REPL-1: found against a running node, where a backfill that could not reach the seam
        // stopped with PRV-4013 and the replacement went on reporting BACKFILLING, failure null,
        // its lag growing, for as long as anyone polled it.
        ReplayableLog log = history(20);
        QueryRegistry registry = registry(log);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);
        List<String> before = rendered(registry, "orders");

        log.failBackfillsWith(new PravahaException(
                com.ash.messaging.pravaha.backfill.BackfillErrors.SPLICE_MISSED,
                "the backfill read all the history this source has and never reached the position the running "
                        + "version is at"));
        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());

        await(() -> registry.replacements().of("orders").orElseThrow().state() == QueryReplacement.State.FAILED);
        QueryReplacement.Status failed = registry.replacements().of("orders").orElseThrow();
        assertThat(failed.failureCode()).isEqualTo("PRV-4013");
        assertThat(failed.failure()).contains("never reached the position");

        // The name goes on answering the version it answered, and the failure is not left holding
        // the candidate: a second replacement is accepted once the cause is gone.
        assertThat(registry.find("orders").orElseThrow().sql()).isEqualTo(V1);
        assertThat(rendered(registry, "orders")).isEqualTo(before);
        await(() -> registry.find("orders").orElseThrow().names().size() == 1);
        log.failBackfillsWith(null);
        QueryReplacement.Status again =
                registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        assertThat(again.state()).isIn(QueryReplacement.State.BACKFILLING, QueryReplacement.State.CAUGHT_UP);
        awaitCaughtUp(registry, "orders");
    }

    private static void awaitRows(QueryRegistry registry, String name, int rows) {
        await(() -> registry.find(name).orElseThrow().view().size() == rows);
    }

    private static void awaitRowsAtLeast(QueryRegistry registry, String name, int rows) {
        await(() -> registry.find(name).orElseThrow().view().size() >= rows);
    }

    private static void awaitCaughtUp(QueryRegistry registry, String name) {
        await(() -> registry.replacements().of(name).orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
    }

    /**
     * Waits for two views to hold exactly the same rows.
     *
     * <p>Both are fed by the same log and neither is told when the other has finished, so the
     * comparison is retried rather than taken once: what is asserted is that they converge on the
     * same answer, which is what "the same as a registration from scratch" means for two queries
     * reading a source that is still being appended to.
     */
    private static void awaitSameRows(QueryRegistry left, String leftName, QueryRegistry right, String rightName) {
        Supplier<List<String>> a = () -> rendered(left, leftName);
        Supplier<List<String>> b = () -> rendered(right, rightName);
        await(() -> !a.get().isEmpty() && a.get().equals(b.get()));
        assertThat(a.get()).isEqualTo(b.get());
    }

    private static List<String> rendered(QueryRegistry registry, String name) {
        List<String> rows = new ArrayList<>();
        registry.find(name).orElseThrow().view().scan().forEach(row -> rows.add(Arrays.toString(row)));
        rows.sort(String::compareTo);
        return rows;
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(10));
        }
        assertThat(condition.getAsBoolean())
                .as("the condition did not hold within 30 seconds")
                .isTrue();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

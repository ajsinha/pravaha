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
package com.ash.messaging.pravaha.algebra;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The property oracle.</strong> Story P1-04, and the gate on milestone M2.
 *
 * <p>It checks one statement, over generated queries and generated changes:
 *
 * <pre>
 *     Q(S + dS)  ==  Q(S) + Qd(dS, S)
 * </pre>
 *
 * <p>This is the cheapest insurance in the whole plan. A retract-stream engine has no equivalent --
 * its correctness rests on the test cases someone thought to write, and the cases nobody thought of
 * are exactly where outer joins and {@code DISTINCT} over updating inputs go wrong. Here the
 * property is machine-checkable over the entire operator set, generated rather than enumerated, and
 * it runs on every commit.
 *
 * <p>Building it <em>before</em> the aggregate and join lifts is deliberate (implementation plan
 * §13): if the oracle proves hard to build, that is the early warning about the whole incremental
 * approach, and it arrives in week 6 rather than week 40.
 *
 * <p><strong>Budget.</strong> Eight properties at 1400 tries is 11 200 generated cases per run,
 * against M2's requirement of at least 10 000 within 90 seconds. It currently runs in a couple of
 * seconds; the headroom is deliberate, because this suite will grow as aggregates and joins arrive
 * and it must stay inside the inner-loop budget rather than being moved to a nightly job.
 */
class IncrementalOracleTest {

    /** A row with value semantics -- weights are keyed on row identity. */
    record Row(String key, int value, String status) {}

    // ------------------------------------------------------------------ the oracle

    /** Asserts the incremental form agrees with the batch form for one (query, state, delta). */
    private static <I, O> void assertAgrees(
            String name, Query<I, O> batch, IncrementalQuery<I, O> incremental, ZSet<I> state, ZSet<I> delta) {

        ZSet<O> viaBatch = batch.evaluate(state.plus(delta));
        ZSet<O> viaIncremental = batch.evaluate(state).plus(incremental.evaluateDelta(delta, state));

        assertThat(viaIncremental)
                .as(
                        "%s violated Q(S + dS) == Q(S) + Qd(dS, S)%n  state = %s%n  delta = %s%n"
                                + "  batch       = %s%n  incremental = %s",
                        name, state, delta, viaBatch, viaIncremental)
                .isEqualTo(viaBatch);
    }

    // ------------------------------------------------------------------ generated properties

    @Property(tries = 1400)
    void filterIsLinearAndSoNeedsNoState(@ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        Query<Row, Row> q = Lift.filter(r -> r.value() > 50);
        assertAgrees("filter", q, Lift.linear(q), state, delta);
    }

    @Property(tries = 1400)
    void projectIsLinear(@ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        // Projecting away a column merges rows, so weights add. That is the case a naive
        // implementation gets wrong, and it only shows up when the projection is not injective.
        Query<Row, String> q = Lift.project(Row::key);
        assertAgrees("project", q, Lift.linear(q), state, delta);
    }

    @Property(tries = 1400)
    void filterThenProjectComposes(@ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        Query<Row, String> q = Lift.compose(Lift.filter(r -> "COMPLETED".equals(r.status())), Lift.project(Row::key));
        assertAgrees("filter->project", q, Lift.linear(q), state, delta);
    }

    @Property(tries = 1400)
    void flatMapIsLinear(@ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        Query<Row, String> q = Lift.flatMap(r -> List.of(r.key(), r.key() + ":" + r.status()));
        assertAgrees("flatMap", q, Lift.linear(q), state, delta);
    }

    @Property(tries = 1400)
    void distinctIsNotLinearButItsLiftIsStillCorrect(
            @ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        // The first non-linear operator. Its lift consults the state, and getting that wrong is
        // subtle: a row going from weight 3 to 4 is still present, so nothing must propagate.
        assertAgrees("distinct", Lift.distinct(), Lift.distinctIncremental(), state, delta);
    }

    @Property(tries = 1400)
    void theRecomputationFallbackIsCorrectForAnyQuery(
            @ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        // The escape hatch design 9.8 promises. Always correct, always O(state) -- so it must at
        // least be correct, or the escape hatch is not one.
        Query<Row, Row> q = Lift.compose(Lift.filter(r -> r.value() % 3 == 0), Lift.distinct());
        assertAgrees("recomputation fallback", q, Lift.byRecomputation(q), state, delta);
    }

    @Property(tries = 1400)
    void aChainOfViewsStaysCorrect(@ForAll("states") ZSet<Row> state, @ForAll("deltas") ZSet<Row> delta) {
        // Chained views are the normal shape of real analytics and the case where a per-operator
        // error compounds instead of cancelling.
        Query<Row, String> q = Lift.compose(
                Lift.compose(Lift.filter(r -> r.value() > 20), Lift.filter(r -> !"FAILED".equals(r.status()))),
                Lift.project(r -> r.key() + "/" + r.status()));
        assertAgrees("view chain", q, Lift.linear(q), state, delta);
    }

    @Property(tries = 1400)
    void appliedDeltasAreEquivalentToRecomputingFromScratch(
            @ForAll("states") ZSet<Row> state, @ForAll("deltaSequences") List<ZSet<Row>> deltas) {
        // The property that matters operationally: a long-running incremental query must not drift
        // from what a fresh batch computation would produce. Drift is the failure mode that never
        // announces itself.
        Query<Row, String> q = Lift.compose(Lift.filter(r -> r.value() > 30), Lift.project(Row::key));
        IncrementalQuery<Row, String> incremental = Lift.linear(q);

        ZSet<Row> accumulated = state;
        ZSet<String> running = q.evaluate(state);
        for (ZSet<Row> delta : deltas) {
            running = running.plus(incremental.evaluateDelta(delta, accumulated));
            accumulated = accumulated.plus(delta);
        }
        assertThat(running)
                .as("after %d deltas the incremental result drifted from a fresh computation", deltas.size())
                .isEqualTo(q.evaluate(accumulated));
    }

    // ------------------------------------------------------------------ non-vacuity guards

    @Test
    void theGeneratorProducesGenuinelyMixedInputs() {
        // Without this the properties above could pass vacuously. If every generated delta were
        // empty, or every weight +1, the oracle would be checking almost nothing -- and it would
        // look exactly as green as a real one.
        int nonEmpty = 0;
        int withNegativeWeights = 0;
        int withWeightsBeyondOne = 0;
        Random random = new Random(20260909L);
        for (int i = 0; i < 400; i++) {
            ZSet<Row> z = randomZSet(random, 6, true);
            if (!z.isEmpty()) {
                nonEmpty++;
            }
            if (z.entries().values().stream().anyMatch(w -> w < 0)) {
                withNegativeWeights++;
            }
            if (z.entries().values().stream().anyMatch(w -> Math.abs(w) > 1)) {
                withWeightsBeyondOne++;
            }
        }
        assertThat(nonEmpty).as("most generated sets should be non-empty").isGreaterThan(300);
        assertThat(withNegativeWeights)
                .as("retractions must be generated, or the oracle never sees an update or a delete")
                .isGreaterThan(100);
        assertThat(withWeightsBeyondOne)
                .as("multiplicities beyond one must be generated")
                .isGreaterThan(50);
    }

    @Test
    void theOracleCatchesADeliberatelyBrokenLift() {
        // Proves the oracle can fail. A property that cannot detect a wrong implementation is
        // decoration, and this suite is the justification for the whole incremental approach.
        IncrementalQuery<Row, Row> broken = (delta, state) -> delta; // ignores the filter entirely
        Query<Row, Row> q = Lift.filter(r -> r.value() > 50);

        ZSet<Row> state = ZSet.ofRows(new Row("a", 10, "OK"));
        ZSet<Row> delta = ZSet.ofRows(new Row("b", 5, "OK")); // filtered out by the real query

        assertThat(catchThrowable(() -> assertAgrees("broken", q, broken, state, delta)))
                .as("the oracle must reject a lift that ignores its query")
                .isNotNull();
    }

    @Test
    void theOracleCatchesAnOffByOneInTheDistinctLift() {
        // A subtler seeded bug: emitting on any weight change rather than on a presence change.
        IncrementalQuery<Row, Row> subtlyWrong = (delta, state) -> delta.distinct();

        ZSet<Row> state = ZSet.of(new Row("a", 1, "OK"), 3); // already present
        ZSet<Row> delta = ZSet.of(new Row("a", 1, "OK"), 1); // 3 -> 4: still present, emit nothing

        assertThat(catchThrowable(
                        () -> assertAgrees("subtly wrong distinct", Lift.distinct(), subtlyWrong, state, delta)))
                .as("a lift that emits on weight change rather than presence change must be caught")
                .isNotNull();
    }

    private static Throwable catchThrowable(Runnable body) {
        try {
            body.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // ------------------------------------------------------------------ generators

    @Provide
    Arbitrary<ZSet<Row>> states() {
        return Arbitraries.longs().map(seed -> randomZSet(new Random(seed), 8, true));
    }

    @Provide
    Arbitrary<ZSet<Row>> deltas() {
        return Arbitraries.longs().map(seed -> randomZSet(new Random(seed), 5, true));
    }

    @Provide
    Arbitrary<List<ZSet<Row>>> deltaSequences() {
        return Arbitraries.longs().map(seed -> {
            Random random = new Random(seed);
            List<ZSet<Row>> sequence = new ArrayList<>();
            int count = 1 + random.nextInt(8);
            for (int i = 0; i < count; i++) {
                sequence.add(randomZSet(random, 4, true));
            }
            return sequence;
        });
    }

    private static final String[] KEYS = {"a", "b", "c", "d"};
    private static final String[] STATUSES = {"COMPLETED", "PENDING", "FAILED"};

    private static ZSet<Row> randomZSet(Random random, int maxRows, boolean allowNegative) {
        ZSet.Builder<Row> b = ZSet.builder();
        int rows = random.nextInt(maxRows + 1);
        for (int i = 0; i < rows; i++) {
            Row row = new Row(
                    KEYS[random.nextInt(KEYS.length)], random.nextInt(100), STATUSES[random.nextInt(STATUSES.length)]);
            // A small key space on purpose: it forces collisions, which is where weight arithmetic
            // and cancellation actually get exercised.
            long weight = 1L + random.nextInt(3);
            if (allowNegative && random.nextBoolean()) {
                weight = -weight;
            }
            b.add(row, weight);
        }
        return b.build();
    }
}

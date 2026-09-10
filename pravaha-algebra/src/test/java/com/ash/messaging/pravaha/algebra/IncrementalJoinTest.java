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

import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bilinear join lift, checked against the definition.
 *
 * <p>The oracle statement for a join is the same as for everything else -- the incremental form must
 * agree with recomputing from scratch -- but a join has two inputs, so the generated property drives
 * both sides through a sequence of steps and compares the accumulated result against a full
 * recomputation at each one. That is the only way to catch the term that is easy to drop.
 *
 * <p>{@link #matchesArrivingInTheSameStepAreJoined} is that term. Without {@code ΔA⋈ΔB}, two rows
 * that arrive together and match each other are never joined, because each is compared against the
 * other side as it was <em>before</em> the step. It is invisible at low rates -- a batch of one
 * contains no pairs to miss -- and shows up as quietly missing output when traffic rises, which is
 * exactly backwards from how anybody debugs.
 */
class IncrementalJoinTest {

    record Order(String customer, int amount) {}

    record Customer(String id, String tier) {}

    record Enriched(String customer, int amount, String tier) {}

    private static IncrementalJoin<Order, Customer, String, Enriched> join() {
        return new IncrementalJoin<>(
                Order::customer,
                Customer::id,
                (order, customer) -> new Enriched(order.customer(), order.amount(), customer.tier()));
    }

    /** The definition: join two whole relations, weights multiplied. */
    private static ZSet<Enriched> recompute(ZSet<Order> orders, ZSet<Customer> customers) {
        ZSet.Builder<Enriched> result = ZSet.builder();
        orders.entries()
                .forEach((order, orderWeight) -> customers.entries().forEach((customer, customerWeight) -> {
                    if (order.customer().equals(customer.id())) {
                        result.add(
                                new Enriched(order.customer(), order.amount(), customer.tier()),
                                orderWeight * customerWeight);
                    }
                }));
        return result.build();
    }

    @Test
    void joinsARowAgainstWhatTheOtherSideAlreadyHeld() {
        IncrementalJoin<Order, Customer, String, Enriched> join = join();

        join.step(ZSet.empty(), ZSet.of(new Customer("ann", "gold"), 1));
        ZSet<Enriched> delta = join.step(ZSet.of(new Order("ann", 100), 1), ZSet.empty());

        assertThat(delta.entries()).containsEntry(new Enriched("ann", 100, "gold"), 1L);
    }

    @Test
    void matchesArrivingInTheSameStepAreJoined() {
        // The third term of the rule. Each side is compared against the other as it was before the
        // step, so without it a pair that arrives together is never joined -- and a batch of one
        // contains no such pair, which is why this hides until traffic rises.
        IncrementalJoin<Order, Customer, String, Enriched> join = join();

        ZSet<Enriched> delta = join.step(ZSet.of(new Order("ann", 100), 1), ZSet.of(new Customer("ann", "gold"), 1));

        assertThat(delta.entries())
                .as("a left row and a right row that match and arrive together must be joined exactly once")
                .containsExactly(java.util.Map.entry(new Enriched("ann", 100, "gold"), 1L));
    }

    @Test
    void aRetractionOnEitherSideWithdrawsTheJoinedRow() {
        // No special-case code: an update is -1 of the old and +1 of the new, and the rule is
        // arithmetic over weights that does not care which side changed.
        IncrementalJoin<Order, Customer, String, Enriched> join = join();
        join.step(ZSet.of(new Order("ann", 100), 1), ZSet.of(new Customer("ann", "gold"), 1));

        ZSet<Enriched> afterRetraction = join.step(ZSet.empty(), ZSet.of(new Customer("ann", "gold"), -1));

        assertThat(afterRetraction.entries()).containsEntry(new Enriched("ann", 100, "gold"), -1L);
    }

    @Test
    void anUpdateOnOneSideRewritesEveryRowItJoinedTo() {
        // The case a hand-written retract-stream engine gets wrong: one customer row changing tier
        // has to withdraw and reissue every order joined to it.
        IncrementalJoin<Order, Customer, String, Enriched> join = join();
        join.step(
                ZSet.<Order>builder()
                        .add(new Order("ann", 100), 1)
                        .add(new Order("ann", 200), 1)
                        .build(),
                ZSet.of(new Customer("ann", "gold"), 1));

        ZSet<Enriched> delta = join.step(
                ZSet.empty(),
                ZSet.<Customer>builder()
                        .add(new Customer("ann", "gold"), -1)
                        .add(new Customer("ann", "platinum"), 1)
                        .build());

        assertThat(delta.entries())
                .containsEntry(new Enriched("ann", 100, "gold"), -1L)
                .containsEntry(new Enriched("ann", 200, "gold"), -1L)
                .containsEntry(new Enriched("ann", 100, "platinum"), 1L)
                .containsEntry(new Enriched("ann", 200, "platinum"), 1L);
    }

    @Test
    void aRowRetractedToZeroLeavesTheIndex() {
        // Keeping a zero-weight row would grow the index with every update on either side --
        // unbounded state produced by bookkeeping rather than by data.
        IncrementalJoin<Order, Customer, String, Enriched> join = join();
        join.step(ZSet.of(new Order("ann", 100), 1), ZSet.of(new Customer("ann", "gold"), 1));
        assertThat(join.leftRows()).isEqualTo(1);

        join.step(ZSet.of(new Order("ann", 100), -1), ZSet.empty());

        assertThat(join.leftRows()).isZero();
        assertThat(join.keyCount()).as("and the empty key goes with it").isEqualTo(1);
    }

    @Test
    void forgettingAKeyReleasesBothSides() {
        // How a window or a TTL bounds a join: the caller decides a key can no longer match.
        IncrementalJoin<Order, Customer, String, Enriched> join = join();
        join.step(ZSet.of(new Order("ann", 100), 1), ZSet.of(new Customer("ann", "gold"), 1));

        join.forget("ann");

        assertThat(join.leftRows()).isZero();
        assertThat(join.rightRows()).isZero();
        assertThat(join.keyCount()).isZero();
    }

    @Property(tries = 400)
    void theIncrementalJoinAgreesWithRecomputingItFromScratch(@ForAll("stepSequences") List<Step> steps) {

        IncrementalJoin<Order, Customer, String, Enriched> join = join();
        ZSet<Order> allOrders = ZSet.empty();
        ZSet<Customer> allCustomers = ZSet.empty();
        ZSet<Enriched> accumulated = ZSet.empty();

        for (Step step : steps) {
            accumulated = accumulated.plus(join.step(step.orders(), step.customers()));
            allOrders = allOrders.plus(step.orders());
            allCustomers = allCustomers.plus(step.customers());

            assertThat(accumulated)
                    .as("after %s the accumulated deltas must equal the join of everything seen", steps)
                    .isEqualTo(recompute(allOrders, allCustomers));
        }
    }

    /** One timestep's changes to both sides. */
    record Step(ZSet<Order> orders, ZSet<Customer> customers) {}

    @Provide
    Arbitrary<List<Step>> stepSequences() {
        Arbitrary<String> keys = Arbitraries.of("ann", "bob", "cat");
        Arbitrary<Integer> amounts = Arbitraries.integers().between(1, 5);
        Arbitrary<Long> weights = Arbitraries.of(-1L, 1L, 2L);

        Arbitrary<ZSet<Order>> orders = keys.list()
                .ofMaxSize(3)
                .flatMap(customerKeys -> amounts.list()
                        .ofSize(customerKeys.size())
                        .flatMap(values -> weights.list()
                                .ofSize(customerKeys.size())
                                .map(rowWeights -> {
                                    ZSet.Builder<Order> builder = ZSet.builder();
                                    for (int i = 0; i < customerKeys.size(); i++) {
                                        builder.add(new Order(customerKeys.get(i), values.get(i)), rowWeights.get(i));
                                    }
                                    return builder.build();
                                })));

        Arbitrary<ZSet<Customer>> customers = keys.list()
                .ofMaxSize(3)
                .flatMap(customerKeys -> Arbitraries.of("gold", "silver")
                        .list()
                        .ofSize(customerKeys.size())
                        .flatMap(tiers -> weights.list()
                                .ofSize(customerKeys.size())
                                .map(rowWeights -> {
                                    ZSet.Builder<Customer> builder = ZSet.builder();
                                    for (int i = 0; i < customerKeys.size(); i++) {
                                        builder.add(new Customer(customerKeys.get(i), tiers.get(i)), rowWeights.get(i));
                                    }
                                    return builder.build();
                                })));

        return orders.flatMap(o -> customers.map(c -> new Step(o, c)))
                .list()
                .ofMinSize(1)
                .ofMaxSize(6);
    }
}

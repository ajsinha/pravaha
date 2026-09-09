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
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZSetTest {

    @Test
    void zeroWeightMeansAbsentAndIsNeverStored() {
        // The invariant everything else depends on: isEmpty() is meaningful, and the runtime can
        // prune a no-op delta before it propagates.
        assertThat(ZSet.of("a", 0).isEmpty()).isTrue();
        assertThat(ZSet.of("a", 1).plus(ZSet.of("a", -1))).isEqualTo(ZSet.empty());
        assertThat(ZSet.of("a", 1).plus(ZSet.of("a", -1)).support()).isEmpty();
        assertThat(ZSet.builder().add("a", 5).add("a", -5).build().entries()).isEmpty();
    }

    @Test
    void anUpdateIsJustMinusOnePlusOne() {
        // No special case, no retract stream, no per-operator handling. This is the whole point.
        ZSet<String> relation = ZSet.ofRows("old");
        ZSet<String> update =
                ZSet.<String>builder().add("old", -1).add("new", 1).build();
        assertThat(relation.plus(update)).isEqualTo(ZSet.ofRows("new"));
    }

    @Test
    void aRelationIsAZSetOfAllOnes() {
        assertThat(ZSet.ofRows("a", "b").entries()).containsExactly(Map.entry("a", 1L), Map.entry("b", 1L));
    }

    @Test
    void cardinalityCanBeNegativeForAPureRetraction() {
        assertThat(ZSet.of("a", -3).cardinality()).isEqualTo(-3);
        assertThat(ZSet.ofRows("a", "b").cardinality()).isEqualTo(2);
        assertThat(ZSet.of("a", 5).size())
                .as("size counts distinct rows, not weights")
                .isOne();
    }

    @Test
    void scalingByZeroYieldsEmptyRatherThanZeroWeights() {
        assertThat(ZSet.ofRows("a", "b").scale(0)).isEqualTo(ZSet.empty());
        assertThat(ZSet.ofRows("a").scale(1)).isEqualTo(ZSet.ofRows("a"));
        assertThat(ZSet.of("a", 2).scale(3).weightOf("a")).isEqualTo(6);
    }

    @Test
    void projectionAddsTheWeightsOfRowsThatMerge() {
        // The case a naive implementation gets wrong: projecting away a distinguishing column must
        // not lose duplicates.
        ZSet<String> merged = ZSet.ofRows("a1", "a2", "b1").map(s -> s.substring(0, 1));
        assertThat(merged.weightOf("a")).isEqualTo(2);
        assertThat(merged.weightOf("b")).isEqualTo(1);
    }

    @Test
    void distinctCollapsesWeightsAndDropsNegatives() {
        ZSet<String> z =
                ZSet.<String>builder().add("a", 5).add("b", -2).add("c", 1).build();
        assertThat(z.distinct()).isEqualTo(ZSet.ofRows("a", "c"));
    }

    @Test
    void filterAndFlatMapBehave() {
        ZSet<Integer> z = ZSet.ofRows(1, 2, 3, 4);
        assertThat(z.filter(i -> i % 2 == 0)).isEqualTo(ZSet.ofRows(2, 4));
        assertThat(z.flatMap(i -> List.of(i, i)).weightOf(1)).isEqualTo(2);
    }

    @Test
    void rejectsANullRow() {
        assertThatThrownBy(() -> ZSet.of((String) null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ZSet.builder().add(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rendersReadably() {
        assertThat(ZSet.empty()).hasToString("{}");
        assertThat(ZSet.<String>builder().add("a", 2).add("b", -1).build()).hasToString("{a: +2, b: -1}");
    }

    // ------------------------------------------------------------------ group laws

    @Property(tries = 400)
    void additionIsAssociative(
            @ForAll("zsets") ZSet<String> a, @ForAll("zsets") ZSet<String> b, @ForAll("zsets") ZSet<String> c) {
        assertThat(a.plus(b).plus(c)).isEqualTo(a.plus(b.plus(c)));
    }

    @Property(tries = 400)
    void additionIsCommutative(@ForAll("zsets") ZSet<String> a, @ForAll("zsets") ZSet<String> b) {
        assertThat(a.plus(b)).isEqualTo(b.plus(a));
    }

    @Property(tries = 400)
    void emptyIsTheIdentity(@ForAll("zsets") ZSet<String> a) {
        assertThat(a.plus(ZSet.empty())).isEqualTo(a);
    }

    @Property(tries = 400)
    void everyElementHasAnInverse(@ForAll("zsets") ZSet<String> a) {
        // Without this, a delta could not undo itself and replay would not be safe.
        assertThat(a.plus(a.negate())).isEqualTo(ZSet.<String>empty());
        assertThat(a.minus(a)).isEqualTo(ZSet.<String>empty());
    }

    @Property(tries = 400)
    void negationIsAnInvolution(@ForAll("zsets") ZSet<String> a) {
        assertThat(a.negate().negate()).isEqualTo(a);
    }

    @Property(tries = 400)
    void filterDistributesOverAddition(@ForAll("zsets") ZSet<String> a, @ForAll("zsets") ZSet<String> b) {
        // Linearity, stated directly. It is what makes filter's incremental form stateless.
        assertThat(a.plus(b).filter(s -> s.startsWith("k")))
                .isEqualTo(a.filter(s -> s.startsWith("k")).plus(b.filter(s -> s.startsWith("k"))));
    }

    @Property(tries = 400)
    void projectionDistributesOverAddition(@ForAll("zsets") ZSet<String> a, @ForAll("zsets") ZSet<String> b) {
        assertThat(a.plus(b).map(String::length))
                .isEqualTo(a.map(String::length).plus(b.map(String::length)));
    }

    @Property(tries = 400)
    void differentiateAndIntegrateAreInverses(@ForAll("zsetSequences") List<ZSet<String>> stream) {
        // D(I(s)) == s, the identity the entire incremental construction is built on.
        Integrate<String> integrate = new Integrate<>();
        Differentiate<String> differentiate = new Differentiate<>();
        for (ZSet<String> delta : stream) {
            assertThat(differentiate.apply(integrate.apply(delta))).isEqualTo(delta);
        }
    }

    @Property(tries = 400)
    void integrationAccumulates(@ForAll("zsetSequences") List<ZSet<String>> stream) {
        Integrate<String> integrate = new Integrate<>();
        ZSet<String> expected = ZSet.empty();
        for (ZSet<String> delta : stream) {
            expected = expected.plus(delta);
            assertThat(integrate.apply(delta)).isEqualTo(expected);
        }
        assertThat(integrate.stateSize()).isEqualTo(expected.size());
    }

    @Provide
    Arbitrary<ZSet<String>> zsets() {
        return Arbitraries.longs().map(seed -> {
            java.util.Random r = new java.util.Random(seed);
            ZSet.Builder<String> b = ZSet.builder();
            int n = r.nextInt(6);
            for (int i = 0; i < n; i++) {
                // A tiny key space forces collisions, which is where the arithmetic is exercised.
                String row = "k" + r.nextInt(4);
                long w = 1 + r.nextInt(3);
                b.add(row, r.nextBoolean() ? w : -w);
            }
            return b.build();
        });
    }

    @Provide
    Arbitrary<List<ZSet<String>>> zsetSequences() {
        return zsets().list().ofMinSize(1).ofMaxSize(10);
    }
}

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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.List;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.plugin.ReadRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one reader shared by several queries asks for (ADR-039 item 6): wide enough for every one of
 * them, which is the property that matters, and no wider than it needs to be.
 */
class SharedReadRequestTest {

    private static ReadRequest.Filter gt(long value) {
        return new ReadRequest.Filter("amount", ReadRequest.Comparison.GT, value);
    }

    private static ReadRequest.Filter lt(long value) {
        return new ReadRequest.Filter("amount", ReadRequest.Comparison.LT, value);
    }

    private static final ReadRequest.Filter DONE = new ReadRequest.Filter("id", ReadRequest.Comparison.NE, 0L);

    @Test
    void twoDifferentFiltersBecomeTheirOr() {
        ReadRequest union =
                SharedReadRequest.union(List.of(new ReadRequest(List.of(gt(100))), new ReadRequest(List.of(lt(10)))));

        assertThat(union.filters()).isEmpty();
        assertThat(union.alternatives()).containsExactlyInAnyOrder(List.of(gt(100)), List.of(lt(10)));
    }

    @Test
    void whatEveryQueryHasInCommonStaysAPlainConjunct() {
        ReadRequest union = SharedReadRequest.union(
                List.of(new ReadRequest(List.of(DONE, gt(100))), new ReadRequest(List.of(DONE, lt(10)))));

        assertThat(union.filters()).containsExactly(DONE);
        assertThat(union.alternatives()).containsExactlyInAnyOrder(List.of(gt(100)), List.of(lt(10)));
    }

    @Test
    void aQueryWhoseFilterIsTheCommonPartMakesTheOrTrue() {
        ReadRequest union = SharedReadRequest.union(
                List.of(new ReadRequest(List.of(DONE, gt(100))), new ReadRequest(List.of(DONE))));

        assertThat(union.filters()).containsExactly(DONE);
        assertThat(union.alternatives()).isEmpty();
    }

    @Test
    void aQueryWithNoFilterMeansEveryRowIsRead() {
        ReadRequest union = SharedReadRequest.union(List.of(new ReadRequest(List.of(gt(100))), ReadRequest.NOTHING));

        assertThat(union.filters()).isEmpty();
        assertThat(union.alternatives()).isEmpty();
    }

    @Test
    void columnsAreTheirUnionAndAnyQueryNeedingAllMeansAll() {
        ReadRequest a = new ReadRequest(List.of(), List.of("id"), List.of());
        ReadRequest b = new ReadRequest(List.of(), List.of("amount"), List.of());
        assertThat(SharedReadRequest.union(List.of(a, b)).columns()).containsExactly("id", "amount");
        assertThat(SharedReadRequest.union(List.of(a, ReadRequest.NOTHING)).columns())
                .isEmpty();
    }

    @Test
    void identicalQueriesAskForExactlyWhatEachWould() {
        ReadRequest one = new ReadRequest(List.of(gt(5)), List.of("id"), List.of());
        assertThat(SharedReadRequest.union(List.of(one, one))).isEqualTo(one);
    }

    @Test
    void aPartialAggregateIsNeverSharedOut() {
        ReadRequest partial = new ReadRequest(
                List.of(gt(5)),
                List.of(),
                List.of(new ReadRequest.PartialAggregate(
                        List.of(),
                        List.of(new ReadRequest.PartialAggregate.AggregateCall(
                                ReadRequest.PartialAggregate.Kind.COUNT, null, "n")))));
        assertThat(SharedReadRequest.union(List.of(partial)).aggregates()).isEmpty();
    }

    /**
     * The property: every row any member wants is a row the union lets through. A union that is too
     * narrow loses rows silently, which is the only failure here that matters.
     */
    @Property(tries = 300)
    void noRowAnyMemberWantsIsExcludedByTheUnion(
            @ForAll @Size(min = 1, max = 4) List<@IntRange(min = 0, max = 5) Integer> shapes,
            @ForAll @IntRange(min = -5, max = 205) int amount,
            @ForAll @IntRange(min = 0, max = 2) int id) {
        List<ReadRequest> members =
                shapes.stream().map(SharedReadRequestTest::member).toList();
        long[] record = {id, amount};
        ReadRequest union = SharedReadRequest.union(members);
        for (ReadRequest member : members) {
            if (CountingScanPlugin.matches(record, member)) {
                assertThat(CountingScanPlugin.matches(record, union))
                        .as(
                                "record %s is wanted by %s but excluded by the union %s",
                                List.of(id, amount), member, union)
                        .isTrue();
            }
        }
    }

    private static ReadRequest member(int shape) {
        return switch (shape) {
            case 0 -> ReadRequest.NOTHING;
            case 1 -> new ReadRequest(List.of(gt(100)));
            case 2 -> new ReadRequest(List.of(lt(10)));
            case 3 -> new ReadRequest(List.of(DONE, gt(150)));
            case 4 -> new ReadRequest(List.of(DONE));
            default -> new ReadRequest(List.of(DONE, lt(50), gt(20)));
        };
    }
}

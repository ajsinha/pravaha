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
package com.ash.messaging.pravaha.api.plugin;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the engine may ask a source to do for it.
 *
 * <p>The validation here is worth more than it looks. A filter reaches a plugin as data, and a
 * plugin turns it into a query against a store; a malformed one becomes a query that returns the
 * wrong rows rather than an error, which is the failure mode this whole area has to avoid.
 */
class ReadRequestTest {

    @Test
    void anEmptyRequestIsWhatAQueryWithNoWhereClauseSends() {
        assertThat(ReadRequest.NOTHING.isEmpty()).isTrue();
        assertThat(ReadRequest.NOTHING.filters()).isEmpty();
        assertThat(new ReadRequest(List.of(new ReadRequest.Filter("a", ReadRequest.Comparison.EQ, 1L))).isEmpty())
                .isFalse();
    }

    @Test
    void theFilterListIsCopiedRatherThanShared() {
        List<ReadRequest.Filter> mutable = new ArrayList<>();
        mutable.add(new ReadRequest.Filter("a", ReadRequest.Comparison.EQ, 1L));
        ReadRequest request = new ReadRequest(mutable);

        mutable.clear();

        assertThat(request.filters())
                .as("a plugin's view of its request changed under it")
                .hasSize(1);
    }

    @Test
    void aComparisonWithNoValueIsRefused() {
        // Null here would mean UNKNOWN for every row, so the store would return nothing and the
        // engine would report an empty result for a query that has matches.
        assertThatThrownBy(() -> new ReadRequest.Filter("amount", ReadRequest.Comparison.GT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN for every row");
    }

    @Test
    void aNullCheckNeedsNoValue() {
        assertThat(new ReadRequest.Filter("note", ReadRequest.Comparison.IS_NULL, null).value())
                .isNull();
        assertThat(new ReadRequest.Filter("note", ReadRequest.Comparison.IS_NOT_NULL, null).value())
                .isNull();
    }

    @Test
    void aFilterWithoutAColumnOrComparisonIsRefused() {
        assertThatThrownBy(() -> new ReadRequest.Filter("  ", ReadRequest.Comparison.EQ, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column name");
        assertThatThrownBy(() -> new ReadRequest.Filter(null, ReadRequest.Comparison.EQ, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadRequest.Filter("a", null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comparison");
    }

    @Test
    void aRequestWithNoFiltersColumnsOrAggregatesIsEmpty() {
        assertThat(new ReadRequest(List.of(), List.of("id"), List.of()).isEmpty())
                .isFalse();
        assertThat(new ReadRequest(
                                List.of(),
                                List.of(),
                                List.of(new ReadRequest.PartialAggregate(
                                        List.of(),
                                        List.of(new ReadRequest.PartialAggregate.AggregateCall(
                                                ReadRequest.PartialAggregate.Kind.COUNT, null, "n")))))
                        .isEmpty())
                .isFalse();
    }

    @Test
    void theSingleArgumentConstructorStillMeansFiltersOnly() {
        ReadRequest request = new ReadRequest(List.of(new ReadRequest.Filter("a", ReadRequest.Comparison.EQ, 1L)));
        assertThat(request.columns()).isEmpty();
        assertThat(request.aggregates()).isEmpty();
    }

    @Test
    void aPartialAggregateNeedsAtLeastOneCall() {
        assertThatThrownBy(() -> new ReadRequest.PartialAggregate(List.of("status"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one aggregate call");
    }

    @Test
    void aSumNeedsAColumnButCountStarDoesNot() {
        assertThatThrownBy(() -> new ReadRequest.PartialAggregate.AggregateCall(
                        ReadRequest.PartialAggregate.Kind.SUM, null, "total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUM needs a column");

        assertThat(new ReadRequest.PartialAggregate.AggregateCall(ReadRequest.PartialAggregate.Kind.COUNT, null, "n")
                        .column())
                .isNull();
    }

    @Test
    void aPushedAggregateNeedsAnOutputName() {
        assertThatThrownBy(() -> new ReadRequest.PartialAggregate.AggregateCall(
                        ReadRequest.PartialAggregate.Kind.COUNT, null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output name");
    }

    @Test
    void everyComparisonHasItsSqlSpelling() {
        // A store that speaks SQL builds its clause from these, so a wrong one is a query that runs
        // and returns the wrong rows.
        assertThat(ReadRequest.Comparison.EQ.sql()).isEqualTo("=");
        assertThat(ReadRequest.Comparison.NE.sql()).isEqualTo("<>");
        assertThat(ReadRequest.Comparison.LT.sql()).isEqualTo("<");
        assertThat(ReadRequest.Comparison.LE.sql()).isEqualTo("<=");
        assertThat(ReadRequest.Comparison.GT.sql()).isEqualTo(">");
        assertThat(ReadRequest.Comparison.GE.sql()).isEqualTo(">=");
        assertThat(ReadRequest.Comparison.IS_NULL.sql()).isEqualTo("IS NULL");
        assertThat(ReadRequest.Comparison.IS_NOT_NULL.sql()).isEqualTo("IS NOT NULL");
    }
}

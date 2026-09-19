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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-039 item 6, without a server: which OR the scan filter carries, and which bins a projection
 * names. The server-side behaviour of both is {@code AerospikePluginIT}'s.
 */
class AerospikeExpressionsTest {

    private static final StreamSchema SCHEMA =
            AerospikeSchemas.parse("orders", "order_id:INT64,status:STRING,amount:INT64,blob:BYTES?");

    private static ReadRequest.Filter filter(String column, ReadRequest.Comparison comparison, Object value) {
        return new ReadRequest.Filter(column, comparison, value);
    }

    @Test
    void noAlternativesIsNoExpression() {
        assertThat(AerospikeExpressions.anyOf(List.of(), SCHEMA)).isNull();
    }

    @Test
    void translatableAlternativesBecomeOneExpression() {
        assertThat(AerospikeExpressions.anyOf(
                        List.of(
                                List.of(filter("amount", ReadRequest.Comparison.LT, 5L)),
                                List.of(filter("status", ReadRequest.Comparison.EQ, "DONE"))),
                        SCHEMA))
                .isNotNull();
    }

    @Test
    void anAlternativeWithNothingTranslatableMakesTheWholeOrTrue() {
        // A BYTES bin has no comparison, and a column the schema lacks has no bin: that
        // alternative is "true", and dropping it instead would lose every row only it wanted.
        assertThat(AerospikeExpressions.anyOf(
                        List.of(
                                List.of(filter("amount", ReadRequest.Comparison.LT, 5L)),
                                List.of(filter("blob", ReadRequest.Comparison.EQ, "x"))),
                        SCHEMA))
                .isNull();
        assertThat(AerospikeExpressions.anyOf(
                        List.of(
                                List.of(filter("amount", ReadRequest.Comparison.LT, 5L)),
                                List.of(filter("nope", ReadRequest.Comparison.EQ, 1L))),
                        SCHEMA))
                .isNull();
    }

    @Test
    void aProjectionNamesItsBinsAndKeepsTheEventTimeBin() {
        ReadRequest request = new ReadRequest(List.of(), List.of("amount"), List.of());
        assertThat(LutScanReader.projectedBins(SCHEMA, request, -1)).containsExactly("amount");
        assertThat(LutScanReader.projectedBins(SCHEMA, request, 0)).containsExactly("amount", "order_id");
        assertThat(LutScanReader.projectedBins(SCHEMA, ReadRequest.NOTHING, -1))
                .as("no projection reads every bin")
                .isNull();
        assertThat(LutScanReader.projectedBins(
                        SCHEMA, new ReadRequest(List.of(), List.of("amount", "nope"), List.of()), -1))
                .as("a column the schema does not declare means the request is about something else")
                .isNull();
    }
}

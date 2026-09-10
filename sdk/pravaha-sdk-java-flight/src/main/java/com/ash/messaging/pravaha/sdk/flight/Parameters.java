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
package com.ash.messaging.pravaha.sdk.flight;

import java.nio.charset.StandardCharsets;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * Writing bound values into the Arrow batch a prepared statement expects (ADR-032).
 *
 * <p>The server sends the parameter schema when the statement is prepared, so this never guesses a
 * type -- it writes the value into the vector the server asked for, and refuses if the value cannot
 * go there. A mismatch found here names the placeholder and the two types; the same mismatch found
 * on the server is an error about a query the caller did not write.
 */
final class Parameters {

    private Parameters() {}

    /** Fills {@code root} with one row: the caller's values, in placeholder order. */
    static void write(VectorSchemaRoot root, Object[] values) {
        int expected = root.getFieldVectors().size();
        if (values.length != expected) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    "this statement has " + expected + " placeholder" + (expected == 1 ? "" : "s")
                            + " and " + values.length + " value" + (values.length == 1 ? " was" : "s were")
                            + " given",
                    false);
        }
        for (int i = 0; i < expected; i++) {
            set(root.getVector(i), i, values[i]);
        }
        root.setRowCount(1);
    }

    private static void set(FieldVector vector, int index, Object value) {
        vector.allocateNew();
        if (value == null) {
            // Allowed for every placeholder. What it *means* is the query's business: under SQL's
            // three-valued logic `x = NULL` matches nothing, which surprises people often enough to
            // be worth saying in the docs rather than working around here.
            vector.setNull(0);
            return;
        }
        switch (vector) {
            case VarCharVector text -> text.setSafe(0, asText(index, value).getBytes(StandardCharsets.UTF_8));
            case BigIntVector big -> big.setSafe(0, asNumber(index, value).longValue());
            case IntVector small -> small.setSafe(0, asNumber(index, value).intValue());
            case SmallIntVector shorter ->
                shorter.setSafe(0, asNumber(index, value).shortValue());
            case TinyIntVector tiny -> tiny.setSafe(0, asNumber(index, value).byteValue());
            case Float8Vector doubles ->
                doubles.setSafe(0, asNumber(index, value).doubleValue());
            case Float4Vector floats -> floats.setSafe(0, asNumber(index, value).floatValue());
            case BitVector bit -> bit.setSafe(0, Boolean.TRUE.equals(value) ? 1 : 0);
            case TimeStampNanoTZVector stamp ->
                stamp.setSafe(0, asNumber(index, value).longValue());
            case VarBinaryVector binary -> {
                if (!(value instanceof byte[] bytes)) {
                    throw mismatch(index, value, "bytes");
                }
                binary.setSafe(0, bytes);
            }
            default ->
                throw new PravahaClientException(
                        ClientErrors.QUERY_REFUSED,
                        "?" + (index + 1) + " needs a " + vector.getField().getType()
                                + ", which this SDK cannot send yet",
                        false);
        }
        vector.setValueCount(1);
    }

    private static String asText(int index, Object value) {
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        throw mismatch(index, value, "text");
    }

    private static Number asNumber(int index, Object value) {
        if (value instanceof Number number) {
            return number;
        }
        throw mismatch(index, value, "a number");
    }

    private static PravahaClientException mismatch(int index, Object value, String wanted) {
        return new PravahaClientException(
                ClientErrors.QUERY_REFUSED,
                "?" + (index + 1) + " needs " + wanted + ", but a "
                        + value.getClass().getSimpleName() + " was given",
                false);
    }
}

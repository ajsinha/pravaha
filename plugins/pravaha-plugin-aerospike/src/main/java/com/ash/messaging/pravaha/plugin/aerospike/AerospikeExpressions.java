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

import com.aerospike.client.exp.Exp;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;

/**
 * Turning the engine's pushed filters into Aerospike expressions.
 *
 * <p>This is where most of this plugin's value is. A predicate evaluated in Aerospike is bytes that
 * never cross the network and ingest CPU that is never spent; the same predicate evaluated after a
 * full scan costs both. Design section 2 calls store-native pushdown the thing that changes the cost
 * curve of the whole system, and this is that, for the primary target.
 *
 * <p><strong>Untranslatable means absent, never approximated.</strong> Returning null leaves the
 * filter with the engine, which costs bandwidth. Returning an expression that is nearly right costs
 * rows, and a row the store declined to send is indistinguishable from one that was never written.
 * Every branch that cannot be exact returns null.
 *
 * <p>Bin types matter more here than in SQL. Aerospike compares an integer bin and a string bin with
 * different expression constructors, and asking for the wrong one does not fail -- it returns
 * nothing. So the declared schema decides which comparison is built, and a bin whose declared type
 * has no Aerospike comparison is not pushed at all.
 */
final class AerospikeExpressions {

    private AerospikeExpressions() {}

    /** One filter, or null if Aerospike cannot express it exactly. */
    static Exp translate(ReadRequest.Filter filter, StreamSchema schema) {
        int ordinal = ordinalOf(schema, filter.column());
        if (ordinal < 0) {
            // A filter naming a bin this stream does not declare. Not an error -- the engine may be
            // filtering on a column added by a projection -- but nothing to push.
            return null;
        }
        String bin = schema.field(ordinal).name();

        if (filter.comparison() == ReadRequest.Comparison.IS_NULL) {
            // Aerospike does not store absent bins, so "is null" is "the bin does not exist".
            return Exp.not(Exp.binExists(bin));
        }
        if (filter.comparison() == ReadRequest.Comparison.IS_NOT_NULL) {
            return Exp.binExists(bin);
        }

        return switch (schema.field(ordinal).type().typeName()) {
            case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ -> {
                Long value = asLong(filter.value());
                yield value == null ? null : compare(filter.comparison(), Exp.intBin(bin), Exp.val(value));
            }
            case FLOAT32, FLOAT64 -> {
                Double value = asDouble(filter.value());
                yield value == null ? null : compare(filter.comparison(), Exp.floatBin(bin), Exp.val(value));
            }
            case STRING -> {
                if (!(filter.value() instanceof String text)) {
                    yield null;
                }
                // Only equality and inequality. Aerospike compares strings byte-wise, and whether
                // that agrees with the engine's ordering depends on a collation neither side has
                // declared -- so an ordered comparison that looked right would be right only for
                // ASCII, and wrong quietly for everything else.
                yield switch (filter.comparison()) {
                    case EQ -> Exp.eq(Exp.stringBin(bin), Exp.val(text));
                    case NE -> Exp.ne(Exp.stringBin(bin), Exp.val(text));
                    default -> null;
                };
            }
            case BOOLEAN -> {
                if (!(filter.value() instanceof Boolean flag)) {
                    yield null;
                }
                yield switch (filter.comparison()) {
                    case EQ -> Exp.eq(Exp.boolBin(bin), Exp.val(flag));
                    case NE -> Exp.ne(Exp.boolBin(bin), Exp.val(flag));
                    default -> null;
                };
            }
            // Blobs, lists and maps have no ordering anybody agreed on, and DECIMAL has no
            // representation at all. Left with the engine.
            default -> null;
        };
    }

    private static Exp compare(ReadRequest.Comparison comparison, Exp bin, Exp value) {
        return switch (comparison) {
            case EQ -> Exp.eq(bin, value);
            case NE -> Exp.ne(bin, value);
            case LT -> Exp.lt(bin, value);
            case LE -> Exp.le(bin, value);
            case GT -> Exp.gt(bin, value);
            case GE -> Exp.ge(bin, value);
            default -> null;
        };
    }

    private static int ordinalOf(StreamSchema schema, String column) {
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            if (schema.field(ordinal).name().equals(column)) {
                return ordinal;
            }
        }
        return -1;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

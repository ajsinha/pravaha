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

import java.util.ArrayList;
import java.util.List;

import com.aerospike.client.command.ParticleType;
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
                Exp equals = stringEquals(bin, text);
                // Only equality and inequality. Aerospike compares strings byte-wise, and whether
                // that agrees with the engine's ordering depends on a collation neither side has
                // declared -- so an ordered comparison that looked right would be right only for
                // ASCII, and wrong quietly for everything else.
                yield switch (filter.comparison()) {
                    case EQ -> equals;
                    // Existence as well as inequality. An absent bin reads as NULL, and the
                    // engine's own <> drops NULL rather than keeping it, so a pushdown that kept
                    // those records would only make the engine throw them away again.
                    case NE -> equals == null ? null : Exp.and(Exp.binExists(bin), Exp.not(equals));
                    default -> null;
                };
            }
            case BOOLEAN -> {
                if (!(filter.value() instanceof Boolean flag)) {
                    yield null;
                }
                Exp equals = booleanEquals(bin, flag);
                yield switch (filter.comparison()) {
                    case EQ -> equals;
                    case NE -> Exp.and(Exp.binExists(bin), Exp.not(equals));
                    default -> null;
                };
            }
            // Blobs, lists and maps have no ordering anybody agreed on, and DECIMAL has no
            // representation at all. Left with the engine.
            default -> null;
        };
    }

    /**
     * A BOOLEAN bin equalling {@code flag}, whichever way the value was stored.
     *
     * <p>Aerospike only grew a boolean particle in server 5.6; before that a boolean was an integer,
     * and {@code AerospikeSchemas.copyInto} still reads one as {@code != 0} because plenty of live
     * data looks like that. {@code Exp.boolBin} does not: a filter that only asked for the boolean
     * particle excluded every legacy record, and the store sent one row where the engine kept two.
     *
     * <p>Guarded by the particle type rather than relying on what a mistyped comparison does. Both
     * arms are then exactly {@code copyInto}'s two readings of a boolean, which is what makes this
     * pushdown exact rather than nearly right.
     */
    private static Exp booleanEquals(String bin, boolean flag) {
        Exp asBoolean =
                Exp.and(Exp.eq(Exp.binType(bin), Exp.val(ParticleType.BOOL)), Exp.eq(Exp.boolBin(bin), Exp.val(flag)));
        Exp asInteger = Exp.and(
                Exp.eq(Exp.binType(bin), Exp.val(ParticleType.INTEGER)),
                flag ? Exp.ne(Exp.intBin(bin), Exp.val(0)) : Exp.eq(Exp.intBin(bin), Exp.val(0)));
        return Exp.or(asBoolean, asInteger);
    }

    /**
     * A STRING bin equalling {@code text}, or null when that cannot be decided in the store.
     *
     * <p>A STRING column is where {@code copyInto} is at its most permissive: the default branch
     * renders <em>whatever the bin holds</em> with {@code String.valueOf}. So a bin holding the
     * integer 42, declared STRING, reads as "42" and matches {@code = '42'} -- and a pushdown that
     * only compared the string particle excluded it, losing a row the engine would have kept.
     *
     * <p>Each particle whose rendering can be reproduced exactly gets an arm. A literal that could
     * be the rendering of a list, a map or a blob gets no expression at all: {@code String.valueOf}
     * of a container is a Java formatting decision, not something to reimplement in an expression
     * and hope stays in step. Leaving it with the engine costs bandwidth, which is the side to err
     * on.
     */
    private static Exp stringEquals(String bin, String text) {
        List<Exp> arms = new ArrayList<>(4);
        arms.add(Exp.and(
                Exp.eq(Exp.binType(bin), Exp.val(ParticleType.STRING)), Exp.eq(Exp.stringBin(bin), Exp.val(text))));
        // Round-tripped, not merely parsed. "007" parses as 7 and renders as "7", so a record
        // holding 7 does not read as "007" and must not be matched by one.
        try {
            long asLong = Long.parseLong(text);
            if (String.valueOf(asLong).equals(text)) {
                arms.add(Exp.and(
                        Exp.eq(Exp.binType(bin), Exp.val(ParticleType.INTEGER)),
                        Exp.eq(Exp.intBin(bin), Exp.val(asLong))));
            }
        } catch (NumberFormatException notAnInteger) {
            // Not an integer literal, so no integer bin can render as it. Nothing to add.
        }
        try {
            double asDouble = Double.parseDouble(text);
            if (String.valueOf(asDouble).equals(text)) {
                arms.add(Exp.and(
                        Exp.eq(Exp.binType(bin), Exp.val(ParticleType.DOUBLE)),
                        Exp.eq(Exp.floatBin(bin), Exp.val(asDouble))));
            }
        } catch (NumberFormatException notADouble) {
            // Likewise.
        }
        if ("true".equals(text) || "false".equals(text)) {
            arms.add(Exp.and(
                    Exp.eq(Exp.binType(bin), Exp.val(ParticleType.BOOL)),
                    Exp.eq(Exp.boolBin(bin), Exp.val(Boolean.parseBoolean(text)))));
        }
        if (couldRenderAContainer(text)) {
            return null;
        }
        return arms.size() == 1 ? arms.get(0) : Exp.or(arms.toArray(new Exp[0]));
    }

    /**
     * Whether {@code text} could be how Java renders a list, a map or a byte array.
     *
     * <p>Deliberately coarse. The question is not what these render as -- it is whether this
     * literal is close enough to that shape to be worth refusing, and a literal that merely looks
     * like one costs a scan's bandwidth rather than a row.
     */
    private static boolean couldRenderAContainer(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return first == '[' || first == '{' || text.startsWith("null");
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

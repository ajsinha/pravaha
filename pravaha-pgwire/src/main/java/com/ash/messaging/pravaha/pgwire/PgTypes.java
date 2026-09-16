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
package com.ash.messaging.pravaha.pgwire;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * Pravaha's types as PostgreSQL sees them: an OID for {@code RowDescription}, and a value as text.
 *
 * <p>This is the pgwire twin of {@code ArrowSchemas} in {@code pravaha-flight}, and it is written
 * from the same rule: <strong>a type this gateway cannot encode is refused by name, not
 * approximated</strong>. A wrong number that arrives looking like a right number is worse than a
 * refusal, because nobody goes looking for it.
 *
 * <h2>Where this deliberately differs from the Arrow mapping</h2>
 *
 * <p><strong>{@code DECIMAL} is supported here and refused there</strong>, and that is not an
 * inconsistency. Arrow refuses it because the engine's two-word decimal cannot reach an Arrow
 * decimal vector without a rounding decision, and a serialiser must not make that decision for a
 * ledger. The text protocol has no such problem: {@code ViewQuery} hands back a {@link BigDecimal}
 * (finding TY-19), {@code BigDecimal.toPlainString()} is exact by construction, and {@code numeric}
 * is arbitrary precision at the far end too. Nothing is rounded, so there is nothing to decide.
 *
 * <p><strong>{@code BYTES} and {@code TIME} are refused here and accepted there</strong> -- and
 * "accepted there" is exactly the problem. {@code docs/CONTINUOUS_QUERIES.md} section 16 records
 * that both are declarable, both compute correctly, and both <em>crash on serialisation of a
 * non-null value</em> to a real client (findings TY-17 and TY-18). Claiming them here would be
 * claiming support this gateway has never once delivered. When the serving path is proven to hand
 * back a {@code byte[]} for BYTES and a nanosecond-of-day {@code long} for TIME, the two cases
 * below are four lines each -- {@code bytea} in hex format, and {@code time} as {@code HH:mm:ss} --
 * and they should be added then, with a test that puts a non-null value of each through a socket.
 */
final class PgTypes {

    // The OIDs are the fixed catalogue numbers from PostgreSQL's pg_type. They are part of the
    // protocol, not of any particular server's catalogue, so they are constants rather than
    // something to look up.
    static final int OID_BOOL = 16;
    static final int OID_INT8 = 20;
    static final int OID_INT2 = 21;
    static final int OID_INT4 = 23;
    static final int OID_TEXT = 25;
    static final int OID_FLOAT4 = 700;
    static final int OID_FLOAT8 = 701;
    static final int OID_NUMERIC = 1700;
    static final int OID_DATE = 1082;
    static final int OID_TIMESTAMPTZ = 1184;

    /** {@code RowDescription}'s "no modifier". */
    static final int NO_TYPE_MODIFIER = -1;

    /** PostgreSQL's varlena header, which every {@code atttypmod} for a varlena type includes. */
    private static final int VARHDRSZ = 4;

    private PgTypes() {}

    /**
     * The OID a client will see for this column, or a refusal naming the column.
     *
     * <p>Named, because the refusal was useless without it: a client saw which <em>type</em> could
     * not be sent and had to work out which column carried it, on a schema it may not have written.
     * The same lesson {@code ArrowSchemas} learned.
     */
    static int oidOf(Field field) {
        try {
            return oidOf(field.type().typeName());
        } catch (PravahaException e) {
            throw new PravahaException(
                    PgWireErrors.UNSUPPORTED_TYPE, "column '" + field.name() + "': " + e.getMessage(), e);
        }
    }

    static int oidOf(TypeName typeName) {
        return switch (typeName) {
            case BOOLEAN -> OID_BOOL;
            // PostgreSQL has no one-byte integer, so TINYINT widens to int2. Widening is lossless
            // and every value still round-trips; the alternative -- refusing INT8 outright -- would
            // make a perfectly ordinary column unreadable to protect a distinction no PostgreSQL
            // client has a name for.
            case INT8, INT16 -> OID_INT2;
            case INT32 -> OID_INT4;
            case INT64 -> OID_INT8;
            case FLOAT32 -> OID_FLOAT4;
            case FLOAT64 -> OID_FLOAT8;
            case DECIMAL -> OID_NUMERIC;
            case STRING -> OID_TEXT;
            case DATE -> OID_DATE;
            // The engine holds UTC nanoseconds since epoch (ADR-012), which is an instant, so
            // timestamptz rather than timestamp. Sending it as the unzoned type would let a client
            // apply its own session zone to a value that already has one, and be wrong by hours.
            case TIMESTAMP_LTZ -> OID_TIMESTAMPTZ;
            case BYTES ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_TYPE,
                        "BYTES is not something Pravaha puts on a client wire yet. It is declarable "
                                + "and it computes, but serialising a non-null value to a real client has "
                                + "never worked (docs/CONTINUOUS_QUERIES.md section 16, finding TY-17), so "
                                + "this gateway refuses the column rather than claim a bytea it cannot fill.");
            case TIME ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_TYPE,
                        "TIME is not something Pravaha puts on a client wire yet. It is declarable "
                                + "and it computes, but serialising a non-null value to a real client has "
                                + "never worked (docs/CONTINUOUS_QUERIES.md section 16, finding TY-18), so "
                                + "this gateway refuses the column rather than claim a time it cannot fill.");
            default ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_TYPE,
                        typeName + " has no PostgreSQL text encoding in this gateway. ARRAY, MAP and "
                                + "ROW would each need a composite or array encoding, and an approximation "
                                + "-- the value's toString, say -- would arrive looking like data.");
        };
    }

    /**
     * The fixed width {@code RowDescription} declares, or -1 for a variable-width type.
     *
     * <p>Advisory: clients size buffers with it and none of them trust it for correctness. It is
     * filled in anyway because {@code -1} for {@code int4} is a small lie that costs a client an
     * allocation strategy.
     */
    static short typeSizeOf(TypeName typeName) {
        return switch (typeName) {
            case BOOLEAN -> 1;
            case INT8, INT16 -> 2;
            case INT32, FLOAT32, DATE -> 4;
            case INT64, FLOAT64, TIMESTAMP_LTZ -> 8;
            default -> -1;
        };
    }

    /**
     * The {@code atttypmod} for a column: the declared precision and scale, where there is one.
     *
     * <p>Only {@code numeric} has anything to say. A client renders {@code NUMERIC(18,2)} from
     * this, and a {@code -1} here makes every decimal column display as unconstrained -- which is a
     * different schema from the one the view actually has.
     */
    static int typeModifierOf(Field field) {
        if (field.type() instanceof DecimalType decimal) {
            return ((decimal.precision() << 16) | (decimal.scale() & 0xffff)) + VARHDRSZ;
        }
        return NO_TYPE_MODIFIER;
    }

    /**
     * One value in PostgreSQL's text format, or {@code null} for SQL NULL.
     *
     * <p>Text rather than binary for slice 1. Binary saves a parse at the far end and is worth
     * having later; it is an optimisation, and getting the text form exactly right first is what
     * makes the binary form checkable against something.
     *
     * <p>{@code null} here means NULL and is written as a {@code -1} length in {@code DataRow},
     * which is the protocol's own distinction between "absent" and "empty string". They are
     * different values and a client that cannot tell them apart will eventually total them
     * differently.
     */
    static byte[] encode(TypeName typeName, Object value) {
        if (value == null) {
            return null;
        }
        String text =
                switch (typeName) {
                    // 't' and 'f', which is what PostgreSQL's text output for bool is. "true"/"false" is
                    // accepted on input but is not what a client comparing against pg_catalog expects back.
                    case BOOLEAN -> ((Boolean) value) ? "t" : "f";
                    case INT8, INT16, INT32, INT64 -> String.valueOf(((Number) value).longValue());
                    case FLOAT32 -> floatText(((Number) value).floatValue());
                    case FLOAT64 -> doubleText(((Number) value).doubleValue());
                    // toPlainString, not toString: toString switches to scientific notation for small
                    // scales, and "1E+2" is a legal numeric literal that displays as something no operator
                    // asked for. Plain is exact and unsurprising.
                    case DECIMAL -> ((BigDecimal) value).toPlainString();
                    case DATE ->
                        LocalDate.ofEpochDay(((Number) value).longValue()).toString();
                    case TIMESTAMP_LTZ -> timestampText(((Number) value).longValue());
                    case STRING -> value.toString();
                    // Unreachable: oidOf refused this column before any row was read. Kept as a refusal
                    // rather than a fallthrough to toString, because a fallthrough is how an unsupported
                    // type quietly starts arriving as its Java debug form.
                    default ->
                        throw new PravahaException(
                                PgWireErrors.UNSUPPORTED_TYPE,
                                typeName + " has no PostgreSQL text encoding in this gateway.");
                };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Special float values by PostgreSQL's spelling, not Java's.
     *
     * <p>Java and PostgreSQL agree on {@code NaN} and on {@code Infinity} since PostgreSQL 12, so
     * the mapping is a pass-through today -- written out anyway so that it is a decision on the
     * page rather than a coincidence somebody later has to re-derive.
     */
    private static String floatText(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        if (Float.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return Float.toString(value);
    }

    private static String doubleText(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return Double.toString(value);
    }

    /**
     * A UTC instant as {@code yyyy-MM-dd HH:mm:ss[.fffffffff]+00}.
     *
     * <p>Space rather than {@code T}, and a {@code +00} offset: that is PostgreSQL's ISO DateStyle,
     * which is what this server announces in its {@code ParameterStatus}, and a client that parses
     * what it was told to expect will parse this.
     *
     * <p><strong>Nine fractional digits where the value has them.</strong> PostgreSQL's own
     * {@code timestamptz} is microseconds and would truncate; the engine holds nanoseconds and
     * being deliberate about keeping them is the whole of ADR-012. Trailing zeros are trimmed, so a
     * value that is a whole second prints as a whole second and a microsecond-resolution value
     * prints with six digits -- byte-identical to what a real PostgreSQL would send. Only a value
     * that genuinely carries sub-microsecond detail prints more, and a client that rounds it has
     * still been told the truth.
     */
    private static String timestampText(long epochNanos) {
        LocalDateTime utc = LocalDateTime.ofEpochSecond(
                Math.floorDiv(epochNanos, 1_000_000_000L),
                (int) Math.floorMod(epochNanos, 1_000_000_000L),
                ZoneOffset.UTC);
        StringBuilder text = new StringBuilder(35);
        text.append(String.format(
                "%04d-%02d-%02d %02d:%02d:%02d",
                utc.getYear(),
                utc.getMonthValue(),
                utc.getDayOfMonth(),
                utc.getHour(),
                utc.getMinute(),
                utc.getSecond()));
        int nano = utc.getNano();
        if (nano != 0) {
            String fraction = String.format("%09d", nano);
            int end = fraction.length();
            while (end > 0 && fraction.charAt(end - 1) == '0') {
                end--;
            }
            text.append('.').append(fraction, 0, end);
        }
        return text.append("+00").toString();
    }
}

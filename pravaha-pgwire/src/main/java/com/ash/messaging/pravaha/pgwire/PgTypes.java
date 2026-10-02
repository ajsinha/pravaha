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
 * "accepted there" is exactly the problem. {@code docs/guides/CONTINUOUS_QUERIES.md} section 16 records
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
                                + "never worked (docs/guides/CONTINUOUS_QUERIES.md section 16, finding TY-17), so "
                                + "this gateway refuses the column rather than claim a bytea it cannot fill.");
            case TIME ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_TYPE,
                        "TIME is not something Pravaha puts on a client wire yet. It is declarable "
                                + "and it computes, but serialising a non-null value to a real client has "
                                + "never worked (docs/guides/CONTINUOUS_QUERIES.md section 16, finding TY-18), so "
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
     * <p>Text is the default, and what psql and pgjdbc ask for. {@link #encodeBinary} is the other
     * format, for the clients -- Npgsql, so Power BI -- that ask for binary.
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

    // -------------------------------------------------------------------------------------
    // Binary results: what Npgsql -- and so Power BI -- asks for by default.

    /**
     * One value in PostgreSQL's <em>binary</em> format, or {@code null} for SQL NULL.
     *
     * <p>Npgsql, the driver inside Power BI's PostgreSQL connector, asks for binary results for
     * every column of every ordinary query ({@code Bind} with the single result format code 1), and
     * never offers text instead; a gateway that refused binary was a gateway Power BI could not read.
     * These are the formats PostgreSQL's own {@code *send} functions write, one per type this gateway
     * puts on the wire at all -- the same set {@link #oidOf} admits, and nothing it refuses.
     *
     * <p><strong>{@code timestamptz} is microseconds here, and that is the one lossy step.</strong>
     * The binary format is a 64-bit count of microseconds since 2000-01-01 and has no room for the
     * nanoseconds the engine holds (ADR-012); a value with sub-microsecond digits is truncated toward
     * the past, exactly as {@code floor} would, never rounded up into the next microsecond. A client
     * that needs the nanoseconds reads the text format, where {@link #timestampText} keeps all nine
     * digits. PostgreSQL itself has no finer resolution, so no PostgreSQL client that asked for
     * binary expected more.
     */
    static byte[] encodeBinary(TypeName typeName, Object value) {
        if (value == null) {
            return null;
        }
        return switch (typeName) {
            case BOOLEAN -> new byte[] {(byte) (((Boolean) value) ? 1 : 0)};
            case INT8, INT16 -> bigEndian(((Number) value).longValue(), 2);
            case INT32 -> bigEndian(((Number) value).longValue(), 4);
            case INT64 -> bigEndian(((Number) value).longValue(), 8);
            case FLOAT32 -> bigEndian(Float.floatToIntBits(((Number) value).floatValue()), 4);
            case FLOAT64 -> bigEndian(Double.doubleToLongBits(((Number) value).doubleValue()), 8);
            case DECIMAL -> numericBinary((BigDecimal) value);
            case STRING -> value.toString().getBytes(StandardCharsets.UTF_8);
            case DATE -> bigEndian(((Number) value).longValue() - POSTGRES_EPOCH_DAYS, 4);
            case TIMESTAMP_LTZ ->
                bigEndian(
                        Math.floorDiv(((Number) value).longValue(), 1_000L) - POSTGRES_EPOCH_DAYS * MICROS_PER_DAY, 8);
            default ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_TYPE,
                        typeName + " has no PostgreSQL binary encoding in this gateway.");
        };
    }

    private static byte[] bigEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int i = width - 1; i >= 0; i--) {
            bytes[i] = (byte) value;
            value >>= 8;
        }
        return bytes;
    }

    private static final short NUMERIC_POSITIVE = 0x0000;
    private static final short NUMERIC_NEGATIVE = 0x4000;

    /**
     * PostgreSQL's binary {@code numeric}: {@code ndigits}, {@code weight}, {@code sign}, {@code
     * dscale}, then {@code ndigits} base-10000 digits, most significant first. {@code weight} is the
     * power of 10000 of the first digit; {@code dscale} is the number of decimal digits after the
     * point, which is what keeps {@code 1200.00} from arriving as {@code 1200}. Exact by
     * construction: the digits are cut from the plain decimal string, never computed in floating
     * point.
     */
    static byte[] numericBinary(BigDecimal value) {
        BigDecimal normalized = value.scale() < 0 ? value.setScale(0) : value;
        int dscale = normalized.scale();
        String plain = normalized.abs().toPlainString();
        int point = plain.indexOf('.');
        String integerPart = point < 0 ? plain : plain.substring(0, point);
        String fractionPart = point < 0 ? "" : plain.substring(point + 1);

        // Left-pad the integer part and right-pad the fraction to whole groups of four digits.
        int integerGroups = (integerPart.length() + 3) / 4;
        String paddedInteger = "0".repeat(integerGroups * 4 - integerPart.length()) + integerPart;
        int fractionGroups = (fractionPart.length() + 3) / 4;
        String paddedFraction = fractionPart + "0".repeat(fractionGroups * 4 - fractionPart.length());
        String all = paddedInteger + paddedFraction;

        int[] groups = new int[integerGroups + fractionGroups];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = Integer.parseInt(all.substring(i * 4, i * 4 + 4));
        }
        int weight = integerGroups - 1;
        int first = 0;
        while (first < groups.length && groups[first] == 0) {
            first++;
            weight--;
        }
        int last = groups.length;
        while (last > first && groups[last - 1] == 0) {
            last--;
        }
        int ndigits = last - first;
        if (ndigits == 0) {
            weight = 0; // zero: no digits at all, which is how PostgreSQL itself writes it
        }
        short sign = normalized.signum() < 0 ? NUMERIC_NEGATIVE : NUMERIC_POSITIVE;

        byte[] bytes = new byte[8 + 2 * ndigits];
        writeShort(bytes, 0, ndigits);
        writeShort(bytes, 2, weight);
        writeShort(bytes, 4, sign);
        writeShort(bytes, 6, dscale);
        for (int i = 0; i < ndigits; i++) {
            writeShort(bytes, 8 + 2 * i, groups[first + i]);
        }
        return bytes;
    }

    private static void writeShort(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >> 8);
        bytes[at + 1] = (byte) value;
    }

    // -------------------------------------------------------------------------------------
    // Bind parameters: the read direction. Written from the same rule as encode: a type or a
    // format this gateway cannot decode is refused by name, not guessed at.

    /** Days between the Unix epoch and PostgreSQL's own (2000-01-01), for the binary date/time formats. */
    private static final long POSTGRES_EPOCH_DAYS = 10_957L;

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    /**
     * A {@code Bind} parameter's bytes, as the Java value {@link
     * com.ash.messaging.pravaha.sql.plan.BoundParameters} and {@code ViewQuery} expect for {@code
     * typeName} -- {@code null} for SQL NULL, which is the protocol's {@code -1} length and is
     * {@code bytes == null} by the time it reaches here (see {@code Bind}'s own parsing).
     *
     * <p>The type is the one {@code ParameterMetadata} inferred by planning the statement, not
     * whatever OID a client's {@code Parse} declared -- Pravaha's own planner is authoritative about
     * what a placeholder needs, the same way {@link #oidOf} is authoritative about what a column
     * is. A client's declared parameter OID decides only how a binary value's bytes are read: an
     * {@code int2} or {@code int4} against a {@code BIGINT} placeholder, a {@code float4} against a
     * {@code DOUBLE} one, is read at its own width and widened, as PostgreSQL coerces it
     * (PGINTPARAM-1: pgjdbc's {@code setInt}, psycopg's {@code %b} and Npgsql all send the narrower
     * type). A wider integer than the placeholder is accepted only when its value fits; nothing is
     * ever narrowed by truncation.
     *
     * @throws PravahaException {@link PgWireErrors#UNSUPPORTED_WIRE_FORMAT} for a binary-format
     *     value this gateway does not decode, {@link PgWireErrors#UNSUPPORTED_TYPE} for a
     *     placeholder type this gateway never puts on the wire in either direction
     */
    static Object decodeParameter(TypeName typeName, short format, byte[] bytes) {
        return decodeParameter(typeName, 0, format, bytes);
    }

    /**
     * As {@link #decodeParameter(TypeName, short, byte[])}, reading a binary value as the type the
     * client declared for it in {@code Parse} ({@code 0}: none, so the placeholder's own width).
     */
    static Object decodeParameter(TypeName typeName, int declaredOid, short format, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (typeName == TypeName.BYTES || typeName == TypeName.TIME) {
            // The same refusal encode() gives on the output side, for the same reason (TY-17/TY-18):
            // claiming to read a type this gateway has never once written correctly would be an
            // untested path exercised only by whichever client tries it first.
            throw new PravahaException(
                    PgWireErrors.UNSUPPORTED_TYPE,
                    typeName + " is not something Pravaha reads off a client wire yet; see PgTypes' own "
                            + "documentation for why, on the write side, which is the same reason here.");
        }
        return format == PgBackend.FORMAT_TEXT
                ? decodeText(typeName, new String(bytes, StandardCharsets.UTF_8))
                : decodeBinary(typeName, declaredOid, bytes);
    }

    private static Object decodeText(TypeName typeName, String text) {
        String trimmed = text.trim();
        return switch (typeName) {
            case BOOLEAN -> decodeBooleanText(trimmed);
            // Long for every integer width: BoundParameters.checkAssignable accepts Byte, Short,
            // Integer or Long for all of INT8/16/32/64, DATE, TIME and TIMESTAMP_LTZ alike, so one
            // Java type serves every one of them and the planner's own type is what actually governs
            // meaning.
            case INT8, INT16, INT32, INT64 -> Long.parseLong(trimmed);
            case FLOAT32, FLOAT64 -> Double.parseDouble(trimmed);
            case DECIMAL -> new BigDecimal(trimmed);
            case STRING -> text;
            case DATE -> LocalDate.parse(trimmed).toEpochDay();
            case TIMESTAMP_LTZ -> parseTimestamp(trimmed);
            default ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_TYPE, typeName + " has no PostgreSQL text decoding in this gateway.");
        };
    }

    private static boolean decodeBooleanText(String text) {
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "t", "true", "1", "y", "yes", "on" -> true;
            case "f", "false", "0", "n", "no", "off" -> false;
            default ->
                throw new PravahaException(
                        PgWireErrors.PROTOCOL_VIOLATION, "'" + text + "' is not a boolean this server recognises");
        };
    }

    /**
     * {@code yyyy-MM-dd[ |T]HH:mm:ss[.fraction][+HH[:mm]]}, the shape {@link #timestampText} writes
     * and the shape every client this gateway has been driven by sends back for a bound {@code
     * timestamptz} parameter. An offset is required, matching this server's own {@code
     * standard_conforming_strings}/{@code DateStyle} promise that every timestamp it deals in carries
     * one (ADR-012): a bare local timestamp would need a session time zone this server does not
     * track to mean anything.
     */
    private static long parseTimestamp(String text) {
        try {
            java.time.OffsetDateTime parsed = java.time.OffsetDateTime.parse(text.replace(' ', 'T'), TIMESTAMP_PARSER);
            java.time.Instant instant = parsed.toInstant();
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "'" + text + "' is not a timestamp this server can parse; expected "
                            + "yyyy-MM-dd HH:mm:ss[.fraction]+HH[:mm]",
                    malformed);
        }
    }

    private static final java.time.format.DateTimeFormatter TIMESTAMP_PARSER =
            new java.time.format.DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .appendOffset("+HH:mm", "+00")
                    .toFormatter();

    /**
     * The fixed-width binary formats a client may choose instead of text -- the primitive types
     * only. PostgreSQL's binary {@code date}/{@code timestamptz} count from 2000-01-01 rather than
     * 1970-01-01, which is the one translation here that is not simply "read the bytes".
     */
    private static Object decodeBinary(TypeName typeName, int declaredOid, byte[] bytes) {
        Object declared = decodeAsDeclared(typeName, declaredOid, bytes);
        if (declared != null) {
            return declared;
        }
        return switch (typeName) {
            case BOOLEAN -> bytes.length > 0 && bytes[0] != 0;
            case INT8, INT16 -> readInt(bytes, 2);
            case INT32 -> readInt(bytes, 4);
            case INT64 -> readInt(bytes, 8);
            case FLOAT32 -> (double) Float.intBitsToFloat((int) readInt(bytes, 4));
            case FLOAT64 -> Double.longBitsToDouble(readInt(bytes, 8));
            // A PostgreSQL text/varchar value is the same UTF-8 bytes whichever format code the
            // client declared; text has no separate binary encoding of its own.
            case STRING -> new String(bytes, StandardCharsets.UTF_8);
            case DATE -> readInt(bytes, 4) + POSTGRES_EPOCH_DAYS;
            case TIMESTAMP_LTZ -> Math.multiplyExact(readInt(bytes, 8) + POSTGRES_EPOCH_DAYS * MICROS_PER_DAY, 1_000L);
            default ->
                throw new PravahaException(
                        PgWireErrors.UNSUPPORTED_WIRE_FORMAT,
                        "a binary-format " + typeName + " parameter was sent, and this gateway only decodes "
                                + "binary for the fixed-width primitive types (booleans, integers, floats, "
                                + "text, date, timestamptz). Bind it as text instead -- every client this "
                                + "server has been driven by defaults to text unless told otherwise.");
        };
    }

    /**
     * A binary number read at the width the client declared and converted to the placeholder's type,
     * or {@code null} when the declaration says nothing this method decides (no OID, the placeholder's
     * own OID, or a type that is not a number) and the placeholder's width governs.
     *
     * <p>Exact or refused: an integer widens to any integer placeholder and an {@code int2}/{@code
     * int4} to a {@code DOUBLE} one (every such value is a double exactly), a {@code float4} to a
     * {@code DOUBLE}; a wider integer than the placeholder is accepted only when its value is in the
     * placeholder's range, and a {@code float8} is never narrowed to a {@code REAL}.
     */
    private static Object decodeAsDeclared(TypeName typeName, int declaredOid, byte[] bytes) {
        int declaredWidth =
                switch (declaredOid) {
                    case OID_INT2 -> 2;
                    case OID_INT4 -> 4;
                    case OID_INT8 -> 8;
                    default -> 0;
                };
        if (declaredWidth > 0) {
            return switch (typeName) {
                case INT8, INT16, INT32, INT64 -> {
                    long value = readInt(bytes, declaredWidth);
                    int width = typeName == TypeName.INT64 ? 8 : typeName == TypeName.INT32 ? 4 : 2;
                    if (declaredWidth > width && (value < -(1L << (width * 8 - 1)) || value >= 1L << (width * 8 - 1))) {
                        throw new PravahaException(
                                com.ash.messaging.pravaha.sql.SqlErrors.PARAMETER_TYPE,
                                "a binary " + typeNameOf(declaredOid) + " parameter holds " + value
                                        + ", outside the range of the " + typeName + " it is compared with");
                    }
                    yield value;
                }
                case FLOAT64 -> declaredWidth <= 4 ? (Object) (double) readInt(bytes, declaredWidth) : null;
                case FLOAT32 -> declaredWidth <= 2 ? (Object) (double) readInt(bytes, declaredWidth) : null;
                default -> null;
            };
        }
        if (declaredOid == OID_FLOAT4 && typeName == TypeName.FLOAT64) {
            return (double) Float.intBitsToFloat((int) readInt(bytes, 4));
        }
        return null;
    }

    private static String typeNameOf(int oid) {
        return switch (oid) {
            case OID_INT2 -> "int2";
            case OID_INT4 -> "int4";
            case OID_INT8 -> "int8";
            default -> "oid " + oid;
        };
    }

    /** A big-endian two's-complement integer of exactly {@code width} bytes, PostgreSQL's binary format. */
    private static long readInt(byte[] bytes, int width) {
        if (bytes.length != width) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "a binary parameter declared " + bytes.length + " bytes; this server expected " + width);
        }
        long value = bytes[0]; // sign-extends the first byte, which is exactly right for two's complement
        for (int i = 1; i < width; i++) {
            value = (value << 8) | (bytes[i] & 0xffL);
        }
        return value;
    }
}

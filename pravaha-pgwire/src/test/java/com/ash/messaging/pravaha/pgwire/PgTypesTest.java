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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The type mapping, asserted against PostgreSQL's numbers rather than against itself.
 *
 * <p>Every OID below is written out as a literal from {@code pg_type}. Reading them back out of
 * {@link PgTypes} would make this test accept whatever the code already does, which is not a test.
 */
class PgTypesTest {

    @Test
    void everyTypeThisGatewayClaimsHasItsPostgresOid() {
        assertThat(PgTypes.oidOf(TypeName.BOOLEAN)).isEqualTo(16);
        assertThat(PgTypes.oidOf(TypeName.INT16)).isEqualTo(21);
        assertThat(PgTypes.oidOf(TypeName.INT32)).isEqualTo(23);
        assertThat(PgTypes.oidOf(TypeName.INT64)).isEqualTo(20);
        assertThat(PgTypes.oidOf(TypeName.FLOAT32)).isEqualTo(700);
        assertThat(PgTypes.oidOf(TypeName.FLOAT64)).isEqualTo(701);
        assertThat(PgTypes.oidOf(TypeName.DECIMAL)).isEqualTo(1700);
        assertThat(PgTypes.oidOf(TypeName.STRING)).isEqualTo(25);
        assertThat(PgTypes.oidOf(TypeName.DATE)).isEqualTo(1082);
        assertThat(PgTypes.oidOf(TypeName.TIMESTAMP_LTZ)).isEqualTo(1184);
    }

    @Test
    void tinyintWidensToInt2BecausePostgresHasNoOneByteInteger() {
        // Widening, not refusing. Every INT8 value still round-trips through int2, and refusing the
        // column outright to preserve a distinction no PostgreSQL client has a name for would make
        // an ordinary column unreadable.
        assertThat(PgTypes.oidOf(TypeName.INT8)).isEqualTo(21);
        assertThat(PgTypes.encode(TypeName.INT8, (byte) -7)).asString().isEqualTo("-7");
    }

    @Test
    void timestampIsTheZonedTypeBecauseTheEngineHoldsAnInstant() {
        // 1184 timestamptz, not 1114 timestamp. The engine holds UTC nanoseconds, which is an
        // instant; sending the unzoned type would let a client apply its session zone to a value
        // that already has one and be wrong by hours.
        assertThat(PgTypes.oidOf(TypeName.TIMESTAMP_LTZ)).isEqualTo(1184);
        assertThat(PgTypes.oidOf(TypeName.TIMESTAMP_LTZ)).isNotEqualTo(1114);
    }

    @Test
    void bytesIsRefusedByNameWithTheFindingThatSaysWhy() {
        assertThatThrownBy(() -> PgTypes.oidOf(TypeName.BYTES))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6200")
                .hasMessageContaining("TY-17");
    }

    @Test
    void timeIsRefusedByNameWithTheFindingThatSaysWhy() {
        assertThatThrownBy(() -> PgTypes.oidOf(TypeName.TIME))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6200")
                .hasMessageContaining("TY-18");
    }

    @Test
    void theStructuredTypesAreRefusedRatherThanStringified() {
        // The refusal that matters most, because the obvious wrong answer -- value.toString() --
        // arrives looking like data.
        assertThatThrownBy(() -> PgTypes.oidOf(TypeName.ARRAY)).isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> PgTypes.oidOf(TypeName.MAP)).isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> PgTypes.oidOf(TypeName.ROW)).isInstanceOf(PravahaException.class);
    }

    @Test
    void aRefusalNamesTheColumnAndNotJustTheType() {
        // Without the name, a client saw which type could not be sent and had to work out which
        // column carried it, on a schema it may not have written.
        assertThatThrownBy(() -> PgTypes.oidOf(Field.of("captured_at", Types.time())))
                .hasMessageContaining("column 'captured_at'");
    }

    @Test
    void booleansAreTAndFWhichIsWhatPostgresSends() {
        assertThat(PgTypes.encode(TypeName.BOOLEAN, true)).asString().isEqualTo("t");
        assertThat(PgTypes.encode(TypeName.BOOLEAN, false)).asString().isEqualTo("f");
    }

    @Test
    void nullIsNullAndNotAnEmptyArray() {
        // PgBackend turns this null into the protocol's -1 length. An empty array here would become
        // a zero length, which is the empty string -- a different value.
        assertThat(PgTypes.encode(TypeName.STRING, null)).isNull();
        assertThat(PgTypes.encode(TypeName.STRING, "")).isEmpty();
    }

    @Test
    void decimalIsExactAndPlainRatherThanScientific() {
        // toString would give "1.2E+3" for this, which is a legal numeric literal that displays as
        // something nobody asked for. Nothing is rounded either way: that is why DECIMAL can be
        // sent here and cannot be sent through Arrow.
        assertThat(PgTypes.encode(TypeName.DECIMAL, new BigDecimal("1200.00")))
                .asString()
                .isEqualTo("1200.00");
        assertThat(PgTypes.encode(TypeName.DECIMAL, new BigDecimal("0.000000000000000000001")))
                .asString()
                .isEqualTo("0.000000000000000000001");
    }

    @Test
    void datesAreIsoDays() {
        assertThat(PgTypes.encode(TypeName.DATE, 0)).asString().isEqualTo("1970-01-01");
        assertThat(PgTypes.encode(TypeName.DATE, 20_712)).asString().isEqualTo("2026-09-16");
        assertThat(PgTypes.encode(TypeName.DATE, -1)).asString().isEqualTo("1969-12-31");
    }

    @Test
    void timestampsKeepWhateverPrecisionTheValueActuallyHas() {
        // A whole second prints as a whole second, and a microsecond value prints with six digits
        // -- byte-identical to a real PostgreSQL. Only a value carrying genuine sub-microsecond
        // detail prints nine, because truncating it would throw away the precision ADR-012 exists
        // to keep.
        assertThat(PgTypes.encode(TypeName.TIMESTAMP_LTZ, 0L)).asString().isEqualTo("1970-01-01 00:00:00+00");
        assertThat(PgTypes.encode(TypeName.TIMESTAMP_LTZ, 1_500_000_000L))
                .asString()
                .isEqualTo("1970-01-01 00:00:01.5+00");
        assertThat(PgTypes.encode(TypeName.TIMESTAMP_LTZ, 1_789_562_096_123_456_000L))
                .asString()
                .isEqualTo("2026-09-16 12:34:56.123456+00");
        assertThat(PgTypes.encode(TypeName.TIMESTAMP_LTZ, 1_789_562_096_123_456_789L))
                .asString()
                .isEqualTo("2026-09-16 12:34:56.123456789+00");
    }

    @Test
    void timestampsBeforeTheEpochDoNotRunBackwards() {
        // Floor division, not truncation toward zero: -1 nanosecond is one nanosecond *before* the
        // epoch, and the naive arithmetic puts it a second later than it belongs.
        assertThat(PgTypes.encode(TypeName.TIMESTAMP_LTZ, -1L))
                .asString()
                .isEqualTo("1969-12-31 23:59:59.999999999+00");
    }

    @Test
    void specialFloatsUsePostgresSpellingWhichIsAlsoJavas() {
        assertThat(PgTypes.encode(TypeName.FLOAT64, Double.NaN)).asString().isEqualTo("NaN");
        assertThat(PgTypes.encode(TypeName.FLOAT64, Double.POSITIVE_INFINITY))
                .asString()
                .isEqualTo("Infinity");
        assertThat(PgTypes.encode(TypeName.FLOAT32, Float.NEGATIVE_INFINITY))
                .asString()
                .isEqualTo("-Infinity");
    }

    @Test
    void textIsUtf8() {
        assertThat(PgTypes.encode(TypeName.STRING, "é中")).isEqualTo("é中".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void numericCarriesItsPrecisionAndScaleSoAClientRendersTheRightSchema() {
        // A -1 here makes every decimal column display as unconstrained, which is a different
        // schema from the one the view has.
        assertThat(PgTypes.typeModifierOf(Field.of("amount", Types.decimal(12, 4))))
                .isEqualTo(((12 << 16) | 4) + 4);
        assertThat(PgTypes.typeModifierOf(Field.of("name", Types.string()))).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ decodeParameter: Bind's own direction

    @Test
    void aNullParameterIsNullRegardlessOfType() {
        assertThat(PgTypes.decodeParameter(TypeName.STRING, PgBackend.FORMAT_TEXT, null))
                .isNull();
    }

    @Test
    void textParametersDecodeToTheJavaTypeBoundParametersAccepts() {
        // Long for every integer width, Double for every float width: BoundParameters.checkAssignable
        // accepts either across the whole family, so one Java type serves all of them and the
        // planner's own inferred type is what actually governs meaning.
        assertThat(PgTypes.decodeParameter(TypeName.INT32, PgBackend.FORMAT_TEXT, bytes("42")))
                .isEqualTo(42L);
        assertThat(PgTypes.decodeParameter(TypeName.INT64, PgBackend.FORMAT_TEXT, bytes("-7")))
                .isEqualTo(-7L);
        assertThat(PgTypes.decodeParameter(TypeName.FLOAT64, PgBackend.FORMAT_TEXT, bytes("3.5")))
                .isEqualTo(3.5);
        assertThat(PgTypes.decodeParameter(TypeName.STRING, PgBackend.FORMAT_TEXT, bytes("hello")))
                .isEqualTo("hello");
        assertThat(PgTypes.decodeParameter(TypeName.BOOLEAN, PgBackend.FORMAT_TEXT, bytes("t")))
                .isEqualTo(true);
        assertThat(PgTypes.decodeParameter(TypeName.BOOLEAN, PgBackend.FORMAT_TEXT, bytes("false")))
                .isEqualTo(false);
    }

    @Test
    void dateAndTimestampParametersDecodeToTheSameEpochEncodingEncodeWrites() {
        // Round-tripped through encode(): what Bind decodes and what a DataRow later encodes must
        // agree, or a client's own value would not read back as the value it sent.
        assertThat(PgTypes.decodeParameter(TypeName.DATE, PgBackend.FORMAT_TEXT, bytes("2026-09-16")))
                .isEqualTo(20_712L);
        assertThat(PgTypes.encode(TypeName.DATE, 20_712L)).asString().isEqualTo("2026-09-16");

        long nanos = (long)
                PgTypes.decodeParameter(TypeName.TIMESTAMP_LTZ, PgBackend.FORMAT_TEXT, bytes("2026-09-16 12:34:56+00"));
        assertThat(PgTypes.encode(TypeName.TIMESTAMP_LTZ, nanos)).asString().isEqualTo("2026-09-16 12:34:56+00");
    }

    @Test
    void aNarrowerBinaryNumberIsWidenedToThePlaceholdersType() {
        // PGINTPARAM-1: read at the width the client declared in Parse, then widened, as PostgreSQL does.
        short binary = PgBackend.FORMAT_BINARY;
        assertThat(PgTypes.decodeParameter(TypeName.INT64, PgTypes.OID_INT4, binary, new byte[] {0, 0, 2, 0x58}))
                .isEqualTo(600L);
        assertThat(PgTypes.decodeParameter(
                        TypeName.INT64, PgTypes.OID_INT2, binary, new byte[] {(byte) 0xff, (byte) 0x9c}))
                .isEqualTo(-100L);
        assertThat(PgTypes.decodeParameter(TypeName.INT32, PgTypes.OID_INT2, binary, new byte[] {0, 100}))
                .isEqualTo(100L);
        assertThat(PgTypes.decodeParameter(TypeName.FLOAT64, PgTypes.OID_INT4, binary, new byte[] {0, 0, 0, 7}))
                .isEqualTo(7.0);
        byte[] onePointFive = java.nio.ByteBuffer.allocate(4).putFloat(1.5f).array();
        assertThat(PgTypes.decodeParameter(TypeName.FLOAT64, PgTypes.OID_FLOAT4, binary, onePointFive))
                .isEqualTo(1.5);
        // The placeholder's own type, and no declaration at all, read at the placeholder's width as before.
        byte[] eight = java.nio.ByteBuffer.allocate(8).putLong(9L).array();
        assertThat(PgTypes.decodeParameter(TypeName.INT64, PgTypes.OID_INT8, binary, eight))
                .isEqualTo(9L);
        assertThat(PgTypes.decodeParameter(TypeName.INT64, 0, binary, eight)).isEqualTo(9L);
    }

    @Test
    void aWiderBinaryIntegerIsAcceptedOnlyWhenItsValueFits() {
        short binary = PgBackend.FORMAT_BINARY;
        byte[] small = java.nio.ByteBuffer.allocate(8).putLong(42L).array();
        assertThat(PgTypes.decodeParameter(TypeName.INT32, PgTypes.OID_INT8, binary, small))
                .isEqualTo(42L);
        byte[] big = java.nio.ByteBuffer.allocate(8).putLong(1L << 40).array();
        assertThatThrownBy(() -> PgTypes.decodeParameter(TypeName.INT32, PgTypes.OID_INT8, binary, big))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2062")
                .hasMessageContaining("outside the range of the INT32");
    }

    @Test
    void aDeclaredWidthTheBytesDoNotHaveIsStillAProtocolViolation() {
        assertThatThrownBy(() -> PgTypes.decodeParameter(
                        TypeName.INT64, PgTypes.OID_INT4, PgBackend.FORMAT_BINARY, new byte[] {0, 1}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6202")
                .hasMessageContaining("expected 4");
        // Undeclared, the placeholder's width governs, as before.
        assertThatThrownBy(() ->
                        PgTypes.decodeParameter(TypeName.INT64, 0, PgBackend.FORMAT_BINARY, new byte[] {0, 0, 0, 1}))
                .hasMessageContaining("PRV-6202");
    }

    @Test
    void bytesAndTimeAreRefusedAsParametersTooForTheSameReasonAsOutput() {
        assertThatThrownBy(() -> PgTypes.decodeParameter(TypeName.BYTES, PgBackend.FORMAT_TEXT, bytes("x")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6200");
        assertThatThrownBy(() -> PgTypes.decodeParameter(TypeName.TIME, PgBackend.FORMAT_TEXT, bytes("x")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6200");
    }

    @Test
    void binaryFormatDecodesTheFixedWidthPrimitivesPostgresWireFormat() {
        // PostgreSQL's own binary encodings: big-endian two's complement, IEEE-754 big-endian, and
        // (for date/timestamptz) counted from 2000-01-01 rather than 1970-01-01.
        assertThat(PgTypes.decodeParameter(TypeName.INT32, (short) 1, new byte[] {0, 0, 0, 42}))
                .isEqualTo(42L);
        assertThat(PgTypes.decodeParameter(TypeName.BOOLEAN, (short) 1, new byte[] {1}))
                .isEqualTo(true);
        assertThat(PgTypes.decodeParameter(TypeName.FLOAT64, (short) 1, longBytes(Double.doubleToLongBits(2.5))))
                .isEqualTo(2.5);
    }

    @Test
    void binaryFormatForAnUnsupportedTypeIsRefusedByNameRatherThanMisread() {
        assertThatThrownBy(() -> PgTypes.decodeParameter(TypeName.DECIMAL, (short) 1, new byte[] {1, 2, 3}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6209");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] longBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xff);
            value >>= 8;
        }
        return out;
    }
}

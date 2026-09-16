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
        assertThat(PgTypes.encode(TypeName.BOOLEAN, Boolean.TRUE)).asString().isEqualTo("t");
        assertThat(PgTypes.encode(TypeName.BOOLEAN, Boolean.FALSE)).asString().isEqualTo("f");
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
}

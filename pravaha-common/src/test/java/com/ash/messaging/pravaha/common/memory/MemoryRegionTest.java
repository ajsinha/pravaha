/*
 * Copyright the Pravaha authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link MemoryRegion} contract, run against every implementation this JVM can initialise.
 *
 * <p>Whichever one {@link MemoryAccess#best()} selects, behaviour must be identical -- that is the
 * entire point of the seam, and testing only the default would let the others drift.
 */
class MemoryRegionTest {

    /** Agrona appears here only when the JVM was given its {@code --add-exports} flag. */
    static Stream<MemoryAccess> implementations() {
        List<MemoryAccess> all = new ArrayList<>();
        all.add(ByteBufferMemoryAccess.INSTANCE);
        if (AgronaMemoryAccess.isAvailable()) {
            all.add(AgronaMemoryAccess.INSTANCE);
        }
        return all.stream();
    }

    @Test
    void theDefaultImplementationNeedsNoJvmFlags() {
        // Agrona 2.x reaches jdk.internal.misc.Unsafe and so needs --add-exports. It therefore
        // cannot be the default: an embedded engine inherits its host application's launch
        // arguments (design section 22.1). The flag-free implementation must always be the one chosen.
        assertThat(MemoryAccess.best().name()).isEqualTo("bytebuffer");
        try (MemoryRegion r = MemoryAccess.best().allocate(64)) {
            r.putLong(0, 0x0123456789ABCDEFL);
            assertThat(r.getLong(0)).isEqualTo(0x0123456789ABCDEFL);
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void newRegionIsZeroed(MemoryAccess access) {
        // Rows rely on the null bitmap starting clear. An un-zeroed region surfaces as sporadically
        // wrong nullability, which is a miserable class of bug to chase later.
        try (MemoryRegion region = access.allocate(4096)) {
            for (int i = 0; i < region.capacity(); i += 8) {
                assertThat(region.getLong(i)).isZero();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void roundTripsEveryPrimitiveWidth(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096)) {
            region.putBoolean(0, true);
            region.putByte(1, (byte) -128);
            region.putShort(2, (short) -32768);
            region.putInt(4, Integer.MIN_VALUE);
            region.putLong(8, Long.MIN_VALUE);
            region.putFloat(16, 3.5f);
            region.putDouble(24, -2.5d);

            assertThat(region.getBoolean(0)).isTrue();
            assertThat(region.getByte(1)).isEqualTo((byte) -128);
            assertThat(region.getShort(2)).isEqualTo((short) -32768);
            assertThat(region.getInt(4)).isEqualTo(Integer.MIN_VALUE);
            assertThat(region.getLong(8)).isEqualTo(Long.MIN_VALUE);
            assertThat(region.getFloat(16)).isEqualTo(3.5f);
            assertThat(region.getDouble(24)).isEqualTo(-2.5d);
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void booleanIsFalseForZeroAndTrueForAnyNonZeroByte(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(64)) {
            region.putByte(0, (byte) 0);
            assertThat(region.getBoolean(0)).isFalse();
            region.putByte(0, (byte) 7);
            assertThat(region.getBoolean(0)).isTrue();
            region.putBoolean(0, false);
            assertThat(region.getByte(0)).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void byteOrderIsLittleEndianRegardlessOfPlatform(MemoryAccess access) {
        // The row layout is checkpointed and shipped between nodes. Inheriting platform order would
        // make a checkpoint unportable in a way nobody notices until a mixed cluster is wrong.
        try (MemoryRegion region = access.allocate(64)) {
            region.putInt(0, 0x01020304);
            assertThat(region.getByte(0)).isEqualTo((byte) 0x04);
            assertThat(region.getByte(1)).isEqualTo((byte) 0x03);
            assertThat(region.getByte(2)).isEqualTo((byte) 0x02);
            assertThat(region.getByte(3)).isEqualTo((byte) 0x01);
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void bulkTransfersRoundTrip(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096)) {
            byte[] src = "COMPLETED".getBytes(StandardCharsets.UTF_8);
            region.putBytes(100, src, 0, src.length);
            byte[] dst = new byte[src.length];
            region.getBytes(100, dst, 0, dst.length);
            assertThat(dst).isEqualTo(src);
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void equalsBytesComparesUtf8WithoutMaterialisingAString(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096)) {
            byte[] literal = "COMPLETED".getBytes(StandardCharsets.UTF_8);
            region.putBytes(64, literal, 0, literal.length);

            assertThat(region.equalsBytes(64, literal)).isTrue();
            assertThat(region.equalsBytes(64, "COMPLETEX".getBytes(StandardCharsets.UTF_8)))
                    .isFalse();
            assertThat(region.equalsBytes(65, literal)).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void equalsBytesRejectsOutOfBoundsInsteadOfReadingPastTheEnd(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096)) {
            byte[] literal = new byte[8];
            assertThat(region.equalsBytes(region.capacity() - 4, literal)).isFalse();
            assertThat(region.equalsBytes(-1, literal)).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void copiesBetweenRegions(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096);
                MemoryRegion other = access.allocate(256)) {
            other.putLong(0, 0xCAFEBABEL);
            region.copyFrom(32, other, 0, 8);
            assertThat(region.getLong(32)).isEqualTo(0xCAFEBABEL);
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void copyFromRejectsAnotherImplementation(MemoryAccess access) {
        // Mixing implementations would silently read the wrong memory; refuse it loudly instead.
        MemoryAccess other = access == ByteBufferMemoryAccess.INSTANCE && AgronaMemoryAccess.isAvailable()
                ? AgronaMemoryAccess.INSTANCE
                : ByteBufferMemoryAccess.INSTANCE;
        if (other == access) {
            return; // only one implementation available on this JVM; nothing to mix
        }
        try (MemoryRegion region = access.allocate(64);
                MemoryRegion alien = other.allocate(64)) {
            assertThatThrownBy(() -> region.copyFrom(0, alien, 0, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot copy");
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void setMemoryFills(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096)) {
            region.setMemory(0, 16, (byte) 0xFF);
            assertThat(region.getByte(0)).isEqualTo((byte) 0xFF);
            assertThat(region.getByte(15)).isEqualTo((byte) 0xFF);
            assertThat(region.getByte(16)).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void outOfBoundsAccessIsRejected(MemoryAccess access) {
        try (MemoryRegion region = access.allocate(4096)) {
            assertThatThrownBy(() -> region.getLong(region.capacity())).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> region.putLong(-1, 0L)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void closeIsIdempotent(MemoryAccess access) {
        MemoryRegion r = access.allocate(64);
        r.close();
        r.close();
        assertThat(r.toString()).contains("closed");
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void accessAfterCloseIsRejected(MemoryAccess access) {
        MemoryRegion r = access.allocate(64);
        r.close();
        assertThatThrownBy(() -> r.getLong(0)).isInstanceOf(IllegalStateException.class);
    }
}

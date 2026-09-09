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
package com.ash.messaging.pravaha.common.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryAccessTest {

    @Test
    void bestReturnsTheFlagFreeImplementationByDefault() {
        assertThat(MemoryAccess.best().name()).isEqualTo("bytebuffer");
    }

    @Test
    void bestFallsBackWhenAnUnavailableImplementationIsRequested() {
        // An unavailable implementation is never an error: the default is a correct answer, not a
        // degraded one, so selection falls through silently.
        withProperty(
                MemoryAccess.FFM_PROPERTY,
                "true",
                () -> assertThat(MemoryAccess.best().name()).isEqualTo("bytebuffer"));
        withProperty(
                MemoryAccess.IMPL_PROPERTY,
                "nonsense",
                () -> assertThat(MemoryAccess.best().name()).isEqualTo("bytebuffer"));
    }

    @Test
    void agronaIsSelectableOnlyWhenItsFlagIsPresent() {
        // Agrona 2.x needs --add-exports java.base/jdk.internal.misc=ALL-UNNAMED. Without it the
        // selector must not hand back an implementation that throws on first use.
        withProperty(MemoryAccess.IMPL_PROPERTY, "agrona", () -> {
            String chosen = MemoryAccess.best().name();
            assertThat(chosen).isEqualTo(AgronaMemoryAccess.isAvailable() ? "agrona" : "bytebuffer");
        });
    }

    @Test
    void agronaRefusesToAllocateWithoutItsFlagRatherThanFailingObscurely() {
        if (AgronaMemoryAccess.isAvailable()) {
            try (MemoryRegion r = AgronaMemoryAccess.INSTANCE.allocate(64)) {
                assertThat(r.capacity()).isEqualTo(64);
            }
        } else {
            assertThatThrownBy(() -> AgronaMemoryAccess.INSTANCE.allocate(64))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("--add-exports");
        }
    }

    private static void withProperty(String key, String value, Runnable body) {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 64, 4096, 1 << 20})
    void allocatesRegionsOfTheRequestedSize(int bytes) {
        try (MemoryRegion r = ByteBufferMemoryAccess.INSTANCE.allocate(bytes)) {
            assertThat(r.capacity()).isEqualTo(bytes);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 8, 64, 4096})
    void acceptsAnyPowerOfTwoAlignment(int alignment) {
        try (MemoryRegion r = ByteBufferMemoryAccess.INSTANCE.allocate(8192, alignment)) {
            assertThat(r.capacity()).isEqualTo(8192);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -1024})
    void rejectsNonPositiveSizes(int bytes) {
        assertThatThrownBy(() -> ByteBufferMemoryAccess.INSTANCE.allocate(bytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -8, 3, 7, 100})
    void rejectsAlignmentThatIsNotAPositivePowerOfTwo(int alignment) {
        assertThatThrownBy(() -> ByteBufferMemoryAccess.INSTANCE.allocate(64, alignment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
    }

    @Test
    void cacheLineConstantMatchesCommodityHardware() {
        assertThat(MemoryAccess.CACHE_LINE_BYTES).isEqualTo(64);
    }
}

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
package com.ash.messaging.pravaha.state.spill;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.memory.MemoryRegion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A mapped-file region behaves like any other {@link MemoryRegion}, and cleans up after itself. */
class MappedFileMemoryAccessTest {

    @Test
    void writesAndReadsEveryPrimitive(@TempDir Path dir) {
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir);
                MemoryRegion region = access.allocate(4096)) {
            region.putBoolean(0, true);
            region.putByte(1, (byte) -7);
            region.putShort(2, (short) 12345);
            region.putInt(8, -123456);
            region.putLong(16, 0x0123456789ABCDEFL);
            region.putFloat(24, 3.5f);
            region.putDouble(32, 2.71828);
            region.putBytes(64, new byte[] {1, 2, 3, 4, 5}, 0, 5);

            assertThat(region.getBoolean(0)).isTrue();
            assertThat(region.getByte(1)).isEqualTo((byte) -7);
            assertThat(region.getShort(2)).isEqualTo((short) 12345);
            assertThat(region.getInt(8)).isEqualTo(-123456);
            assertThat(region.getLong(16)).isEqualTo(0x0123456789ABCDEFL);
            assertThat(region.getFloat(24)).isEqualTo(3.5f);
            assertThat(region.getDouble(32)).isEqualTo(2.71828);
            byte[] out = new byte[5];
            region.getBytes(64, out, 0, 5);
            assertThat(out).containsExactly(1, 2, 3, 4, 5);
            assertThat(region.equalsBytes(64, new byte[] {1, 2, 3, 4, 5})).isTrue();
            assertThat(region.equalsBytes(64, new byte[] {1, 2, 3, 4, 6})).isFalse();
        }
    }

    @Test
    void copyFromAcceptsARegionOfAnotherConcreteType(@TempDir Path dir) {
        // The one place this matters: JoinSide and VariableKeyStateMap copy row bytes out of an
        // arena region into whatever RowStore.regionOf(handle) returns, without knowing or caring
        // which tier that handle's slab was carved from.
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir);
                MemoryRegion target = access.allocate(1024);
                com.ash.messaging.pravaha.common.arena.RowArena arena =
                        new com.ash.messaging.pravaha.common.arena.RowArena(
                                com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 16, 4)) {
            long handle = arena.allocate(64);
            MemoryRegion source = arena.regionOf(handle);
            int srcOffset = arena.offsetOf(handle);
            source.putLong(srcOffset, 999L);

            target.copyFrom(0, source, srcOffset, 8);

            assertThat(target.getLong(0)).isEqualTo(999L);
        }
    }

    @Test
    void writtenBytesSurviveEvenThoughTheChannelIsAlreadyClosed(@TempDir Path dir) {
        // MappedFileMemoryAccess.allocate closes its FileChannel immediately after mapping; the
        // mapping itself must still be live and writable, which is the whole point of using a
        // memory-mapped file rather than keeping a channel open per slab.
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir)) {
            MemoryRegion region = access.allocate(256);
            region.putLong(0, 42L);
            assertThat(region.getLong(0)).isEqualTo(42L);
            region.close();
        }
    }

    @Test
    void aRegionDeletesItsOwnFileOnClose(@TempDir Path dir) throws Exception {
        MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir);
        MemoryRegion region = access.allocate(256);
        long filesWhileOpen;
        try (var files = Files.list(dir)) {
            filesWhileOpen = files.count();
        }
        assertThat(filesWhileOpen).isEqualTo(1);

        region.close();

        long filesAfterClose;
        try (var files = Files.list(dir)) {
            filesAfterClose = files.count();
        }
        assertThat(filesAfterClose).isEqualTo(0);
        access.close();
    }

    @Test
    void aClosedRegionRefusesFurtherAccess(@TempDir Path dir) {
        MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir);
        MemoryRegion region = access.allocate(64);
        region.close();

        assertThatThrownBy(() -> region.getLong(0)).isInstanceOf(IllegalStateException.class);
        access.close();
    }

    @Test
    void distinctAllocationsGetDistinctFiles(@TempDir Path dir) {
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir);
                MemoryRegion a = access.allocate(64);
                MemoryRegion b = access.allocate(64)) {
            a.putLong(0, 1L);
            b.putLong(0, 2L);
            assertThat(a.getLong(0)).isEqualTo(1L);
            assertThat(b.getLong(0)).isEqualTo(2L);
        }
    }
}

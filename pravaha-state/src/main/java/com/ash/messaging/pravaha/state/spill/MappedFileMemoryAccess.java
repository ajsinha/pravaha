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

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * A {@link MemoryAccess} whose regions live on disk rather than in RAM, one memory-mapped file per
 * slab.
 *
 * <p><strong>Why this and not a dependency.</strong> ADR-037 names "RocksDB or equivalent" without
 * committing to it, and asks the question to be answered with reasoning rather than assumed. A
 * memory-mapped file needs nothing {@code java.nio} does not already provide -- no native library,
 * no JNI, nothing this bundle would need to ship per platform, which is exactly the cost ADR-037
 * itself flags ("a native-code dependency is a real cost here, as the Cassandra driver's JNI was").
 * And {@link com.ash.messaging.pravaha.state.RowStore} already has everything an embedded key-value
 * store would otherwise be adopted to provide: size classes, a free list, block reuse. The only
 * thing {@code RowStore} does not have is a way to carve a slab from disk instead of RAM once its
 * RAM ceiling is reached -- which is all this class is. Adopting RocksDB here would mean keeping two
 * unrelated storage engines' free-space bookkeeping in sync at every checkpoint; reusing {@code
 * RowStore}'s means there is only ever one.
 *
 * <p>One file per slab, not one growing file, because {@code RowStore} already allocates in
 * whole-slab units and never returns one once carved -- the same reason {@code
 * ByteBufferMemoryAccess} allocates one direct buffer per slab rather than sub-allocating from a
 * single large one. Sparse allocation ({@link StandardOpenOption#SPARSE}) means an empty slab costs
 * no disk space until it is actually written to, so choosing a slab size larger than a single query
 * is ever likely to need is cheap insurance, not a pre-commitment.
 *
 * <p>Not thread-safe, like every other {@link MemoryAccess}/{@link MemoryRegion} pair in this
 * codebase -- owned by one lane, one {@code RowStore}, single-writer throughout.
 */
public final class MappedFileMemoryAccess implements MemoryAccess, AutoCloseable {

    private final Path directory;
    private final AtomicLong nextFile = new AtomicLong();

    /**
     * @param directory where slab files are created; must already exist or be creatable by this
     *     process. Each file is named {@code slab-<n>.spill} and deleted when its region closes.
     */
    public MappedFileMemoryAccess(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(
                    "cannot create spill directory " + directory + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "mapped-file";
    }

    @Override
    public MemoryRegion allocate(int bytes) {
        return allocate(bytes, CACHE_LINE_BYTES);
    }

    /**
     * Alignment is not applied beyond what the mapping already gives. A file mapped at offset zero
     * lands on the operating system's page boundary -- 4 KiB on every platform this runs on -- which
     * already satisfies the 64-byte cache-line alignment every caller in this codebase asks for, so
     * the padding-and-slice trick {@code ByteBufferMemoryRegion} needs for a direct buffer (which
     * carries no such guarantee) would only waste disk space here for no benefit.
     */
    @Override
    public MemoryRegion allocate(int bytes, int alignment) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("region size must be positive, got " + bytes);
        }
        Path file = directory.resolve("slab-" + nextFile.getAndIncrement() + ".spill");
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes);
            return new MappedFileMemoryRegion(buffer, file, bytes);
        } catch (IOException e) {
            throw MappedFileMemoryRegion.wrap(e);
        }
    }

    /**
     * Removes the spill directory and anything still in it.
     *
     * <p>Ordinary use never needs this: every region deletes its own file on {@link
     * MemoryRegion#close()}, which {@code RowStore.close()} always calls. This exists for the case
     * that matters more in a test than in production -- a process that crashed mid-query and left
     * files behind -- so a caller managing this access's lifetime explicitly has one place to ask
     * for the directory back empty.
     */
    @Override
    public void close() {
        try (var files = Files.list(directory)) {
            files.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort, as in MappedFileMemoryRegion.close.
                }
            });
        } catch (IOException ignored) {
            // The directory may already be gone, or unreadable; either way there is nothing more
            // this method can do about it.
        }
    }
}

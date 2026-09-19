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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.state.StateErrors;

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
 * <p><strong>Shared, and budgeted (ADR-044).</strong> One access serves every lane on a node -- the
 * file counter, the quota and the mapped-byte count are atomics for that reason -- while each region
 * it hands out belongs to one {@code RowStore} and is single-writer, like every other region in this
 * codebase. Every slab counts against an optional byte quota until its region closes, and the
 * filesystem's free space is checked before a slab is created, so a spill that cannot fit is refused
 * with a code ({@code PRV-4005}, {@code PRV-4006}) rather than discovered as a fault inside a write.
 */
public final class MappedFileMemoryAccess implements MemoryAccess, AutoCloseable {

    /** How much the filesystem holding a directory will still let this process write. */
    @FunctionalInterface
    interface UsableSpace {
        long of(Path directory) throws IOException;
    }

    private static final UsableSpace FILESYSTEM =
            directory -> Files.getFileStore(directory).getUsableSpace();

    private final Path directory;
    private final AtomicLong nextFile = new AtomicLong();
    private final long maxBytes;
    private final UsableSpace usableSpace;
    private final AtomicLong bytesMapped = new AtomicLong();
    private final AtomicLong slabsReleased = new AtomicLong();

    /**
     * @param directory where slab files are created; must already exist or be creatable by this
     *     process. Each file is named {@code slab-<n>.spill} and deleted when its region closes.
     */
    public MappedFileMemoryAccess(Path directory) {
        this(directory, 0);
    }

    /**
     * ADR-044's byte quota: every slab mapped through this access counts against {@code maxBytes}
     * until its region is closed, whichever query's store carved it -- one access per node, so this
     * is the node's disk budget for spilled state, where {@code max-overflow-slabs} bounds one store.
     *
     * @param maxBytes the most bytes of slab this access keeps mapped at once, or {@code 0} for no
     *     quota beyond the filesystem's own free space, which is checked before every slab either way
     */
    public MappedFileMemoryAccess(Path directory, long maxBytes) {
        this(directory, maxBytes, FILESYSTEM);
    }

    /** With the filesystem's free space read through {@code usableSpace}, which is how a test fills a disk. */
    MappedFileMemoryAccess(Path directory, long maxBytes, UsableSpace usableSpace) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("a spill quota cannot be negative, got " + maxBytes);
        }
        this.directory = Objects.requireNonNull(directory, "directory");
        this.maxBytes = maxBytes;
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(
                    "cannot create spill directory " + directory + ": " + e.getMessage(), e);
        }
    }

    /**
     * Counts {@code bytes} against the quota, or refuses by code.
     *
     * <p>A compare-and-set loop because this access is shared by every lane on the node, and two
     * lanes reserving the last slab of room at once must not both get it.
     */
    private void reserve(int bytes) {
        while (true) {
            long mapped = bytesMapped.get();
            if (maxBytes > 0 && mapped + bytes > maxBytes) {
                throw new PravahaException(
                        StateErrors.SPILL_QUOTA_REACHED,
                        "the spill tier's quota of " + maxBytes + " bytes (pravaha.state.spill.max-bytes) is in use: "
                                + mapped + " bytes of overflow slab are mapped under " + directory
                                + " across this node's queries, and another " + bytes + " would pass it. The "
                                + "query whose state needed the slab stops here, before writing anything. Raise "
                                + "pravaha.state.spill.max-bytes if the disk can hold it, or bound the state that "
                                + "is growing with a window or a tighter key range.");
            }
            if (bytesMapped.compareAndSet(mapped, mapped + bytes)) {
                return;
            }
        }
    }

    private void unreserve(long bytes) {
        bytesMapped.addAndGet(-bytes);
    }

    /**
     * Refuses a slab the filesystem cannot hold, before it is created.
     *
     * <p>A slab is a sparse file: creating and mapping it costs no disk, and the space is taken page
     * by page as state is written into it. A full disk found that way is not an exception but a
     * {@code SIGBUS} inside a write to mapped memory, surfaced by the JVM as an internal error from
     * whichever operator happened to touch the page -- the least legible failure available. So the
     * free space is checked here, against the whole slab, and a slab that would not fit is refused with
     * a code instead.
     */
    private void checkFreeSpace(int bytes) {
        long usable;
        try {
            usable = usableSpace.of(directory);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(
                    "cannot read the free space of the spill directory " + directory + ": " + e.getMessage(), e);
        }
        if (usable < bytes) {
            throw new PravahaException(
                    StateErrors.SPILL_DISK_FULL,
                    "the filesystem holding the spill directory " + directory + " has " + usable
                            + " bytes free and the next overflow slab needs " + bytes + ". Refused before the "
                            + "slab was created, rather than failing inside a write to it. Free space there, point "
                            + "pravaha.state.spill.directory at a larger filesystem, or set "
                            + "pravaha.state.spill.max-bytes below what this one can hold so the quota refuses "
                            + "first.");
        }
    }

    /** Bytes of slab mapped through this access now: what the spill tier holds on disk, node-wide. */
    public long bytesMapped() {
        return bytesMapped.get();
    }

    /** The quota, or {@code 0} for none. */
    public long maxBytes() {
        return maxBytes;
    }

    /** Slabs whose regions were closed -- compacted away, or released with the store that held them. */
    public long slabsReleased() {
        return slabsReleased.get();
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
        reserve(bytes);
        try {
            checkFreeSpace(bytes);
        } catch (RuntimeException refused) {
            unreserve(bytes);
            throw refused;
        }
        Path file = directory.resolve("slab-" + nextFile.getAndIncrement() + ".spill");
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes);
            return new MappedFileMemoryRegion(buffer, file, bytes, () -> {
                unreserve(bytes);
                slabsReleased.incrementAndGet();
            });
        } catch (IOException e) {
            unreserve(bytes);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // The mapping failed; a stray empty file is the least of it.
            }
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

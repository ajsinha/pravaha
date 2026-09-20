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

/**
 * Allocator for off-heap {@link MemoryRegion}s, and the single seam between Pravaha and whichever
 * low-level memory API the running JDK offers.
 *
 * <p>This exists because the two candidate baselines each carry a future-facing liability: Java 21
 * has no final Foreign Function and Memory API, and {@code sun.misc.Unsafe}'s memory accessors are
 * deprecated for removal from Java 23 onward. One interface with two implementations resolves both
 * (design section 4.6). Generated code and the arena call this interface; the JIT inlines the single
 * implementation present at runtime, so the abstraction costs nothing.
 *
 * <p>{@link #best()} selects: Agrona by default, and an FFM implementation on JDK 22+ when
 * {@code -Dpravaha.ffm=true} is set. The FFM path is opt-in until the parity benchmark
 * (implementation plan spike S2) says otherwise.
 */
public interface MemoryAccess {

    /** Cache-line size assumed for alignment. */
    int CACHE_LINE_BYTES = 64;

    /** System property that opts into the FFM implementation on JDK 22+. */
    String FFM_PROPERTY = "pravaha.ffm";

    /** System property selecting an implementation by {@link #name()}; unset means "choose for me". */
    String IMPL_PROPERTY = "pravaha.memory";

    /** A short name for logs and metrics, e.g. {@code "agrona"}. */
    String name();

    /**
     * Allocates a zeroed region aligned to {@link #CACHE_LINE_BYTES}.
     *
     * @throws IllegalArgumentException if {@code bytes} is not positive
     * @throws OutOfMemoryError if the allocation cannot be satisfied
     */
    MemoryRegion allocate(int bytes);

    /**
     * Allocates a zeroed region with explicit alignment.
     *
     * @throws IllegalArgumentException if {@code alignment} is not a positive power of two
     */
    MemoryRegion allocate(int bytes, int alignment);

    /** The values {@link #IMPL_PROPERTY} accepts, besides being unset. */
    java.util.List<String> IMPL_NAMES = java.util.List.of("agrona", "foreign", "bytebuffer");

    /**
     * The best implementation for the running JDK.
     *
     * <p>An implementation that is <em>unavailable</em> is not an error: the default is a correct
     * answer, not a degraded one, and all four selections produce byte-identical results -- so
     * {@code -Dpravaha.ffm=true} on a JDK that cannot support it falls through, deliberately.
     *
     * <p>An implementation that <strong>does not exist</strong> is a different thing, and is now
     * refused (CFG-22). {@code -Dpravaha.memory=nonsense} used to start normally and run the
     * default, which defeats the only reason to set the property: it is set to be certain, and
     * silence is the one answer that cannot give certainty. A typo in a launcher script is the
     * commonest way to reach it and the hardest to see, because the node it produces is correct.
     */
    static MemoryAccess best() {
        String requested = System.getProperty(IMPL_PROPERTY, "").trim();
        if (!requested.isEmpty() && !IMPL_NAMES.contains(requested)) {
            throw new IllegalArgumentException("-D" + IMPL_PROPERTY + "=" + requested
                    + " names no off-heap implementation; the values are " + IMPL_NAMES
                    + ", or leave it unset to let the engine choose. Falling through to the default "
                    + "would run a node that is correct and is not the one you asked for, which is the "
                    + "one thing setting this property is meant to rule out.");
        }

        if ("bytebuffer".equals(requested)) {
            return ByteBufferMemoryAccess.INSTANCE;
        }
        if ("agrona".equals(requested) && AgronaMemoryAccess.isAvailable()) {
            return AgronaMemoryAccess.INSTANCE;
        }
        if (("foreign".equals(requested) || Boolean.getBoolean(FFM_PROPERTY))
                && Runtime.version().feature() >= 22) {
            MemoryAccess ffm = tryLoadForeign();
            if (ffm != null) {
                return ffm;
            }
        }
        // Flag-free, always available, and correct on every supported JDK.
        return ByteBufferMemoryAccess.INSTANCE;
    }

    private static MemoryAccess tryLoadForeign() {
        try {
            Class<?> c = Class.forName("com.ash.messaging.pravaha.common.memory.ForeignMemoryAccess");
            return (MemoryAccess) c.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            // Deliberately silent: the Agrona path is a correct answer, not a degraded one.
            return null;
        }
    }
}

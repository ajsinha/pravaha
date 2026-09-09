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
 * {@link MemoryAccess} backed by Agrona's {@code UnsafeBuffer}.
 *
 * <p><strong>Requires a JVM flag.</strong> Agrona 2.x reaches {@code jdk.internal.misc.Unsafe},
 * which the platform does not export to unnamed modules, so this implementation only initialises
 * under:
 *
 * <pre>--add-exports java.base/jdk.internal.misc=ALL-UNNAMED</pre>
 *
 * <p>That requirement is why this is <em>not</em> the default (design section 4.6, corrected). An
 * embedded engine inherits its host application's launch arguments; demanding a flag would mean a
 * customer cannot embed Pravaha without changing how their own service starts, which forfeits the
 * embeddability the product is positioned on (design section 2.2). {@link ByteBufferMemoryAccess} carries
 * the baseline instead.
 *
 * <p>Where the flag <em>is</em> available -- server mode, where we own the launch arguments -- this
 * implementation may still win on throughput. Select it with {@code -Dpravaha.memory=agrona}; the
 * JMH comparison in {@code pravaha-benchmarks} is what decides whether that is worth doing.
 */
public final class AgronaMemoryAccess implements MemoryAccess {

    /** The single instance; the implementation holds no mutable state. */
    public static final AgronaMemoryAccess INSTANCE = new AgronaMemoryAccess();

    private static final boolean AVAILABLE = probe();

    private AgronaMemoryAccess() {}

    /**
     * Whether this implementation can initialise on the running JVM.
     *
     * <p>Probed once, by actually allocating: asking whether the flag is present is less reliable
     * than finding out.
     */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean probe() {
        try (MemoryRegion probe = new AgronaMemoryRegion(64, 8)) {
            probe.putLong(0, 1L);
            return probe.getLong(0) == 1L;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "agrona";
    }

    @Override
    public MemoryRegion allocate(int bytes) {
        return allocate(bytes, CACHE_LINE_BYTES);
    }

    @Override
    public MemoryRegion allocate(int bytes, int alignment) {
        Allocations.checkSize(bytes);
        Allocations.checkAlignment(alignment);
        if (!AVAILABLE) {
            throw new UnsupportedOperationException(
                    "Agrona memory access needs --add-exports java.base/jdk.internal.misc=ALL-UNNAMED; "
                            + "use ByteBufferMemoryAccess (the default) or add the flag");
        }
        return new AgronaMemoryRegion(bytes, alignment);
    }
}

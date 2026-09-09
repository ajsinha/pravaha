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

    /**
     * The best implementation for the running JDK.
     *
     * <p>An unavailable or unflagged implementation is never an error: the default is a correct
     * answer, not a degraded one, so selection silently falls through to it.
     */
    static MemoryAccess best() {
        String requested = System.getProperty(IMPL_PROPERTY, "").trim();

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

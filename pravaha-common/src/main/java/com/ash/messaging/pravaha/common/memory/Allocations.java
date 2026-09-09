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

/** Argument checks shared by every {@link MemoryAccess} implementation. */
final class Allocations {

    private Allocations() {}

    static void checkSize(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("allocation size must be positive, got " + bytes);
        }
    }

    static void checkAlignment(int alignment) {
        if (alignment <= 0 || Integer.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("alignment must be a positive power of two, got " + alignment);
        }
    }
}

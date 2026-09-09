/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
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

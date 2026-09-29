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
package com.ash.messaging.pravaha.runtime.window;

/**
 * A {@code DECIMAL} value as a windowed aggregate's group key or distinct value holds it: the whole
 * 128-bit unscaled value, both halves.
 *
 * <p>WINDECKEY-1. A decimal key was read with {@code getLong}, which is the high half of the slot
 * only -- zero for every value of eighteen digits or fewer -- so a windowed {@code GROUP BY} on a
 * decimal column put {@code 1.50} and {@code 2.75} in one group, and {@code COUNT(DISTINCT)} of one
 * counted them as one value. Both halves are the identity; the scale is the column's and is the same
 * for every value in it, so two values are equal exactly when their bits are.
 *
 * <p>A record so that equality and hashing are by value, which is what the on-heap maps keyed by a
 * group's columns compare; the off-heap stores compare the tagged bytes {@link TaggedValues} writes.
 *
 * @param high the upper 64 bits of the two's-complement unscaled value
 * @param low the lower 64 bits
 */
public record DecimalBits(long high, long low) {}

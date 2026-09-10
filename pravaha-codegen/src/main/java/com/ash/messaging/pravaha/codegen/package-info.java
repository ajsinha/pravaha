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
/**
 * Whole-stage code generation.
 *
 * <p>Emits Java source for a fused operator chain and compiles it with Janino at query
 * registration. Field offsets become compile-time constants, string comparison becomes a UTF-8 byte
 * comparison, and a filter followed by a projection becomes straight-line code in one counted loop
 * the JIT can unroll -- which is the difference between roughly 100k and roughly 1M records per
 * second per core (design 12.1).
 *
 * <p>This is the highest-risk component in the design (R2), and two things make it survivable. The
 * interpreted path in {@code pravaha-runtime} implements every operator, so correctness never
 * depends on generation succeeding. And both paths consume the same predicate IR, so the
 * differential tests compare two implementations of one specification rather than two translations
 * that could each be wrong in their own way.
 */
package com.ash.messaging.pravaha.codegen;

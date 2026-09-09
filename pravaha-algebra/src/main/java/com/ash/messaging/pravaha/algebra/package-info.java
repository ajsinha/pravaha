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
 * The incremental computation core: Z-sets, frontiers, and the lift from batch to incremental.
 *
 * <p>This is the engine's intellectual centre (design section 9). A relation and a changelog are the same
 * object here -- a multiset with signed integer weights -- so insert, update and delete stop being
 * three cases an operator author reasons about separately and become arithmetic.
 *
 * <p>The package is deliberately small and dependency-free. Its correctness is checked by a
 * generated property rather than by enumerated cases, and keeping it simple enough to state that
 * property over is worth more than any convenience added here.
 */
package com.ash.messaging.pravaha.algebra;

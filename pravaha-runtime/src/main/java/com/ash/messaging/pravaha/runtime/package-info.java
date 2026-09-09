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
 * The execution runtime.
 *
 * <p>Deliberately free of Calcite. The plan IR lives here and {@code pravaha-sql} depends on this
 * module rather than the reverse, so the compiler targets the runtime's contract and none of
 * Calcite's thirty transitive dependencies reach execution (ADR-002).
 */
package com.ash.messaging.pravaha.runtime;

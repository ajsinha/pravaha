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
 * The deterministic test harness.
 *
 * <p>Every runtime test uses this rather than real threads and the wall clock. A streaming engine's
 * hardest bugs are timing- and ordering-dependent, and a suite that reproduces them only sometimes
 * is a suite that cannot be trusted to have found them. Here, time is advanced explicitly and
 * interleaving is a seed, so a failure recurs exactly (implementation plan story P0-09).
 */
package com.ash.messaging.pravaha.testkit;

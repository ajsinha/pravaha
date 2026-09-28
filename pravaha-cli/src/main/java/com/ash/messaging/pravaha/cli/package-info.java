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
 * The {@code pravaha-engine} command-line tool: validate, explain and run with the engine in-process.
 *
 * <p>Exists because the inner loop is a competitive lever (design 24.1): Flink's most-cited weakness
 * is that the path from idea to first output takes minutes and a cluster. Shipping the CLI in Wave 2
 * rather than Wave 8 is deliberate -- a fast loop has to be designed in, not bolted on.
 */
package com.ash.messaging.pravaha.cli;

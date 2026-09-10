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
 * Delta Lake source.
 *
 * <p>Delta rewrites whole files, so the difference between two table versions is a set of removed
 * files and a set of added files. Weighted {@code -1} and {@code +1} that difference is already a
 * Z-set delta (design section 9.2), which is why this connector needs no update path of its own.
 *
 * <p>Built on Delta Kernel rather than Spark: Kernel understands the transaction log, protocol
 * versions and column mapping without requiring a second execution engine to feed the first.
 */
package com.ash.messaging.pravaha.plugin.delta;

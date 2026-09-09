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
 * Off-heap memory access.
 *
 * <p>The one place in the codebase where a low-level memory API is named. Everything above this
 * package talks to {@link com.ash.messaging.pravaha.common.memory.MemoryAccess} and
 * {@link com.ash.messaging.pravaha.common.memory.MemoryRegion}, so moving from Agrona to the
 * Foreign Function and Memory API is a configuration change rather than a migration
 * (design section 4.6).
 */
package com.ash.messaging.pravaha.common.memory;

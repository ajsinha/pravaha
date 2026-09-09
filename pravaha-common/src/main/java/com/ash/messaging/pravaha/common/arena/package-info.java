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
 * Bump-pointer arena allocation for rows.
 *
 * <p>Batch-scoped memory with O(1) reclaim. Rows that must outlive their batch -- buffered window
 * inputs, a join build side, a subscriber tap -- are copied into the owning structure's own arena.
 * That copy is the only one in the pipeline and it is deliberate and explicit (design section 8.5).
 */
package com.ash.messaging.pravaha.common.arena;

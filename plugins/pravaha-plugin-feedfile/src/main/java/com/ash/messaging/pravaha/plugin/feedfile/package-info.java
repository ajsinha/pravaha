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
 * Drop-directory feeds: files land in a directory and become a stream.
 *
 * <p>CSV and Parquet, completion detection, declared ordering, per-file replayable offsets and
 * quarantine for poison files (design section 19.7). The hard parts are not the formats -- they are
 * knowing when a file is finished, in what order files apply, and how to resume in the middle of
 * one.
 */
package com.ash.messaging.pravaha.plugin.feedfile;

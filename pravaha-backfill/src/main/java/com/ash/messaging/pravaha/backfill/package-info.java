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
 * Loading history without losing or duplicating the present (design section 16).
 *
 * <p>Every streaming rollout meets the same problem on its first day: a query needs three years of
 * history and the live change feed, and the two have to be joined at a seam. Read the snapshot
 * first and the changes made while it was running are lost; start the feed first and those changes
 * arrive twice. Getting this wrong is quiet -- the numbers are merely a little off -- and it is
 * where streaming projects actually fail.
 */
package com.ash.messaging.pravaha.backfill;

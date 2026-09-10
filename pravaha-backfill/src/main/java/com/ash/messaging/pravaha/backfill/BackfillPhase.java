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
package com.ash.messaging.pravaha.backfill;

/**
 * Where a backfill has got to.
 *
 * <p>Part of the offset, not a private field, because a crash mid-backfill must resume in the same
 * phase it stopped in. An offset that recorded only a scan position would restart a half-finished
 * backfill in the wrong phase -- replaying the change buffer as though it were history, or treating
 * live changes as though they still needed deduplicating.
 */
public enum BackfillPhase {

    /** Reading history, while changes accumulate in the buffer behind it. */
    SNAPSHOT,

    /** History is read; the changes that arrived during it are being replayed. */
    CATCH_UP,

    /** The seam is behind us. The change feed is the only input, and nothing is buffered. */
    LIVE
}

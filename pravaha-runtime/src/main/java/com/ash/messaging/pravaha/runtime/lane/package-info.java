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
 * The lane model: the execution unit the performance gate is expressed in.
 *
 * <p>A lane is one platform thread, one bounded inbox, one arena, one processor and one batch loop
 * (design section 13.1). Lanes share nothing -- not an arena, not a queue, not a processor instance
 * -- because the gate asks for 90 % scaling from one lane to eight, and anything shared between them
 * is a coherence miss taken on every record that touches it.
 *
 * <p>The pieces this assembles all predate it: {@code RowArena} for output, {@code RowInbox} for the
 * ingest edge, {@code WaitStrategy} for the idle policy, and a processor that is either a generated
 * fused stage or the interpreted chain. What the lane adds is the loop, the lifecycle, and the two
 * orderings within the loop that keep flyweight rows valid for exactly as long as they are read.
 */
package com.ash.messaging.pravaha.runtime.lane;

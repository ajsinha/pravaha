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
package com.ash.messaging.pravaha.state.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * Where checkpoints are kept.
 *
 * <p>Two requirements that sound obvious and are the whole difficulty. A checkpoint is either
 * entirely there or entirely absent -- a half-written one that a restore reads is worse than no
 * checkpoint, because the engine will believe it. And the newest complete one must be findable
 * after a crash that happened at any instant, including during a write.
 */
public interface CheckpointStore {

    /** Stores a checkpoint. Returns only once it is durable and complete. */
    void store(Checkpoint checkpoint);

    /** The newest complete checkpoint, if there is one. */
    Optional<Checkpoint> latest();

    /** Every complete checkpoint, newest first. */
    List<Long> availableIds();

    /** Loads one by id. */
    Optional<Checkpoint> load(long id);

    /** Deletes all but the newest {@code keep}. */
    int prune(int keep);
}

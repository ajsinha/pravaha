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
package com.ash.messaging.pravaha.cluster;

/**
 * What a node can do with a virtual partition it owns, or is about to.
 *
 * <p>The cluster module orchestrates handoff; it does not know what partition state <em>is</em>.
 * This is the seam: the runtime supplies an implementation that reaches into lane-local state and
 * the checkpoint store, and {@link PartitionHandoff} drives it through a fixed sequence.
 *
 * <p>The methods are deliberately separate rather than one {@code move()} call, because the order
 * is the correctness argument (see {@link PartitionHandoff}) and a single call would hide it.
 */
public interface PartitionOwner {

    /**
     * Stops processing input for this partition and returns once in-flight work has drained.
     *
     * <p>This is the pause the design budgets at five seconds (NFR-6). It has to happen before the
     * snapshot, or the snapshot is of a moving target.
     */
    void pause(int partition);

    /**
     * Captures the partition's state and its input offsets, durably.
     *
     * <p>The offsets travel with the state. A snapshot without them would leave the target guessing
     * where to resume, and either guess is wrong: too early replays rows, too late drops them.
     */
    PartitionSnapshot snapshot(int partition);

    /** Loads a snapshot taken elsewhere, leaving the partition paused and ready to resume. */
    void restore(PartitionSnapshot snapshot);

    /** Starts processing this partition's input, from the offsets the snapshot carried. */
    void resume(int partition);

    /**
     * Drops all local state for a partition this node no longer owns.
     *
     * <p>Called on the source only after the target confirms it is serving. Until then the source's
     * copy is the only thing standing between a failed handoff and lost state.
     */
    void release(int partition);
}

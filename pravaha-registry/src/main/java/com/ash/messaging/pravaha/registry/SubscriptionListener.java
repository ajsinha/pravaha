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
package com.ash.messaging.pravaha.registry;

import java.util.List;

import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * What a subscriber that mirrors a view is told: where to start, then every change after it (SUB-1).
 *
 * <p>For {@link RegisteredQuery#subscribeFromSnapshot}. {@link #onSnapshot} is called once, first,
 * with the view as some commit left it; {@link #onCommit} then once per later commit, in order,
 * with that commit's changes whole. Applying the snapshot to nothing and each commit's changes by
 * weight reproduces the view at every commit from then on: no commit is missing and none is
 * counted twice.
 *
 * <p>Both run on an engine thread -- the subscribing one or a committing one -- and must not block.
 */
public interface SubscriptionListener {

    /**
     * The view at a commit boundary, after the subscription's filter.
     *
     * @param rows every committed row matching the filter, each with its multiplicity as its weight;
     *     empty when none does, and delivered all the same
     * @param frontier the committed frontier the rows are the view at
     */
    void onSnapshot(List<ViewChange> rows, long frontier);

    /**
     * One commit after the snapshot, whole, after the filter; not called for a commit none of whose
     * changes match.
     *
     * @param frontier the frontier that commit published
     */
    void onCommit(List<ViewChange> changes, long frontier);
}

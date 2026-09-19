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
package com.ash.messaging.pravaha.embedded;

import java.util.List;

/**
 * A subscriber that keeps its own copy of a view: told where to start, then every change (SUB-1).
 *
 * <p>For {@link PravahaEngine#subscribeFromSnapshot}. {@link #onSnapshot} arrives once and first,
 * with the view as a commit left it; {@link #onCommit} then once per later commit, whole and in
 * order. Adding each row's {@link RowChange#weight()} to the snapshot gives the view at every commit
 * after it -- nothing between the two is missed, and nothing is counted twice.
 *
 * <p>Both are called on an engine thread and must not block it.
 */
public interface RowChangeListener {

    /**
     * The view's committed rows, each with its multiplicity as its weight; empty when the view is.
     *
     * @param frontier the committed frontier the rows are the view at
     */
    void onSnapshot(List<RowChange> rows, long frontier);

    /** One commit after the snapshot, whole. */
    void onCommit(List<RowChange> changes, long frontier);
}

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
package com.ash.messaging.pravaha.serving;

import java.util.List;

/**
 * Hears a view's <em>answer</em> change: the rows a reader of the view sees, each once, and for each
 * commit the rows that left it and the rows that entered it (ADR-056).
 *
 * <p>Not the changelog a subscriber gets. That is what the lanes applied, and for a keyed view a
 * second insert under a key replaces the row the view holds while the changelog carries only the
 * insert; a query summing it would count both. This is computed by the view in its own commit, from
 * the row it held under each touched key and the row it holds after, so it is exactly the difference
 * between two consecutive committed answers.
 *
 * <p>Both methods are called under the view's monitor, in commit order, on whichever thread commits.
 * An implementation must only queue, and must not modify the arrays it is handed: they are the view's
 * own rows, shared rather than copied.
 */
public interface AnswerListener {

    /**
     * The committed answer as it stands: at the moment of following, and again whenever the view's
     * contents are replaced wholesale (a restore) or a follower asks for it.
     *
     * @param frontier the frontier the answer was committed at
     */
    void onSnapshot(List<Object[]> rows, long frontier);

    /**
     * One commit's change to the answer.
     *
     * @param leaving rows that were in the answer and are not now, including rows retention evicted
     * @param entering rows that are in the answer and were not before
     * @param frontier the frontier this commit published
     */
    void onAnswer(List<Object[]> leaving, List<Object[]> entering, long frontier);
}

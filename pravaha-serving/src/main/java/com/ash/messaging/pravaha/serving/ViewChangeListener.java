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
 * Told when a view has changed, at a frontier where the change is complete.
 *
 * <p>Delivery is per <em>commit</em>, never per row, and that is the whole contract. A commit is the
 * point at which the engine says a prefix of the input has been fully processed; between commits the
 * view holds a half-applied batch. A subscriber woken per row would see a window's rows arrive one
 * at a time and could easily act on a total that was still being assembled.
 *
 * <p>Implementations must not block. They are called on the thread that committed -- the engine's --
 * so anything slow here is backpressure applied to the query itself, which is rarely what anybody
 * wants and never what they intended.
 */
@FunctionalInterface
public interface ViewChangeListener {

    /**
     * @param changes the rows that changed in this commit, in the order they were applied
     * @param frontier the frontier now committed; every change in this batch belongs at or before it
     */
    void onCommit(List<ViewChange> changes, long frontier);
}

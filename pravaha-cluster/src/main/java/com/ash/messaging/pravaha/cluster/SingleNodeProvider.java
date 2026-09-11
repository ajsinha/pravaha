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

import com.ash.messaging.pravaha.common.config.Configuration;

/** Makes the single-node coordinator selectable as {@code mechanism: single}, and the default. */
public final class SingleNodeProvider implements CoordinatorProvider {

    @Override
    public String mechanism() {
        return "single";
    }

    @Override
    public Guarantees guarantees() {
        return new Guarantees("single", true, false, true);
    }

    @Override
    public ClusterCoordinator create(Configuration configuration) {
        return new SingleNodeCoordinator();
    }
}

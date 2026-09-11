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

public record Guarantees(
        String name, boolean excludesSplitBrain, boolean requiresExternalService, boolean suitableForProduction) {

    public Guarantees {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a coordinator mechanism needs a name");
        }
    }

    /** A one-line summary for a log line at startup, where somebody may still read it. */
    public String describe() {
        return name
                + (excludesSplitBrain ? " (consensus)" : " (NO consensus — cannot exclude split-brain)")
                + (requiresExternalService ? ", external service required" : ", self-contained")
                + (suitableForProduction ? "" : ", development only");
    }
}

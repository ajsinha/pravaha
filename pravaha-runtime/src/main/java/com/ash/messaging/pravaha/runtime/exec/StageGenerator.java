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
package com.ash.messaging.pravaha.runtime.exec;

import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * Compiles a chain of filters and a projection over a scan to Java, or says why it will not.
 *
 * <p>Installed once per process with {@link GeneratedChains#install}; {@code pravaha-codegen}
 * provides the implementation and the node installs it at start-up. With none installed every
 * query runs interpreted, which is also what happens to any chain the generator refuses.
 */
public interface StageGenerator {

    /**
     * Generates the chain rooted at {@code root}.
     *
     * @param root a {@code FilterOperator} or {@code ProjectOperator} whose inputs lead, through
     *     nothing but filters and projections, to a scan
     * @return the compiled stage, or a refusal naming why; never null and never a thrown refusal,
     *     because a chain the generator cannot compile is a chain the interpreter runs
     */
    Outcome generate(PhysicalOperator root);

    /** A compiled stage with what the generator said about it, or no stage and the reason. */
    record Outcome(GeneratedRowStage stage, String reason) {

        public static Outcome refused(String reason) {
            return new Outcome(null, reason);
        }

        public boolean generated() {
            return stage != null;
        }
    }
}

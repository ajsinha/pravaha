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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

/**
 * Every stream a plan reads, in the order it reads them.
 *
 * <p>Taken from the plan rather than from the SQL text, because the text can name a stream the
 * planner optimised away and can omit one a view expanded into. What the plan scans is what the
 * query will actually read.
 *
 * <p>Extracted from {@link QueryRegistry}, which held it as two private statics; registration, a
 * blue/green replacement and a debug fork all need the same answer, and the third of them is where
 * three copies would have started.
 */
final class PlanSources {

    private PlanSources() {}

    static List<String> of(PhysicalOperator plan) {
        List<String> found = new ArrayList<>();
        collect(plan, found);
        return found;
    }

    private static void collect(PhysicalOperator operator, List<String> into) {
        if (operator instanceof ScanOperator scan && !into.contains(scan.streamName())) {
            into.add(scan.streamName());
        }
        operator.inputs().forEach(input -> collect(input, into));
    }
}

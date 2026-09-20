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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * The one definition of a plan's node order, and therefore of a node's name.
 *
 * <p>Three things number the operators of a plan: the text {@code EXPLAIN} prints, the graph the
 * API returns, and the per-operator counters the engine keeps. They have to agree, because a
 * console draws the graph and hangs the counters on it by id -- and if two of them walked the tree
 * differently, the numbers would land on the wrong boxes and nothing would look broken.
 *
 * <p>Pre-order, root first, inputs in the order the operator declares them (left before right for a
 * join). {@code n0} is always the root.
 *
 * <p>Lives in {@code pravaha-runtime} rather than beside the graph in {@code pravaha-sql} because
 * the runtime is where the counters are written and the runtime does not depend on the SQL module
 * (ADR-002). The dependency runs the other way, so the graph can use this and the counters cannot
 * have used the graph.
 */
public final class PlanNodes {

    private PlanNodes() {}

    /** Every operator of {@code root}, in id order. Index {@code i} is the node named {@code ni}. */
    public static List<PhysicalOperator> preOrder(PhysicalOperator root) {
        List<PhysicalOperator> out = new ArrayList<>();
        visit(root, out);
        return List.copyOf(out);
    }

    /** The name node {@code index} carries everywhere: {@code n0}, {@code n1}, and so on. */
    public static String idOf(int index) {
        return "n" + index;
    }

    private static void visit(PhysicalOperator operator, List<PhysicalOperator> out) {
        out.add(operator);
        for (PhysicalOperator input : operator.inputs()) {
            visit(input, out);
        }
    }
}

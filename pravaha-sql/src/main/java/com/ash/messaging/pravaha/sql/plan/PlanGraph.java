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
package com.ash.messaging.pravaha.sql.plan;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * A physical plan as nodes and edges, for anything that draws it.
 *
 * <p>{@link PhysicalPlanBuilder#explain} renders the same tree as indented text, and a client that
 * wanted a graph used to rebuild one by counting leading spaces -- a parser of a rendering, which
 * breaks the day a label contains a newline or the indent changes. This is the structure itself.
 *
 * <p>Node ids are assigned in the order {@code explain} prints the operators (pre-order, root first),
 * so {@code n0} is always the root and the text and the graph can be read side by side. Edges run in
 * the direction rows flow: from an input to the operator that consumes it.
 */
public record PlanGraph(List<Node> nodes, List<Edge> edges) {

    /**
     * One operator.
     *
     * @param operator the operator's kind -- {@code Scan}, {@code Filter}, {@code WindowedAggregate}
     *     -- taken from its type, not parsed from its label
     * @param detail the engine's own label for it, exactly as {@code explain} prints it
     * @param stateful whether it keeps state, and so is what a state ceiling bounds
     * @param fields the columns it emits, by name, in order
     */
    public record Node(String id, String operator, String detail, boolean stateful, List<String> fields) {}

    /** Rows flow from {@code from} into {@code to}. */
    public record Edge(String from, String to) {}

    public PlanGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** The graph of a plan, root first. */
    public static PlanGraph of(PhysicalOperator root) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        visit(root, null, nodes, edges);
        return new PlanGraph(nodes, edges);
    }

    private static void visit(PhysicalOperator operator, String consumer, List<Node> nodes, List<Edge> edges) {
        String id = "n" + nodes.size();
        List<String> fields = new ArrayList<>(operator.outputSchema().fieldCount());
        for (int i = 0; i < operator.outputSchema().fieldCount(); i++) {
            fields.add(operator.outputSchema().field(i).name());
        }
        nodes.add(new Node(id, kindOf(operator), operator.label(), operator.isStateful(), fields));
        if (consumer != null) {
            edges.add(new Edge(id, consumer));
        }
        for (PhysicalOperator input : operator.inputs()) {
            visit(input, id, nodes, edges);
        }
    }

    private static String kindOf(PhysicalOperator operator) {
        String simple = operator.getClass().getSimpleName();
        return simple.endsWith("Operator") && simple.length() > "Operator".length()
                ? simple.substring(0, simple.length() - "Operator".length())
                : simple;
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which of a pipeline's operators hold state, what to call them, and how to read a page (ADR-048).
 *
 * <p>Its own class because it is its own subject: a naming scheme, a router from a name to the
 * operator that answers it, and the paging. {@link InterpretedPipeline} is the largest file in
 * this module and this was the part of it that had nothing to do with running a query.
 *
 * <p>Built per call from the pipeline's operator lists, which it does not own and does not
 * mutate. Everything here runs on the lane's own thread -- see {@link OperatorStateReader} -- and
 * nothing here writes: reading a join's index must not evict from it, and reading an aggregate
 * must not emit it.
 */
final class OperatorStateIndex {

    private final List<KeyedAggregate> keyed;
    private final List<GlobalAggregate> globals;
    private final List<WindowedAggregate> windowed;
    private final List<SymmetricHashJoin> joins;

    OperatorStateIndex(
            List<KeyedAggregate> keyed,
            List<GlobalAggregate> globals,
            List<WindowedAggregate> windowed,
            List<SymmetricHashJoin> joins) {
        this.keyed = keyed;
        this.globals = globals;
        this.windowed = windowed;
        this.joins = joins;
    }

    /**
     * The pieces of state in this pipeline that can be looked at, and how much each holds (ADR-048).
     *
     * <p>Ids are positional within a kind -- {@code aggregate#0}, {@code join#1.left} -- and are
     * stable for the life of a pipeline because the builder wires the plan in a fixed order. They
     * are not the plan's operator ids: the plan has operators with no state at all, and a join has
     * two indexes rather than one.
     *
     * <p>Called on the lane's own thread. Everything it reads is written there, and reading it from
     * anywhere else is a second thread on a lane's state -- which is the rule this whole runtime is
     * built around. See {@link OperatorStateReader}.
     */
    List<OperatorState.Slot> slots() {
        List<OperatorState.Slot> slots = new ArrayList<>();
        for (int index = 0; index < keyed.size(); index++) {
            slots.add(new OperatorState.Slot(
                    "aggregate#" + index,
                    "aggregate",
                    "groups",
                    keyed.get(index).groupCount()));
        }
        for (int index = 0; index < globals.size(); index++) {
            slots.add(new OperatorState.Slot("global#" + index, "global", "accumulators", 1));
        }
        for (int index = 0; index < windowed.size(); index++) {
            slots.add(new OperatorState.Slot(
                    "window#" + index,
                    "window",
                    "windows retained",
                    windowed.get(index).retainedWindows()));
        }
        for (int index = 0; index < joins.size(); index++) {
            SymmetricHashJoin join = joins.get(index);
            slots.add(new OperatorState.Slot(
                    "join#" + index + ".left", "join", join.label() + " left", join.distinctRowsHeld(true)));
            slots.add(new OperatorState.Slot(
                    "join#" + index + ".right", "join", join.label() + " right", join.distinctRowsHeld(false)));
        }
        return List.copyOf(slots);
    }

    /**
     * One page of one operator's state, filtered by key (ADR-048).
     *
     * <p>Bounded in memory as well as in what it returns: entries outside the page are counted and
     * discarded as the walk goes, so paging a join holding a million rows costs the walk and a page,
     * not a million rendered rows.
     *
     * @param keyFilter a key to show, or null or blank for every key
     */
    OperatorState.Page page(String id, String keyFilter, int offset, int limit) {
        String wanted = keyFilter == null || keyFilter.isBlank() ? null : keyFilter;
        List<OperatorState.Entry> page = new ArrayList<>();
        long[] seen = {0};
        java.util.function.BiConsumer<String, Map<String, String>> collector = (key, values) -> {
            if (wanted != null && !wanted.equals(key)) {
                return;
            }
            long index = seen[0]++;
            if (index >= offset && page.size() < limit) {
                page.add(new OperatorState.Entry(key, values));
            }
        };
        String kind = describeInto(id, collector);
        return new OperatorState.Page(id, kind, wanted, offset, limit, seen[0], page);
    }

    /** Routes {@code id} to the operator that holds it, returning its kind. */
    private String describeInto(String id, java.util.function.BiConsumer<String, Map<String, String>> collector) {
        if (id != null && id.startsWith("aggregate#")) {
            keyed.get(ordinalOf(id, "aggregate#", keyed.size())).describe(collector);
            return "aggregate";
        }
        if (id != null && id.startsWith("global#")) {
            globals.get(ordinalOf(id, "global#", globals.size())).describe(collector);
            return "global";
        }
        if (id != null && id.startsWith("window#")) {
            windowed.get(ordinalOf(id, "window#", windowed.size())).describe(collector);
            return "window";
        }
        if (id != null && id.startsWith("join#")) {
            boolean left = !id.endsWith(".right");
            String ordinal = id.substring("join#".length()).replace(".left", "").replace(".right", "");
            joins.get(ordinalOf("join#" + ordinal, "join#", joins.size())).describe(left, collector);
            return "join";
        }
        throw new IllegalArgumentException("'" + id + "' is not a piece of state in this query. It holds "
                + slots().stream().map(OperatorState.Slot::id).toList());
    }

    private static int ordinalOf(String id, String prefix, int count) {
        int ordinal;
        try {
            ordinal = Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + id + "' does not name a " + prefix + "N operator");
        }
        if (ordinal < 0 || ordinal >= count) {
            throw new IllegalArgumentException("'" + id + "' is out of range: this query has " + count + " of them");
        }
        return ordinal;
    }
}

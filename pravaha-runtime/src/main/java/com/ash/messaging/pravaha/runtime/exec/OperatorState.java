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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an operator is holding, read back as text for somebody looking at it (ADR-048).
 *
 * <p>Text, not the values themselves, and that is the decision this class records. State lives in
 * arena memory as a flyweight whose bytes are reused as soon as the next row arrives, so handing a
 * caller anything that points into it hands them something that changes under their feet. Rendering
 * each entry once, into strings, costs a copy per page and cannot alias.
 *
 * <p>And it is <strong>read-only by construction</strong>: nothing here can be written back, so
 * inspecting a join's index cannot evict from it and inspecting an aggregate cannot re-emit it.
 * That is why the debugger inspects through this rather than through the operators themselves.
 */
public final class OperatorState {

    private OperatorState() {}

    /** One inspectable piece of state: which operator, what kind, and how much it holds. */
    public record Slot(String id, String kind, String label, long entries) {}

    /**
     * One key's state: a join's rows for that key, a group's accumulators, a window's contents.
     *
     * <p>The values keep the order the operator reported them in, which is the order somebody
     * reading a group's accumulators expects -- so a {@link LinkedHashMap} rather than {@code
     * Map.copyOf}, whose iteration order is deliberately unspecified.
     */
    public record Entry(String key, Map<String, String> values) {

        public Entry {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /** The columns in the order the operator reported them. */
        public List<String> columns() {
            return List.copyOf(values.keySet());
        }
    }

    /** A page of one operator's state: bounded, and honest about how much it did not show. */
    public record Page(
            String id, String kind, String keyFilter, int offset, int limit, long total, List<Entry> entries) {

        public Page {
            entries = List.copyOf(entries);
        }

        /** Whether another page follows this one. */
        public boolean hasMore() {
            return offset + entries.size() < total;
        }
    }
}

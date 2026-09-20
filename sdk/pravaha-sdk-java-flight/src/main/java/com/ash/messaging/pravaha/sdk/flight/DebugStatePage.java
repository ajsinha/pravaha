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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One page of one operator's state inside a debug session's fork (ADR-048).
 *
 * @param id the operator, as {@link PravahaFlightClient#debugState} named it
 * @param kind {@code aggregate}, {@code global}, {@code window} or {@code join}
 * @param key the key this page was filtered to, or null for every key
 * @param offset where the page started
 * @param limit how many entries were asked for
 * @param total how many there are in all, which is how a screen knows there is more
 * @param entries the page itself, each a key and that key's columns in the operator's own order
 */
public record DebugStatePage(
        String id, String kind, String key, int offset, int limit, long total, List<Entry> entries) {

    /** One key's state: a join's row, a group's accumulators, a window's contents. */
    public record Entry(String key, Map<String, String> values) {}

    /** Whether another page follows this one. */
    public boolean hasMore() {
        return offset + entries.size() < total;
    }

    static DebugStatePage of(List<String> fields) {
        int[] at = {7};
        int count = (int) Wire.number(fields, 6);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = Wire.text(fields, at[0]++);
            int columns = (int) Wire.number(fields, at[0]++);
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < columns; column++) {
                values.put(Wire.text(fields, at[0]++), Wire.text(fields, at[0]++));
            }
            entries.add(new Entry(key, values));
        }
        String key = Wire.text(fields, 2);
        return new DebugStatePage(
                Wire.text(fields, 0),
                Wire.text(fields, 1),
                key.isEmpty() ? null : key,
                (int) Wire.number(fields, 3),
                (int) Wire.number(fields, 4),
                Wire.number(fields, 5),
                entries);
    }
}

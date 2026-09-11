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

import java.util.Iterator;
import java.util.List;

/**
 * One commit's worth of changes, delivered to a subscriber.
 *
 * <p>A batch is a <strong>commit</strong>, not an arbitrary chunk. That boundary is the contract:
 * between commits the view holds a half-applied window, so a consumer woken per row could act on a
 * total that was still being assembled. Handling a batch as a unit is handling a consistent state.
 */
public record ChangeBatch(List<Row> rows) implements Iterable<Row> {

    public ChangeBatch {
        rows = List.copyOf(rows);
    }

    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public Iterator<Row> iterator() {
        return rows.iterator();
    }
}

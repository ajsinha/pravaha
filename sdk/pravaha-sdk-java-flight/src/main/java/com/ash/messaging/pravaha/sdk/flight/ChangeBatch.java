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
 * One commit's worth of changes, delivered to a subscriber -- or, first on a snapshot subscription,
 * the view it starts from.
 *
 * <p>A batch is a <strong>commit</strong>, not an arbitrary chunk. That boundary is the contract:
 * between commits the view holds a half-applied window, so a consumer woken per row could act on a
 * total that was still being assembled. Handling a batch as a unit is handling a consistent state.
 *
 * <p>On a subscription opened with {@link PravahaFlightClient#subscribeFromSnapshot}, the first batch
 * is the snapshot ({@link #isSnapshot()}): every row of the view at a commit, with its multiplicity
 * as its weight, delivered even when there are none. Every batch after it is a commit after that one,
 * so adding their weights to the snapshot's gives the view with nothing missed and nothing counted
 * twice (SUB-1). A plain subscription's batches are never snapshots and carry no frontier.
 *
 * @param snapshot whether this is the snapshot a subscription started from
 * @param frontier the committed frontier this batch brings the view to; {@link Long#MIN_VALUE} on a
 *     plain subscription, whose server does not say
 */
public record ChangeBatch(List<Row> rows, boolean snapshot, long frontier) implements Iterable<Row> {

    public ChangeBatch {
        rows = List.copyOf(rows);
    }

    /** A commit on a plain subscription, as batches were before snapshots existed. */
    public ChangeBatch(List<Row> rows) {
        this(rows, false, Long.MIN_VALUE);
    }

    /** Whether this is the view a snapshot subscription started from, rather than a commit. */
    public boolean isSnapshot() {
        return snapshot;
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

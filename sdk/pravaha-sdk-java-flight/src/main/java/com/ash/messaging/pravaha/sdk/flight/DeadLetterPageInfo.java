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

import java.util.List;

/**
 * A page of one query's dead letters, newest first, with the queue's totals.
 *
 * <p>The totals come with the page rather than from a second call, because the first thing anyone
 * does with a page of failures is ask how many there are -- and a second call answers from a
 * different moment.
 *
 * @param evicted how many entries retention has removed and are gone. Never omitted: a depth
 *     without it cannot be read, since a queue steady at two thousand is either one bad afternoon
 *     or a bound throwing two thousand a minute away
 * @param retention the bound in force, as words
 * @param configured whether the server has a dead-letter directory at all. False means records
 *     that cannot be decoded stop the source instead of being kept -- a different state from an
 *     empty queue, and said differently
 */
public record DeadLetterPageInfo(
        String query,
        List<DeadLetterInfo> entries,
        int offset,
        long total,
        long bytes,
        long evicted,
        long evictedBytes,
        long replayed,
        long failedAgain,
        String retention,
        boolean configured) {

    public DeadLetterPageInfo {
        entries = List.copyOf(entries);
    }

    /** Whether there are older entries past this page. */
    public boolean hasMore() {
        return offset + entries.size() < total;
    }
}

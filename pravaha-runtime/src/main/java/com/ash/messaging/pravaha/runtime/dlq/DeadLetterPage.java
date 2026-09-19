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
package com.ash.messaging.pravaha.runtime.dlq;

import java.util.List;

/**
 * One page of a query's dead letters, newest first.
 *
 * <p>Carries the total beside the rows, because the first thing anybody does with a page of
 * failures is ask how many there are altogether, and a caller that has to make a second call for
 * that gets a number from a different moment.
 *
 * @param entries the page, newest first
 * @param offset how many newer entries were skipped
 * @param limit the page size actually used, which may be smaller than the one asked for
 * @param total how many entries the query's queue held when the page was read
 */
public record DeadLetterPage(List<DeadLetterEntry> entries, int offset, int limit, long total) {

    /** The largest page any surface will return, whatever it asks for. */
    public static final int MAX_LIMIT = 500;

    /** What a surface uses when the caller names no page size. */
    public static final int DEFAULT_LIMIT = 50;

    public DeadLetterPage {
        entries = List.copyOf(entries);
    }

    public static DeadLetterPage empty(int offset, int limit) {
        return new DeadLetterPage(List.of(), Math.max(0, offset), clamp(limit), 0);
    }

    /** A page size within bounds: negative and zero mean the default, and nothing exceeds the maximum. */
    public static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** Whether there are older entries past this page. */
    public boolean more() {
        return offset + entries.size() < total;
    }
}

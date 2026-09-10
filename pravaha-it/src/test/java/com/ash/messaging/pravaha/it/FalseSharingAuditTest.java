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
package com.ash.messaging.pravaha.it;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.common.queue.MpscLongRing;
import com.ash.messaging.pravaha.common.queue.RowInbox;
import com.ash.messaging.pravaha.common.queue.SpscRowRing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every cross-thread cursor is padded apart, and stays padded.
 *
 * <p>False sharing is the ideal silent regression. Deleting the padding fields breaks no test,
 * changes no answer, and passes review easily -- they look like dead code, because that is exactly
 * what they look like. What it does is put two cursors written by different threads on one cache
 * line, so every operation on either costs a coherence miss, and the queue quietly runs at a
 * fraction of its speed at precisely the lane counts where it matters most.
 *
 * <p>This test is the cheap half of P2-10: it fails the moment somebody removes the padding, in
 * milliseconds, deterministically, on any machine. {@code FalseSharingBenchmark} is the other half
 * and measures what the padding is worth, which no unit test can.
 *
 * <p><strong>What this cannot prove.</strong> Declared padding is not laid-out padding: HotSpot
 * arranges fields as it likes, and only {@code jdk.internal.vm.annotation.Contended} guarantees
 * separation. That annotation needs {@code -XX:-RestrictContended}, and an embedded engine inherits
 * its host application's launch arguments -- the same reason Agrona cannot be the default memory
 * accessor (design section 4.6). So the padding is the pragmatic technique every serious JVM queue
 * uses, this test guards it against deletion, and the benchmark checks it is still doing something.
 */
class FalseSharingAuditTest {

    /** How many padding longs a cache line needs beside a cursor: 64 bytes, minus the cursor itself. */
    private static final int LONGS_PER_CACHE_LINE = 7;

    @Test
    void everyMultiThreadedQueueKeepsItsCursorsOnSeparateCacheLines() {
        assertPadded(MpscLongRing.class, "producerIndex", "consumerIndex");
        assertPadded(RowInbox.class, "producerIndex", "drainIndex", "releaseIndex");
        assertPadded(SpscRowRing.class, "producerIndex", "drainIndex", "releaseIndex");
    }

    /**
     * Asserts that consecutive cursors are separated by at least a cache line of padding.
     *
     * <p>Reads declaration order, which is what a human editing the file sees and therefore what a
     * human deleting the padding changes.
     */
    private static void assertPadded(Class<?> type, String... cursorsInOrder) {
        List<String> declared = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == long.class) {
                declared.add(field.getName());
            }
        }

        for (int i = 0; i + 1 < cursorsInOrder.length; i++) {
            String first = cursorsInOrder[i];
            String second = cursorsInOrder[i + 1];
            int from = declared.indexOf(first);
            int to = declared.indexOf(second);
            assertThat(from)
                    .as(
                            "%s declares no long field named %s; the audit is looking at the wrong name",
                            type.getSimpleName(), first)
                    .isNotNegative();
            assertThat(to)
                    .as("%s declares no long field named %s", type.getSimpleName(), second)
                    .isGreaterThan(from);

            int padding = to - from - 1;
            assertThat(padding)
                    .as("""
                            %s has only %d padding longs between %s and %s, and needs at least %d.

                            Those fields look like dead code and are not: without them two cursors \
                            written by different threads share a cache line, every operation on \
                            either costs a coherence miss, and the queue runs at a fraction of its \
                            speed with no test failing and no answer changing.""", type.getSimpleName(), padding, first, second, LONGS_PER_CACHE_LINE)
                    .isGreaterThanOrEqualTo(LONGS_PER_CACHE_LINE);
        }
    }
}

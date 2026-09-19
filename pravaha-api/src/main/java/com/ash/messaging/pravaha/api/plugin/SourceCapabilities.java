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
package com.ash.messaging.pravaha.api.plugin;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a source can and cannot do.
 *
 * <p>This record is what makes the engine's guarantees honest. Aerospike Community Edition has no
 * change feed at all, and the strategies that approximate one differ in whether they can rewind,
 * whether they see deletes, and whether they carry a before-image (design section 19.1). Rather than
 * pretend those differences away, a source declares them and the engine degrades the query's stated
 * guarantee to match -- visibly, in the API response and the console.
 *
 * @param replayableOffsets whether the source can resume from a recorded offset. Without this,
 *     exactly-once is impossible no matter what the engine does.
 * @param orderedWithinPartition whether records arrive in order within one partition
 * @param emitsDeletes whether the feed carries tombstones. A source that cannot see deletes will
 *     leave a maintained view holding rows that no longer exist.
 * @param emitsBeforeImage whether an update carries the previous row, which retract-mode output and
 *     correct aggregate maintenance need
 * @param guarantee the strongest delivery this source supports
 * @param pushdown what the source can absorb
 * @param typicalLatency an honest estimate, used for planning and shown to operators
 * @param repeatsRows whether, in normal running and not only after a failure, the source can deliver
 *     a row it has already delivered -- a periodic scan re-reading an unchanged row, or a poll
 *     re-reading an updated one -- without retracting the earlier delivery. Such a feed is not a
 *     changelog: every copy arrives at weight {@code +1}, so anything whose answer depends on how
 *     many times a row arrived (an aggregate, a join, an append-only sink) is wrong, and the
 *     registry refuses it ({@code PRV-2042}, SCAN-1). A keyed view of the rows themselves is
 *     unaffected: the repeat overwrites its key with the same values. False unless a source says
 *     otherwise, which is the right default only because every source that repeats says so
 */
public record SourceCapabilities(
        boolean replayableOffsets,
        boolean orderedWithinPartition,
        boolean emitsDeletes,
        boolean emitsBeforeImage,
        DeliveryGuarantee guarantee,
        Set<PushdownKind> pushdown,
        Duration typicalLatency,
        boolean repeatsRows) {

    public SourceCapabilities {
        pushdown = pushdown == null ? EnumSet.noneOf(PushdownKind.class) : Set.copyOf(pushdown);
        if (guarantee == DeliveryGuarantee.EXACTLY_ONCE && !replayableOffsets) {
            // Caught at declaration rather than discovered during recovery.
            throw new IllegalArgumentException(
                    "a source cannot offer EXACTLY_ONCE without replayable offsets: there is no way to "
                            + "resume without either losing or duplicating records");
        }
        if (guarantee == DeliveryGuarantee.EXACTLY_ONCE && repeatsRows) {
            throw new IllegalArgumentException(
                    "a source that repeats rows cannot offer EXACTLY_ONCE: every repeat is a row counted "
                            + "twice, whatever the offsets say");
        }
    }

    /**
     * A source that never repeats a row -- the declaration every source made before {@code
     * repeatsRows} existed, and still the right one for a changelog, a log or a file read once.
     */
    public SourceCapabilities(
            boolean replayableOffsets,
            boolean orderedWithinPartition,
            boolean emitsDeletes,
            boolean emitsBeforeImage,
            DeliveryGuarantee guarantee,
            Set<PushdownKind> pushdown,
            Duration typicalLatency) {
        this(
                replayableOffsets,
                orderedWithinPartition,
                emitsDeletes,
                emitsBeforeImage,
                guarantee,
                pushdown,
                typicalLatency,
                false);
    }

    public boolean supports(PushdownKind kind) {
        return pushdown.contains(kind);
    }

    /** A minimal, honest default for a source that can do little: at-least-once, no pushdown. */
    public static SourceCapabilities minimal() {
        return new SourceCapabilities(
                false,
                true,
                false,
                false,
                DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofSeconds(1));
    }
}

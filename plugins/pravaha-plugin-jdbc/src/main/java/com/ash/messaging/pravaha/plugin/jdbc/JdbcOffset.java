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
package com.ash.messaging.pravaha.plugin.jdbc;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * A high-water mark, a tie-breaking key, and a count -- in that order of preference.
 *
 * <p><strong>Watermark columns are not unique</strong>, and that fact decides this whole design. A
 * batch job stamping a thousand rows writes one timestamp on all of them, and SQL defines no order
 * among rows that tie on the {@code ORDER BY} column: the database may return them differently on
 * every execution, and H2 demonstrably does. Every naive resume is therefore wrong in one of two
 * ways -- {@code >} loses rows that commit at the boundary value after the poll, and {@code >=}
 * re-emits the boundary on every poll forever.
 *
 * <p>Counting rows already emitted at the boundary fixes the second and only appears to fix the
 * first: it assumes the database returns tied rows in a stable order, which it does not promise and
 * does not do. That was found here by a test that read rows 3 and 2 where it expected 1 and 2.
 *
 * <p>So the real fix is a <strong>unique tie-breaking key</strong> in the sort, making the order
 * total and the resume exact: {@code WHERE w > ? OR (w = ? AND k > ?) ORDER BY w, k}. Without such a
 * key the plugin falls back to counting and <em>declares itself non-replayable</em>, because a
 * resume that is right only when the database feels like it is not a guarantee.
 *
 * @param watermark the highest watermark value emitted
 * @param key the key of the last row emitted at that watermark, when a key column is configured
 * @param emittedAtWatermark rows emitted at that watermark, used only in the keyless fallback
 */
public record JdbcOffset(long watermark, long key, long emittedAtWatermark) {

    /** Before anything has been read. */
    public static final JdbcOffset BEGINNING = new JdbcOffset(Long.MIN_VALUE, Long.MIN_VALUE, 0L);

    public JdbcOffset {
        if (emittedAtWatermark < 0) {
            throw new IllegalArgumentException("emitted count must be non-negative");
        }
    }

    public static JdbcOffset parse(SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return BEGINNING;
        }
        String token = offset.token();
        try {
            String[] parts = token.split(";");
            if (parts.length != 3
                    || !parts[0].startsWith("w=")
                    || !parts[1].startsWith("k=")
                    || !parts[2].startsWith("n=")) {
                throw new IllegalArgumentException("expected w=<watermark>;k=<key>;n=<count>");
            }
            return new JdbcOffset(
                    Long.parseLong(parts[0].substring(2)),
                    Long.parseLong(parts[1].substring(2)),
                    Long.parseLong(parts[2].substring(2)));
        } catch (RuntimeException e) {
            throw new PravahaException(
                    JdbcErrors.MALFORMED_OFFSET,
                    "cannot resume from offset '" + token + "': it is not a JDBC offset of the form "
                            + "w=<watermark>;k=<key>;n=<rows emitted at that watermark>.",
                    e);
        }
    }

    public SourceOffset toSourceOffset() {
        return new SourceOffset("w=" + watermark + ";k=" + key + ";n=" + emittedAtWatermark);
    }

    public boolean isBeginning() {
        return watermark == Long.MIN_VALUE && emittedAtWatermark == 0L;
    }

    /** Records that a row has been emitted. */
    public JdbcOffset advanced(long newWatermark, long newKey) {
        return newWatermark == watermark
                ? new JdbcOffset(watermark, newKey, emittedAtWatermark + 1)
                : new JdbcOffset(newWatermark, newKey, 1L);
    }
}

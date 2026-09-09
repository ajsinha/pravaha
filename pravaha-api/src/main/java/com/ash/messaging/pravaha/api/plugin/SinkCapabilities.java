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

import java.util.EnumSet;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.EmitMode;

/**
 * What a sink accepts.
 *
 * <p>Negotiated at query registration, not discovered at run time. A {@code LEFT JOIN} or an
 * unbounded aggregate produces updates; if the configured sink is append-only the query is rejected
 * with a message naming the offending operator and suggesting a fix, rather than running for a week
 * and writing nonsense (design section 15.5).
 *
 * @param emitModes the changelog modes this sink can consume
 * @param transactional whether the sink supports two-phase commit
 * @param idempotentUpsert whether a repeated identical write is harmless, which is what makes
 *     effectively-once output possible without transactions
 * @param maxBatchRows the largest batch the sink wants; zero means no preference
 */
public record SinkCapabilities(
        Set<EmitMode> emitModes, boolean transactional, boolean idempotentUpsert, int maxBatchRows) {

    public SinkCapabilities {
        emitModes = emitModes == null || emitModes.isEmpty() ? EnumSet.of(EmitMode.APPEND) : Set.copyOf(emitModes);
        if (maxBatchRows < 0) {
            throw new IllegalArgumentException("maxBatchRows must be non-negative, got " + maxBatchRows);
        }
    }

    public boolean accepts(EmitMode mode) {
        return emitModes.contains(mode);
    }

    /** The strongest delivery this sink can support, given what it declares. */
    public DeliveryGuarantee guarantee() {
        if (transactional) {
            return DeliveryGuarantee.EXACTLY_ONCE;
        }
        // Idempotent upsert gives "effectively once": replays overwrite with identical values, so
        // the end state is correct even though the write happened more than once.
        return idempotentUpsert ? DeliveryGuarantee.EXACTLY_ONCE : DeliveryGuarantee.AT_LEAST_ONCE;
    }

    /** Append-only, non-transactional: the weakest useful sink, and an honest default. */
    public static SinkCapabilities appendOnly() {
        return new SinkCapabilities(EnumSet.of(EmitMode.APPEND), false, false, 0);
    }
}

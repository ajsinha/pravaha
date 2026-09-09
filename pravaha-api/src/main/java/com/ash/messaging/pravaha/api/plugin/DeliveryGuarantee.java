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

/**
 * What a source or sink can actually promise.
 *
 * <p>Declared by the plugin rather than assumed by the engine, so that the engine can compute the
 * <strong>weakest link</strong> across source, engine and sinks and report *that* as the query's
 * effective guarantee (design section 14.4). Claiming exactly-once when the source cannot rewind is the
 * kind of over-promise that is discovered during an incident.
 */
public enum DeliveryGuarantee {

    /** Records may be lost. */
    AT_MOST_ONCE,

    /** No record is lost; some may be delivered more than once. */
    AT_LEAST_ONCE,

    /**
     * Each record affects the result exactly once.
     *
     * <p>For a source this requires replayable offsets. For a sink it requires either a
     * transaction or a deterministic idempotent write.
     */
    EXACTLY_ONCE;

    /** The weaker of two guarantees -- how an end-to-end promise is computed. */
    public DeliveryGuarantee weakest(DeliveryGuarantee other) {
        return compareTo(other) <= 0 ? this : other;
    }
}

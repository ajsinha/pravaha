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
package com.ash.messaging.pravaha.api.data;

/**
 * How a query's changelog is presented to a sink.
 *
 * <p>Internally there are only signed weights (design section 9.2); this is how they are rendered at the
 * boundary. The mode is declared per query and checked against the sink's capabilities at
 * registration, because a mismatch is a correctness problem and registration is the last moment it
 * can be caught cheaply.
 */
public enum EmitMode {

    /**
     * Inserts only.
     *
     * <p>The planner rejects a query in this mode if its plan can produce updates -- an outer join
     * or an unbounded aggregate can, and a sink that cannot express a retraction would silently
     * accumulate wrong rows.
     */
    APPEND,

    /**
     * Inserts and updates collapsed onto a declared key; deletes as removals.
     *
     * <p>Requires a sink that supports keyed upsert: Aerospike, Redis, JDBC, a compacted Kafka topic.
     */
    UPSERT,

    /**
     * Full retract-then-insert pairs.
     *
     * <p>For sinks that can handle a negative row -- another Pravaha query, or a Kafka topic being
     * consumed by one.
     */
    RETRACT
}

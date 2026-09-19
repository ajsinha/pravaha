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
package com.ash.messaging.pravaha.state;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * State-tier error codes, PRV-4nnn.
 *
 * <p>Numbers are never reused and never renumbered: an operator who has seen PRV-4001 once should
 * find the same thing behind it in five years.
 */
public final class StateErrors {

    /** State exceeded the memory it was given. Always a bounded-state problem, never a transient one. */
    public static final ErrorCode STATE_TOO_LARGE = new ErrorCode(4001, "STATE_TOO_LARGE");

    /** A stored state image could not be read back: truncated, corrupt, or from another version. */
    public static final ErrorCode STATE_UNREADABLE = new ErrorCode(4002, "STATE_UNREADABLE");

    /**
     * The spill tier's byte quota, {@code pravaha.state.spill.max-bytes}, would be passed by the next
     * overflow slab.
     *
     * <p>ADR-044. The node's disk budget for spilled state, across every query on it: a query whose
     * state needs a slab past it stops, as it would at its memory ceiling without a tier -- a bound
     * has to exist somewhere, and eviction is not one a Z-set can have.
     */
    public static final ErrorCode SPILL_QUOTA_REACHED = new ErrorCode(4005, "STATE_SPILL_QUOTA_REACHED");

    /**
     * The spill directory's filesystem has less free space than the next overflow slab needs.
     *
     * <p>ADR-044. Refused before the slab is created. A sparse mapped file takes its disk page by page
     * as it is written, so the alternative is finding the full disk inside a write to mapped memory --
     * a fault from whichever operator touched the page, not an error with a name.
     */
    public static final ErrorCode SPILL_DISK_FULL = new ErrorCode(4006, "STATE_SPILL_DISK_FULL");

    /**
     * {@code pravaha.dlq.directory} is set and this node cannot write there.
     *
     * <p>TIME-4. Refused rather than degraded: an operator who configured a dead-letter queue asked
     * for records to be kept, and carrying on without one would hand them exactly the behaviour
     * they configured it to avoid -- a decode failure ending the poll and taking the file with it.
     */
    public static final ErrorCode DLQ_UNUSABLE = new ErrorCode(4090, "STATE_DLQ_UNUSABLE");

    private StateErrors() {}
}

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

    /**
     * No dead letter with that id is in this query's queue.
     *
     * <p>B5. Its own code rather than a generic not-found, because there are three ordinary ways to
     * meet it and they need different actions: the id was mistyped, the entry was replayed and the
     * caller is holding an id from a stale page, or retention evicted it -- and the third is the
     * one worth knowing, since the queue's eviction count says whether it is plausible.
     */
    public static final ErrorCode DLQ_NO_SUCH_LETTER = new ErrorCode(4091, "STATE_DLQ_NO_SUCH_LETTER");

    /**
     * Replaying this dead letter could not be correct, so it was not attempted.
     *
     * <p>B5. A replay is a new row at the query's current frontier, which is right for a record
     * that was never counted and wrong in three cases that this refuses by name: the query that
     * rejected it is no longer registered, the stream's schema has changed since (so the bytes
     * would decode into a different row than the one that was rejected), and the source promises
     * exactly-once delivery with replayable offsets -- where the record is still readable at its
     * own offset, and re-feeding it at the frontier would count it twice. The message says which.
     */
    public static final ErrorCode DLQ_REPLAY_REFUSED = new ErrorCode(4092, "STATE_DLQ_REPLAY_REFUSED");

    /**
     * {@code pravaha.checkpoint.directory} is set and this node cannot checkpoint into it.
     *
     * <p>CFG-7. Refused at startup, where a bad path is one failure, rather than at the first
     * registration, where it is every query failing separately on a node that started healthy,
     * passes every probe, and logged "checkpointing registered queries under …" about a path that
     * is a regular file.
     *
     * <p>4093 and not 4091: the dead-letter batch took 4091 and 4092 while this branch was held
     * un-rebased, and a code is what goes in a runbook.
     */
    public static final ErrorCode CHECKPOINT_DIRECTORY_UNUSABLE =
            new ErrorCode(4093, "STATE_CHECKPOINT_DIRECTORY_UNUSABLE");

    /**
     * A checkpoint whose contents do not match the CRC32C written with them (CKPTSUM-1).
     *
     * <p>Skipped, as a truncated one is, and the restore falls back to the one before it: a flipped
     * bit was restored as state and published as an answer for ever, because nothing checked the
     * bytes between the header and the trailer.
     */
    public static final ErrorCode CHECKPOINT_CORRUPT = new ErrorCode(4094, "STATE_CHECKPOINT_CORRUPT");

    /**
     * A checkpoint taken when the query's output had another schema (RETYPERESTORE-1).
     *
     * <p>Not restored: the query rebuilds from its sources instead, because the view would otherwise
     * hold values of the old types beside rows of the new ones.
     */
    public static final ErrorCode CHECKPOINT_SCHEMA_CHANGED = new ErrorCode(4095, "STATE_CHECKPOINT_SCHEMA_CHANGED");

    private StateErrors() {}
}

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
package com.ash.messaging.pravaha.backfill;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Backfill error codes, in the state range because a backfill's failures are all about held rows. */
public final class BackfillErrors {

    /** Changes accumulated during the snapshot faster than the buffer could hold them. */
    public static final ErrorCode BUFFER_FULL = new ErrorCode(4010, "BACKFILL_BUFFER_FULL");

    /** A row arrived with no version, so nothing can be said about whether it is newer. */
    public static final ErrorCode MISSING_VERSION = new ErrorCode(4011, "BACKFILL_MISSING_VERSION");

    /** A key column of a type the splice cannot compare. */
    public static final ErrorCode UNSUPPORTED_KEY = new ErrorCode(4012, "BACKFILL_UNSUPPORTED_KEY");

    /**
     * A backfill read all the history there is without reaching the position it was to splice at.
     *
     * <p>Either the source's positions do not name the record they were taken after, or the history
     * is not the same log the running version is reading. Reading on would deliver the overlap
     * twice, so the backfill stops instead.
     */
    public static final ErrorCode SPLICE_MISSED = new ErrorCode(4013, "BACKFILL_SPLICE_MISSED");

    /** A cutover was asked for before the new version had caught up. */
    public static final ErrorCode NOT_CAUGHT_UP = new ErrorCode(4014, "BACKFILL_NOT_CAUGHT_UP");

    /** A cutover seam at or before the previous one, which would make two versions both responsible. */
    public static final ErrorCode SEAM_WENT_BACKWARDS = new ErrorCode(4015, "BACKFILL_SEAM_WENT_BACKWARDS");

    /** A cutover, rollback or progress report was asked for a name that is not being replaced. */
    public static final ErrorCode NO_REPLACEMENT = new ErrorCode(4016, "BACKFILL_NO_REPLACEMENT");

    /**
     * A replacement was started for a name that already has one.
     *
     * <p>One shadow at a time. Two candidates for one name is a decision tree rather than a
     * deployment, and the second would have to be compared against a version that may never serve.
     */
    public static final ErrorCode REPLACEMENT_IN_PROGRESS = new ErrorCode(4017, "BACKFILL_REPLACEMENT_IN_PROGRESS");

    /**
     * A replacement's backfill cannot read a stream the query names.
     *
     * <p>Nothing is bound to it, or the source cannot be read from a position it handed out -- and a
     * backfill that cannot replay history would start the new version from an empty state and call
     * it caught up.
     */
    public static final ErrorCode SOURCE_UNSUPPORTED = new ErrorCode(4018, "BACKFILL_SOURCE_UNSUPPORTED");

    /**
     * A subscription ended because the view it was following was replaced at a cutover.
     *
     * <p>Not a failure of the subscription. The name now answers a different question, and handing a
     * subscriber the new version's changes on top of the old version's would be a copy that is a
     * mixture of two queries with nothing to say so. Subscribe again.
     */
    public static final ErrorCode VIEW_REPLACED = new ErrorCode(4019, "BACKFILL_VIEW_REPLACED");

    private BackfillErrors() {}
}

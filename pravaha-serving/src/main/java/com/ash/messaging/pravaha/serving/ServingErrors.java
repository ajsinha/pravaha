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
package com.ash.messaging.pravaha.serving;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Serving-layer error codes, PRV-4nnn: a served view is state, and its failures are state's. */
public final class ServingErrors {

    /** A read asked for a past frontier, which a view does not keep. */
    public static final ErrorCode NO_HISTORY = new ErrorCode(4020, "SERVING_NO_HISTORY");

    /** An AT_LEAST read waited for a frontier the view did not reach in time. */
    public static final ErrorCode READ_TIMED_OUT = new ErrorCode(4021, "SERVING_READ_TIMED_OUT");

    /** The view holds more keys than it was given room for. */
    public static final ErrorCode VIEW_TOO_LARGE = new ErrorCode(4022, "SERVING_VIEW_TOO_LARGE");

    /** A query named a view this server does not serve. */
    public static final ErrorCode NO_SUCH_VIEW = new ErrorCode(4023, "SERVING_NO_SUCH_VIEW");

    /**
     * The query behind a view has failed, so the view is no longer current.
     *
     * <p>E-13, and **declared twice on purpose**. This is `RegistryErrors.QUERY_FAILED`, and
     * `pravaha-serving` cannot see it: the registry depends on serving, so depending back would be a
     * cycle. The alternative -- a serving-specific code -- would mean one event answering under two
     * numbers depending on which door the reader came through, which is worse than a duplicate
     * declaration of one number.
     *
     * <p>Number and name are copied verbatim so that `ErrcCrossCuttingTest`'s "one code means one
     * thing" check fails loudly if either copy is edited alone. The same boundary produced the same
     * duplicate for `PRV-2061` in the Java SDK; moving both into `pravaha-api` is the right shape
     * and is its own change.
     */
    public static final ErrorCode QUERY_FAILED = new ErrorCode(8004, "REGISTRY_QUERY_FAILED");

    /** A single request produced more rows than one response may carry. */
    public static final ErrorCode RESULT_TOO_LARGE = new ErrorCode(4024, "SERVING_RESULT_TOO_LARGE");

    /** A query shape this server does not answer -- see ADR-030 for what is deliberately excluded. */
    public static final ErrorCode UNSUPPORTED_QUERY = new ErrorCode(4025, "SERVING_UNSUPPORTED_QUERY");

    /**
     * The server is at its read concurrency limit and the queue is full.
     *
     * <p>Distinct from {@link #READ_QUEUE_TIMED_OUT}: this one never waited. A client seeing it
     * knows the server is saturated right now, which is a different signal from one that waited its
     * turn and ran out of patience.
     */
    public static final ErrorCode READ_REJECTED = new ErrorCode(4026, "SERVING_READ_REJECTED");

    /** A read waited for a permit and gave up before getting one. */
    public static final ErrorCode READ_QUEUE_TIMED_OUT = new ErrorCode(4027, "SERVING_READ_QUEUE_TIMED_OUT");

    /** One tenant is already using its whole share of the read concurrency. */
    public static final ErrorCode TENANT_QUOTA_EXCEEDED = new ErrorCode(4028, "SERVING_TENANT_QUOTA_EXCEEDED");

    /** A read ran past its deadline and was stopped mid-scan. */
    public static final ErrorCode READ_DEADLINE_EXCEEDED = new ErrorCode(4029, "SERVING_READ_DEADLINE_EXCEEDED");

    private ServingErrors() {}
}

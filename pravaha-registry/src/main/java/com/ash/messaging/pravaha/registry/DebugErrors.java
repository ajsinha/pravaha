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
package com.ash.messaging.pravaha.registry;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * What the time-travel debugger refuses, and why (ADR-048).
 *
 * <p>Each of these is a refusal <em>by name</em>: the thing that cannot be done is named in the
 * message, along with the query, the stream or the session it applies to. A debugger is opened by
 * somebody who is already confused about a query, so "cannot fork" without a reason is worse than
 * useless.
 */
public final class DebugErrors {

    /**
     * There is no checkpoint to fork from: this node does not checkpoint, this query has not taken
     * one yet, or the id asked for has been pruned.
     */
    public static final ErrorCode NO_CHECKPOINT = new ErrorCode(8011, "DEBUG_NO_CHECKPOINT");

    /**
     * A source cannot be rewound to the checkpoint's position -- nothing is bound to the stream, or
     * the plugin does not offer replayable offsets.
     */
    public static final ErrorCode SOURCE_NOT_REPLAYABLE = new ErrorCode(8012, "DEBUG_SOURCE_NOT_REPLAYABLE");

    /** No debug session answers to that id: it never existed, it was ended, or it expired. */
    public static final ErrorCode NO_SUCH_SESSION = new ErrorCode(8013, "DEBUG_NO_SUCH_SESSION");

    /** This node already holds as many debug sessions as it allows. */
    public static final ErrorCode TOO_MANY_SESSIONS = new ErrorCode(8014, "DEBUG_TOO_MANY_SESSIONS");

    /** A step, a predicate or a page this session cannot make sense of. */
    public static final ErrorCode BAD_STEP = new ErrorCode(8015, "DEBUG_BAD_STEP");

    /** The query this session forked from has been dropped or replaced since the fork. */
    public static final ErrorCode QUERY_GONE = new ErrorCode(8016, "DEBUG_QUERY_GONE");

    private DebugErrors() {}
}

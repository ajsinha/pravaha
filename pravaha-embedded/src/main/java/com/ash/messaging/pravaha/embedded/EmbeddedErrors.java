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
package com.ash.messaging.pravaha.embedded;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Embedded-engine error codes, PRV-810n: an application talking to an engine in its own process.
 *
 * <p>In the registry's 8xxx range because every one of these is about what an embedder asked the
 * registry it hosts to do -- push a row, declare a stream, register a query -- and 810n rather than
 * 800n so the registry's own codes keep room to grow.
 */
public final class EmbeddedErrors {

    /** A row was pushed to a stream this engine was never told about. */
    public static final ErrorCode UNKNOWN_STREAM = new ErrorCode(8101, "EMBEDDED_UNKNOWN_STREAM");

    /** A pushed row does not fit its stream: wrong arity, a value of the wrong kind, a null in NOT NULL. */
    public static final ErrorCode ROW_REJECTED = new ErrorCode(8102, "EMBEDDED_ROW_REJECTED");

    /** A computation's inbox stayed full for longer than a push was prepared to wait. */
    public static final ErrorCode BACKPRESSURE = new ErrorCode(8103, "EMBEDDED_BACKPRESSURE");

    /** The engine's configuration says something it cannot do, found at start rather than at first use. */
    public static final ErrorCode MISCONFIGURED = new ErrorCode(8104, "EMBEDDED_MISCONFIGURED");

    private EmbeddedErrors() {}
}

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
package com.ash.messaging.pravaha.sdk;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Client-side error codes, PRV-1nnn, sharing the engine's catalogue. */
public final class ClientErrors {

    /** The server could not be reached. Retryable: a server may come back. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(1040, "CLIENT_CONNECT_FAILED");

    /** The server refused the query. Not retryable: the same SQL will be refused again. */
    public static final ErrorCode QUERY_REFUSED = new ErrorCode(1041, "CLIENT_QUERY_REFUSED");

    /** A result could not be read. */
    public static final ErrorCode READ_FAILED = new ErrorCode(1042, "CLIENT_READ_FAILED");

    /** A client operation used after close. */
    public static final ErrorCode CLOSED = new ErrorCode(1043, "CLIENT_CLOSED");

    private ClientErrors() {}
}

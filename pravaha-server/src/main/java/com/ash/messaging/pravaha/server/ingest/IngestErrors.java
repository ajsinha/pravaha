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
package com.ash.messaging.pravaha.server.ingest;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Ingestion error codes, PRV-509n: attaching a source to a registered query.
 *
 * <p>In the 5xxx plugin range rather than the 8xxx registry range, because every failure here is a
 * plugin's -- missing, misconfigured, or broken mid-read. The registry's own lifecycle is fine; what
 * failed is the thing on the end of it.
 *
 * <p>509n and not 504n, which is where these started: 5040 was already
 * {@code FILESYSTEM_DECODE_FAILED}, and two different failures answering to one code is what makes a
 * support conversation start with "which 5040?". Caught by {@code ErrorCodeUniquenessTest} rather
 * than by a user.
 */
public final class IngestErrors {

    /** No plugin on the classpath answers to the name a binding gave. */
    public static final ErrorCode NO_SUCH_PLUGIN = new ErrorCode(5090, "INGEST_NO_SUCH_PLUGIN");

    /** The plugin refused its configuration, or could not open what it was pointed at. */
    public static final ErrorCode BINDING_FAILED = new ErrorCode(5091, "INGEST_BINDING_FAILED");

    /** A feed stopped part-way through: the source failed after the query was already running. */
    public static final ErrorCode FEED_FAILED = new ErrorCode(5092, "INGEST_FEED_FAILED");

    private IngestErrors() {}
}

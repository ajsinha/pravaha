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
package com.ash.messaging.pravaha.bindings.egress;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Egress error codes: resolving a {@code pravaha.sinks.*} binding to a plugin.
 *
 * <p>5090-5092 is {@code IngestErrors}, allocated to the ingest side of exactly this problem; these
 * continue the same block for the egress side rather than starting a new one, so the two halves of
 * one mechanism read as neighbours.
 */
public final class EgressErrors {

    /** No sink plugin on the classpath answers to the name a binding gave. */
    public static final ErrorCode NO_SUCH_SINK_PLUGIN = new ErrorCode(5093, "EGRESS_NO_SUCH_SINK_PLUGIN");

    /** The plugin refused its configuration, or could not open what it was pointed at. */
    public static final ErrorCode SINK_BINDING_FAILED = new ErrorCode(5094, "EGRESS_SINK_BINDING_FAILED");

    private EgressErrors() {}
}

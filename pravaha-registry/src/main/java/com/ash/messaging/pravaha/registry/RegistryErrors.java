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

/** Registry error codes, PRV-5nnn: the lifecycle of a registered query. */
public final class RegistryErrors {

    /** A name is already taken by a different computation. */
    public static final ErrorCode NAME_IN_USE = new ErrorCode(5001, "REGISTRY_NAME_IN_USE");

    /** No registered query answers to that name. */
    public static final ErrorCode NO_SUCH_QUERY = new ErrorCode(5002, "REGISTRY_NO_SUCH_QUERY");

    /** The operation is not legal from the state the query is in. */
    public static final ErrorCode ILLEGAL_TRANSITION = new ErrorCode(5003, "REGISTRY_ILLEGAL_TRANSITION");

    /** The query failed while running; its recorded cause says how. */
    public static final ErrorCode QUERY_FAILED = new ErrorCode(5004, "REGISTRY_QUERY_FAILED");

    private RegistryErrors() {}
}

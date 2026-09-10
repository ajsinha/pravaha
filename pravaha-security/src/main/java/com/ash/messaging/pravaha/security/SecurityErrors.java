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
package com.ash.messaging.pravaha.security;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Security error codes, PRV-7nnn. */
public final class SecurityErrors {

    /** No credential, or one that did not verify. */
    public static final ErrorCode UNAUTHENTICATED = new ErrorCode(7001, "SECURITY_UNAUTHENTICATED");

    /** Authenticated, but not permitted. */
    public static final ErrorCode FORBIDDEN = new ErrorCode(7002, "SECURITY_FORBIDDEN");

    /**
     * A row filter that cannot be enforced on the data available.
     *
     * <p>The refusal that keeps ADR-031 honest: a filter naming a column the view does not carry
     * cannot be applied to it, and the alternative to refusing is serving an aggregate that mixed in
     * rows this principal may not see.
     */
    public static final ErrorCode FILTER_NOT_ENFORCEABLE = new ErrorCode(7003, "SECURITY_FILTER_NOT_ENFORCEABLE");

    private SecurityErrors() {}
}

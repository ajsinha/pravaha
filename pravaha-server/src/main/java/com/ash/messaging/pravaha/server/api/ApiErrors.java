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
package com.ash.messaging.pravaha.server.api;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Error codes for the REST surface itself, as opposed to the engine it fronts. */
public final class ApiErrors {

    /**
     * A request body left out a field the endpoint requires.
     *
     * <p>API-F9. {@code POST /api/v1/queries/validate} with body <code>{}</code> or
     * <code>{"sql":null}</code> used to answer {@code 200 valid:false} with the diagnostic
     * {@code PRV-2010  Cannot invoke "String.length()" because "s" is null} -- Java's own
     * {@code NullPointerException} text, wrapped in a planning code and given a help URL that
     * pointed at documentation for a planning failure that had not occurred. {@code /explain}
     * returned the same text as a {@code 400}.
     *
     * <p>Its own code rather than a reused one, and in the 1xxx range rather than the planner's
     * 2xxx, because that is what it is: nothing was planned, and nothing could be. A {@code PRV-2xxx}
     * sends the reader to the SQL documentation for a request that carried no SQL. The category also
     * decides the status -- {@code ApiExceptionHandler.statusFor} maps CONFIGURATION to {@code 400},
     * which is the right answer for a malformed request without anyone maintaining a second table.
     */
    public static final ErrorCode MISSING_FIELD = new ErrorCode(1050, "API_MISSING_FIELD");

    /**
     * A query parameter the endpoint could not read: a timestamp that is not ISO-8601, a decision
     * that is neither {@code allow} nor {@code deny}, a cursor this node did not issue. A 400 that
     * names the parameter, rather than a filter silently ignored -- an audit search that dropped a
     * malformed {@code since} would answer a different question from the one asked and look right.
     */
    public static final ErrorCode INVALID_PARAMETER = new ErrorCode(1051, "API_INVALID_PARAMETER");

    private ApiErrors() {}
}

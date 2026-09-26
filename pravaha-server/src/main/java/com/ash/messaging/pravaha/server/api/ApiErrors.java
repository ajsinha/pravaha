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

    /**
     * The request reached no handler at all: an unmapped path, a method the path does not support,
     * a body in a media type the endpoint does not read.
     *
     * <p>CFG-20. {@code application.yaml} turns RFC 7807 problem details off with the stated intent
     * that "every non-2xx response is an ApiError and nothing else, because a client that has to
     * parse two error shapes will handle one of them badly". Turning them off worked and was never
     * the mechanism that mattered: {@code ApiExceptionHandler} handles {@code PravahaException} and
     * {@code IllegalArgumentException}, and a 404 on an unmapped path, a 405 and a 415 are neither,
     * so three of six non-2xx shapes fell through to Spring's {@code BasicErrorController} and came
     * back as a <em>third</em> shape with no {@code code}, no {@code message} and no
     * {@code helpUrl}.
     *
     * <p>One code rather than one per status, because the status is what distinguishes them and a
     * client already has it. What the code adds is that the body is an {@code ApiError}.
     */
    public static final ErrorCode UNHANDLED_REQUEST = new ErrorCode(1052, "API_UNHANDLED_REQUEST");

    /**
     * A string in a request body is not text: it carries a UTF-16 surrogate with no partner.
     *
     * <p>API-F10. {@code "\ud800"} parses as JSON and decodes to a Java {@code String}, and encodes
     * to nothing -- there is no UTF-8 for it. As a stream name it reached the catalogue and stayed
     * there, unaddressable by any later request; as anything reaching Flight it becomes {@code ?},
     * because protobuf's encoder substitutes rather than fails. See {@link WellFormedTextModule},
     * which is where it is refused and why it is refused there rather than per field.
     *
     * <p>In the 1xxx API range with {@link #MISSING_FIELD} and {@link #INVALID_PARAMETER}, and for
     * the same reason: nothing was planned and nothing could be, so a 2xxx would send the reader to
     * the SQL documentation for a request the SQL never saw.
     */
    public static final ErrorCode MALFORMED_TEXT = com.ash.messaging.pravaha.api.wire.ControlWire.MALFORMED_TEXT;

    private ApiErrors() {}
}

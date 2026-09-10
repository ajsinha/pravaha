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
package com.ash.messaging.pravaha.flight;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Flight gateway error codes, PRV-8nnn. */
public final class FlightErrors {

    /** A column type this gateway will not put on the wire. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(6100, "FLIGHT_UNSUPPORTED_TYPE");

    /** A Flight SQL request this server does not implement. */
    public static final ErrorCode UNSUPPORTED_REQUEST = new ErrorCode(6101, "FLIGHT_UNSUPPORTED_REQUEST");

    private FlightErrors() {}
}

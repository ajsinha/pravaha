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
package com.ash.messaging.pravaha.api;

import org.jspecify.annotations.Nullable;

/**
 * Base class for every checked failure the engine raises.
 *
 * <p>Carries a stable {@link ErrorCode} so that messages can be documented, searched and linked
 * from the console (design section 24.4). An exception without a code is a bug in the throwing code, not a
 * convenience.
 */
public class PravahaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public PravahaException(ErrorCode errorCode, String message) {
        super(format(errorCode, message));
        this.errorCode = errorCode;
    }

    public PravahaException(ErrorCode errorCode, String message, @Nullable Throwable cause) {
        super(format(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    private static String format(ErrorCode code, String message) {
        return code.code() + "  " + message;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Documentation URL for this failure. */
    public String helpUrl() {
        return errorCode.helpUrl();
    }
}

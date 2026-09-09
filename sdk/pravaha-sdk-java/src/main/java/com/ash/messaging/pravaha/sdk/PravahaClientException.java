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
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * A failure raised by the client.
 *
 * <p>Carries the same stable {@code PRV-nnnn} codes the engine uses, so an operator searching for a
 * code finds one page whether the error surfaced in a server log or in an application's stack
 * trace. {@link #retryable()} exists so callers can write a sensible retry policy without parsing
 * messages, which is what they will otherwise do.
 */
public class PravahaClientException extends PravahaException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public PravahaClientException(ErrorCode errorCode, String message, boolean retryable) {
        super(errorCode, message);
        this.retryable = retryable;
    }

    public PravahaClientException(ErrorCode errorCode, String message, boolean retryable, Throwable cause) {
        super(errorCode, message, cause);
        this.retryable = retryable;
    }

    /** Whether retrying the same call unchanged could plausibly succeed. */
    public boolean retryable() {
        return retryable;
    }
}

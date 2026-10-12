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
package com.ash.messaging.pravaha.ai;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * A model call or a provider setup failed. The message never carries a key, a prompt or an upstream
 * body: a vendor's error body can echo the prompt, so only the status and a hint are kept. Each
 * {@link Kind} has its {@code PRV} code ({@link AiErrors}).
 */
public class LlmException extends PravahaException {

    private static final long serialVersionUID = 1L;

    /** What went wrong, for the caller to act on. */
    public enum Kind {
        CONFIG,
        NOT_FOUND,
        UNSUPPORTED,
        POLICY,
        RATE_LIMITED,
        TIMEOUT,
        FAILED;

        /** The lower-case name used in audit rows and JSON. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Kind kind;
    private final String detail;

    public LlmException(Kind kind, String message) {
        this(kind, message, null);
    }

    public LlmException(Kind kind, String message, @Nullable Throwable cause) {
        this(AiErrors.of(kind), kind, message, cause);
    }

    private LlmException(ErrorCode code, Kind kind, String message, @Nullable Throwable cause) {
        super(code, message, cause);
        this.kind = kind;
        this.detail = message;
    }

    /** The refusal every call gets while {@code pravaha.ai.enabled} is false. */
    public static LlmException disabled() {
        return new LlmException(
                AiErrors.DISABLED,
                Kind.CONFIG,
                "the AI layer is switched off (pravaha.ai.enabled is false, or PRAVAHA_AI_ENABLED); "
                        + "no provider file is read and no model is called",
                null);
    }

    /** What went wrong. */
    public Kind kind() {
        return kind;
    }

    /** The message without its code. */
    public String detail() {
        return detail;
    }

    /** Whether the endpoint was too slow. */
    public boolean timeout() {
        return kind == Kind.TIMEOUT;
    }
}

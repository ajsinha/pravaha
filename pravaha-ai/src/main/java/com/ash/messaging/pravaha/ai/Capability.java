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

/** What a provider or a model can do; a caller asks before it relies on one. */
public enum Capability {
    CHAT,
    STREAMING,
    TOOL_CALLS,
    EMBEDDINGS,
    JSON_MODE;

    /** The name written in a provider file: {@code chat}, {@code tool-calls}, {@code json-mode}. */
    @com.fasterxml.jackson.annotation.JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The capability a provider file names. */
    public static Capability of(String id) {
        return valueOf(id.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}

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
package com.ash.messaging.pravaha.ai.llm;

import org.jspecify.annotations.Nullable;

/**
 * A provider's health: {@code ok}, {@code degraded} or {@code down} with a detail.
 *
 * @param status the word
 * @param detail why
 */
public record HealthStatus(String status, String detail) {

    public HealthStatus(@Nullable String status, @Nullable String detail) {
        this.status = status == null || status.isBlank() ? "ok" : status;
        this.detail = detail == null ? "" : detail;
    }

    /** Whether calls may be made. */
    public boolean ok() {
        return "ok".equals(status) || "degraded".equals(status);
    }
}

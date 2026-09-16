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
package com.ash.messaging.pravaha.common.row;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Row-layout error codes. Stable, documented, and never renumbered. */
public final class RowErrors {

    /**
     * A row needs more than 64 fields.
     *
     * <p>{@link BinaryRowWriter} tracks which fields have been written in a single {@code long}
     * bitmask, one bit per field, so it cannot represent more than {@link Long#SIZE} of them. The
     * ceiling is architectural rather than tunable, and it binds any row -- a wide projection, a wide
     * join, a wide aggregate all land here at the same 64 columns (X-11 part A). In the {@code 3xxx}
     * (runtime) range rather than {@code 2xxx} (SQL) because the ceiling is met when a row is
     * actually built, not when the query is planned: {@code pravaha validate} accepts a 1,000-column
     * projection without complaint, since nothing writes a row during validation.
     */
    public static final ErrorCode FIELD_LIMIT_EXCEEDED = new ErrorCode(3030, "ROW_FIELD_LIMIT_EXCEEDED");

    private RowErrors() {}
}

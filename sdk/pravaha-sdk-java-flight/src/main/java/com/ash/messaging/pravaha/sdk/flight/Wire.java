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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Reading positional fields off the control wire, defensively.
 *
 * <p>Every one of these treats a missing field as absent rather than as an error. The wire is
 * append-only by design -- a server adds a field at the end and an older client goes on reading the
 * ones it knows -- and that promise is only kept if the client reads past the end without
 * complaining. A malformed number is zero for the same reason: a status that cannot be rendered
 * because one counter arrived wrong is worse than a status with one wrong counter in it.
 */
final class Wire {

    private Wire() {}

    static String text(List<String> fields, int index) {
        return index < fields.size() && fields.get(index) != null ? fields.get(index) : "";
    }

    static long number(List<String> fields, int index) {
        try {
            return Long.parseLong(text(fields, index).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A number, or null when the field is empty -- "no watermark yet" is not "watermark zero". */
    static @Nullable Long optionalNumber(List<String> fields, int index) {
        String value = text(fields, index).strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A comma-separated field as a list, empty when the field is blank. */
    static List<String> list(List<String> fields, int index) {
        String value = text(fields, index).strip();
        return value.isEmpty() ? List.of() : List.of(value.split(","));
    }
}

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

/**
 * A whole-number query parameter that is either absent, and takes its default, or present and a
 * number.
 *
 * <p>API-F8's mechanism, closed for paging. Spring's {@code @RequestParam(defaultValue = ...)}
 * substitutes the default for an <em>empty</em> value as well as an absent one, so {@code ?limit=} --
 * most often a shell variable that did not expand -- silently meant fifty. Absent still means the
 * default: not asking is not the same as asking for nothing. Present and empty, or present and not a
 * number, is refused {@code PRV-0400} with the parameter's name.
 */
final class PageParameters {

    private PageParameters() {}

    static int intOrDefault(String name, String raw, int absent) {
        if (raw == null) {
            return absent;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, got "
                    + (raw.isEmpty() ? "an empty value" : "'" + raw + "'")
                    + ". An empty ?" + name + "= is this, not an absent one: write the number or leave the "
                    + "parameter out for " + absent + ".");
        }
    }
}

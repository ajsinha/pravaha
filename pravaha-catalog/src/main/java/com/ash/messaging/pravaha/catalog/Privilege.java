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
package com.ash.messaging.pravaha.catalog;

import java.util.Locale;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What a grant allows (ADR-059 §2). Allow-only: there is no deny to reason about, and access is the
 * union of what a principal's grants, roles and ownership give.
 */
public enum Privilege {
    /** May resolve names in a namespace. Necessary, not sufficient. */
    USE,
    /** May read an answer by point read or scan. */
    SELECT,
    /** May receive a view's changes live -- separate from reading it. */
    SUBSCRIBE,
    /** May register a query that reads it; the registrant needs this on every input. */
    BUILD_ON,
    /** May register queries in a namespace. */
    CREATE,
    /** May name a sink in {@code WRITING TO}. */
    WRITE,
    /** May pause, resume or replace a query. */
    MODIFY,
    /** May change an object's grants, tags and description, and drop it. */
    MANAGE,
    /** All of the above; exactly one owner, transferred with {@code OWNER TO} rather than granted. */
    OWN;

    /** {@code BUILD_ON}, or {@code BUILD ON} as two words, in any case. */
    public static Privilege parse(String text) {
        String normal = text.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            return Privilege.valueOf(normal);
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'" + text + "' is not a privilege; they are USE, SELECT, SUBSCRIBE, BUILD_ON, CREATE, WRITE, "
                            + "MODIFY, MANAGE and OWN (and ALL, for every one that applies)");
        }
    }
}

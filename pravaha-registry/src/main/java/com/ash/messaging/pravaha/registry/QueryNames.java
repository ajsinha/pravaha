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
package com.ash.messaging.pravaha.registry;

import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;

/** What a registration may be called, and why a name is refused before anything is started. */
final class QueryNames {

    private QueryNames() {}

    /** Refuses a name that cannot be used, or is already somebody else's. */
    static void require(String name, Set<String> taken) {
        // The null check first. requireSayable was added above it once, so a null name threw a bare
        // NullPointerException out of name.matches() instead of the message two lines down.
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a registration needs a name");
        }
        requireSayable(name);
        if (taken.contains(name)) {
            throw new PravahaException(
                    RegistryErrors.NAME_IN_USE,
                    "'" + name + "' is already registered. Drop it first, register under another name, or "
                            + "replace it with CREATE OR REPLACE CONTINUOUS QUERY -- silently replacing a "
                            + "running query would take its answers away from whoever is reading them");
        }
    }

    /**
     * Refuses a name no query will be able to say.
     *
     * <p>A view name is written in a FROM clause, so it has to survive the SQL parser. {@code
     * primary} does not: the registration is accepted, the server reports RUNNING, and every attempt
     * to read it fails with a parse error naming a column position, which reads like a broken query
     * rather than a name that was never usable. Refused at registration, where the person who chose
     * the name is still holding it.
     */
    static void requireSayable(String name) {
        // Unicode letters, not ASCII only. My first version refused a name like 金额 that the
        // planner resolves perfectly well -- a validation stricter than the thing it was protecting.
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) {
            throw new PravahaException(
                    RegistryErrors.NAME_UNUSABLE,
                    "'" + name + "' cannot be used as a view name: a name is written in a FROM clause, so it "
                            + "must be a plain identifier -- a letter or underscore, then letters, digits or "
                            + "underscores.");
        }
        try {
            // Calcite's own parser rather than a list of reserved words kept by hand here. The list
            // is long, it is version-specific, and a copy of it is wrong the first time Calcite
            // changes -- whereas the parser is the thing that will actually reject the name.
            org.apache.calcite.sql.parser.SqlParser.create("SELECT 1 FROM " + name)
                    .parseQuery();
        } catch (org.apache.calcite.sql.parser.SqlParseException | RuntimeException e) {
            // What the parser said, not a diagnosis of our own. This used to answer every parse
            // failure with "is a reserved word in SQL", which is the common cause and not the only
            // one: a 500-character name is refused by Calcite's lexer for its length and was told it
            // was a keyword -- false, and it sends the person who chose it looking for a list they
            // will not find themselves on.
            String reason =
                    e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            throw new PravahaException(
                    RegistryErrors.NAME_UNUSABLE,
                    "'" + name + "' cannot appear in a FROM clause, so no query could read the view: " + reason
                            + ". The usual cause is that the name is a reserved word in SQL.");
        }
    }
}

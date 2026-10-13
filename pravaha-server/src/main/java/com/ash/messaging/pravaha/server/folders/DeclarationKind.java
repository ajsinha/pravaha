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
package com.ash.messaging.pravaha.server.folders;

import java.util.Locale;

/**
 * What a declaration file declares: a named connection, a stream (with its source), a dimension table, or a
 * sink (ADR-064). In the order the folders are read, so a connection is there before a binding names it.
 */
public enum DeclarationKind {
    CONNECTOR("connectors"),
    STREAM("streams"),
    LOOKUP("lookups"),
    SINK("sinks");

    private final String plural;

    DeclarationKind(String plural) {
        this.plural = plural;
    }

    /** {@code connectors}, {@code streams}, {@code lookups}, {@code sinks}: the folder's last segment. */
    public String plural() {
        return plural;
    }

    /** The inline block the same object can be declared in: {@code pravaha.streams}, and so on. */
    public String inlineBlock() {
        return "pravaha." + plural;
    }

    /** {@code connector}, {@code stream}, {@code lookup}, {@code sink}: for messages and the API. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}

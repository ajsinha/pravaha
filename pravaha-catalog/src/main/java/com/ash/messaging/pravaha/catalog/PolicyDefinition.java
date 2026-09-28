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

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.ash.messaging.pravaha.security.Principal;

/**
 * What a policy object says (ADR-059 §4): a row filter's predicate, or a mask's column and expression,
 * and the roles it does not apply to. Where it applies is its bindings ({@link PolicyBinding}); who owns
 * it, its description, tags and version are its {@link CatalogObject}, kind {@code POLICY}.
 *
 * @param fullName {@code tenant.namespace.name}
 * @param type what it does
 * @param column the masked column's name; empty for a row filter
 * @param expression as written, already checked by {@link PolicyExpression}
 * @param exceptRoles principals holding any of these are not narrowed by it
 */
public record PolicyDefinition(String fullName, Type type, String column, String expression, Set<String> exceptRoles) {

    /** A row filter keeps rows; a mask replaces one column's value. */
    public enum Type {
        ROW_FILTER("ROW FILTER"),
        MASK("MASK");

        private final String words;

        Type(String words) {
            this.words = words;
        }

        /** As a statement writes it: {@code ROW FILTER}, {@code MASK}. */
        public String words() {
            return words;
        }

        /** {@code ROW_FILTER}, {@code ROW FILTER}, {@code filter}, {@code MASK}, any case. */
        public static Type parse(String text) {
            String canonical =
                    text == null ? "" : text.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
            return switch (canonical) {
                case "ROW_FILTER", "FILTER" -> ROW_FILTER;
                case "MASK", "COLUMN_MASK" -> MASK;
                default ->
                    throw new com.ash.messaging.pravaha.api.PravahaException(
                            CatalogErrors.INVALID_REQUEST,
                            "'" + text + "' is not a kind of policy; they are ROW_FILTER and MASK");
            };
        }
    }

    public PolicyDefinition {
        column = column == null ? "" : column;
        exceptRoles = Collections.unmodifiableSet(new TreeSet<>(exceptRoles == null ? Set.of() : exceptRoles));
    }

    /** The expression, parsed; checked again, so a journal written by hand cannot smuggle one past. */
    public PolicyExpression parsed() {
        return PolicyExpression.of(expression, type == Type.MASK ? column : null);
    }

    /** The {@code EXCEPT ROLE} this principal holds, if any: they are exempt. */
    public java.util.Optional<String> exemption(Principal principal) {
        return exceptRoles.stream().filter(principal::hasRole).findFirst();
    }

    /** The tenant it belongs to; its tag bindings reach only that tenant's objects. */
    public String tenant() {
        return CatalogNames.tenantOf(fullName);
    }
}

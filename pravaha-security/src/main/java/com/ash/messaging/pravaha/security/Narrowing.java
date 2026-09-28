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
package com.ash.messaging.pravaha.security;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a principal is shown of one object once policies have narrowed it (ADR-059 §4): the rows a
 * row filter keeps and the columns a mask replaces.
 *
 * <p>Everything here is already bound to the principal: {@code session_attribute('region')} has become
 * the principal's region as a literal, {@code is_member('x')} TRUE or FALSE, {@code current_user()} their
 * id. So the text is plain SQL over the object's own columns, the planner checks it like any other
 * predicate, and two principals whose narrowing differs have different text -- which is what keeps them
 * from sharing a computation by fingerprint (ADR-025).
 *
 * @param rowFilter the conjunction of every row filter that applies, or empty; several filters on one
 *     object AND together
 * @param masks column name to the expression that replaces it, in column-name order
 * @param because one line per policy that applies, and how it came to -- for {@code SHOW EFFECTIVE
 *     ACCESS} and the audit trail; not part of what is enforced
 */
public record Narrowing(Optional<String> rowFilter, Map<String, String> masks, List<String> because) {

    /** Nothing narrowed: every row, every value. */
    public static final Narrowing NONE = new Narrowing(Optional.empty(), Map.of(), List.of());

    public Narrowing {
        rowFilter = rowFilter == null ? Optional.empty() : rowFilter.filter(f -> !f.isBlank());
        masks = Collections.unmodifiableMap(new TreeMap<>(masks == null ? Map.of() : masks));
        because = List.copyOf(because == null ? List.of() : because);
    }

    /** Whether nothing is narrowed. */
    public boolean isNone() {
        return rowFilter.isEmpty() && masks.isEmpty();
    }

    /** Whether {@code column} is masked (compared ignoring case, as SQL names are). */
    public boolean masks(String column) {
        return maskOf(column).isPresent();
    }

    /** The mask on {@code column}, ignoring case, if there is one. */
    public Optional<String> maskOf(String column) {
        for (Map.Entry<String, String> mask : masks.entrySet()) {
            if (mask.getKey().equalsIgnoreCase(column)) {
                return Optional.of(mask.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * What is enforced, as one stable string: equal for two principals exactly when they would be shown
     * the same rows and values. What ADR-025's fingerprint takes, and what an open subscription compares
     * to see whether its policy changed.
     */
    public String fingerprint() {
        if (isNone()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        rowFilter.ifPresent(f -> text.append("filter:").append(f));
        masks.forEach((column, mask) ->
                text.append("|mask:").append(column).append('=').append(mask));
        return text.toString();
    }

    /** Whether this enforces the same thing as {@code other}; {@link #because} is not compared. */
    public boolean enforcesSameAs(Narrowing other) {
        return other != null && fingerprint().equals(other.fingerprint());
    }
}

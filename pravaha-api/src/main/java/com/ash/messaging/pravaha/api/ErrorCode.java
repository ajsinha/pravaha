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
package com.ash.messaging.pravaha.api;

/**
 * A stable, documented failure identifier of the form {@code PRV-nnnn}.
 *
 * <p>Codes are grouped by {@link Category} so that the number alone tells an operator which
 * subsystem failed. They are never reused and never renumbered: an operator searching for
 * {@code PRV-2041} two years from now must find the same page.
 */
public record ErrorCode(int number, String name) {

    private static final String DOCS_BASE = "https://docs.pravaha.io/errors/";

    public ErrorCode {
        if (number < 1000 || number > 9999) {
            throw new IllegalArgumentException("error code must be a four-digit number, got " + number);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("error code must have a name");
        }
    }

    /** Numeric ranges, one per subsystem. */
    public enum Category {
        CONFIGURATION(1000, 1999),
        PLANNING(2000, 2999),
        RUNTIME(3000, 3999),
        STATE(4000, 4999),
        PLUGIN(5000, 5999),
        FLIGHT(6000, 6999),
        SECURITY(7000, 7999),
        REGISTRY(8000, 8999),
        CLUSTER(9000, 9999);

        private final int lo;
        private final int hi;

        Category(int lo, int hi) {
            this.lo = lo;
            this.hi = hi;
        }

        public boolean contains(int number) {
            return number >= lo && number <= hi;
        }
    }

    /** The rendered form, e.g. {@code PRV-2041}. */
    public String code() {
        return "PRV-" + number;
    }

    public Category category() {
        for (Category c : Category.values()) {
            if (c.contains(number)) {
                return c;
            }
        }
        throw new IllegalStateException("no category for " + code());
    }

    public String helpUrl() {
        return DOCS_BASE + code();
    }

    @Override
    public String toString() {
        return code() + " (" + name + ")";
    }
}

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
package com.ash.messaging.pravaha.sql.plan;

import com.ash.messaging.pravaha.api.data.RowView;

/**
 * A boolean test over a row.
 *
 * <p>Interpreted for now. Wave 3 replaces this with generated code that compares UTF-8 bytes
 * directly and never materialises a {@code String} (design section 12.3); this interface is what the
 * interpreted fallback keeps implementing, and what the differential tests compare generated code
 * against. Correctness never depends on code generation succeeding.
 */
public interface Predicate {

    boolean test(RowView row);

    /** How this reads in EXPLAIN output. */
    String describe();

    /** Always true. Used when an optimiser rule removes the last conjunct. */
    Predicate ALWAYS_TRUE = new Predicate() {
        @Override
        public boolean test(RowView row) {
            return true;
        }

        @Override
        public String describe() {
            return "true";
        }
    };

    /** Conjunction, which is what a residual filter after partial pushdown reduces to. */
    static Predicate and(Predicate left, Predicate right) {
        if (left == ALWAYS_TRUE) {
            return right;
        }
        if (right == ALWAYS_TRUE) {
            return left;
        }
        return new Predicate() {
            @Override
            public boolean test(RowView row) {
                return left.test(row) && right.test(row);
            }

            @Override
            public String describe() {
                return left.describe() + " AND " + right.describe();
            }
        };
    }
}

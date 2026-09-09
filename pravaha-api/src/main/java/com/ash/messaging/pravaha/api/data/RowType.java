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
package com.ash.messaging.pravaha.api.data;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Nested structure. Field names are unique and order is significant, because it fixes the layout. */
public record RowType(List<Field> fields, boolean nullable) implements PravahaType {

    public RowType {
        Objects.requireNonNull(fields, "fields");
        fields = List.copyOf(fields);
        Set<String> seen = new LinkedHashSet<>();
        for (Field f : fields) {
            if (!seen.add(f.name())) {
                throw new IllegalArgumentException("duplicate field name in ROW: " + f.name());
            }
        }
    }

    @Override
    public TypeName typeName() {
        return TypeName.ROW;
    }

    @Override
    public String sqlName() {
        StringBuilder sb = new StringBuilder("ROW<");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fields.get(i).name())
                    .append(' ')
                    .append(fields.get(i).type().sqlName());
        }
        return sb.append('>').append(nullable ? "" : " NOT NULL").toString();
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new RowType(fields, value);
    }
}

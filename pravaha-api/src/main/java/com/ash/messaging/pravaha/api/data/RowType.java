/*
 * Copyright the Pravaha authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

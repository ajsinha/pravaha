/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The shape of a stream or table: an ordered, immutable list of fields plus the metadata the engine
 * needs to bind it -- which field carries event time, and what identifies a row.
 *
 * <p>Schemas are versioned and immutable. A running query keeps the version it was planned against,
 * so a source-side schema change can never silently alter a query's meaning (design section 11.4).
 */
public final class StreamSchema {

    private final String name;
    private final List<Field> fields;
    private final Map<String, Field> byName;
    private final int version;
    private final int eventTimeOrdinal;
    private final List<String> primaryKey;

    private StreamSchema(String name, List<Field> fields, int version, int eventTimeOrdinal, List<String> primaryKey) {
        this.name = name;
        this.fields = fields;
        this.version = version;
        this.eventTimeOrdinal = eventTimeOrdinal;
        this.primaryKey = primaryKey;
        Map<String, Field> index = new LinkedHashMap<>(fields.size() * 2);
        for (Field f : fields) {
            index.put(f.name(), f);
        }
        this.byName = Map.copyOf(index);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** Fields in layout order. Ordinals are assigned by position and are contiguous from zero. */
    public List<Field> fields() {
        return fields;
    }

    public int fieldCount() {
        return fields.size();
    }

    public int version() {
        return version;
    }

    /** Ordinal of the event-time field, or empty when the stream has no declared event time. */
    public OptionalInt eventTimeOrdinal() {
        return eventTimeOrdinal < 0 ? OptionalInt.empty() : OptionalInt.of(eventTimeOrdinal);
    }

    /** Declared primary key field names, empty when the stream has none. */
    public List<String> primaryKey() {
        return primaryKey;
    }

    public Field field(int ordinal) {
        return fields.get(ordinal);
    }

    /**
     * Ordinal of the named field.
     *
     * @throws IllegalArgumentException if no such field exists -- callers on the hot path resolve
     *     ordinals once at registration, so failing loudly here is correct
     */
    public int indexOf(String fieldName) {
        Field f = byName.get(fieldName);
        if (f == null) {
            throw new IllegalArgumentException(
                    "no field '" + fieldName + "' in schema '" + name + "'; have " + byName.keySet());
        }
        return f.ordinal();
    }

    public boolean hasField(String fieldName) {
        return byName.containsKey(fieldName);
    }

    /** This schema with the version incremented and the given fields. Used by catalog evolution. */
    public StreamSchema evolve(List<Field> newFields) {
        Builder b = new Builder(name).version(version + 1);
        newFields.forEach(f -> b.field(f.name(), f.type()));
        if (eventTimeOrdinal >= 0 && eventTimeOrdinal < newFields.size()) {
            b.eventTime(newFields.get(eventTimeOrdinal).name());
        }
        primaryKey.forEach(b::primaryKeyField);
        return b.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StreamSchema other)) {
            return false;
        }
        return version == other.version
                && eventTimeOrdinal == other.eventTimeOrdinal
                && name.equals(other.name)
                && fields.equals(other.fields)
                && primaryKey.equals(other.primaryKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, fields, version, eventTimeOrdinal, primaryKey);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append(" v").append(version).append(" (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fields.get(i).name())
                    .append(' ')
                    .append(fields.get(i).type().sqlName());
        }
        return sb.append(')').toString();
    }

    /** Assigns ordinals by insertion order, so the caller never has to keep them consistent. */
    public static final class Builder {
        private final String name;
        private final List<Field> fields = new ArrayList<>();
        private final List<String> primaryKey = new ArrayList<>();
        private int version = 1;
        private String eventTimeField;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("schema name must not be blank");
            }
        }

        public Builder field(String fieldName, PravahaType type) {
            fields.add(new Field(fieldName, type, fields.size()));
            return this;
        }

        public Builder version(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("version must be >= 1, got " + value);
            }
            this.version = value;
            return this;
        }

        public Builder eventTime(String fieldName) {
            this.eventTimeField = fieldName;
            return this;
        }

        public Builder primaryKeyField(String fieldName) {
            primaryKey.add(fieldName);
            return this;
        }

        public StreamSchema build() {
            List<Field> frozen = List.copyOf(fields);
            int etOrdinal = -1;
            if (eventTimeField != null) {
                etOrdinal = ordinalOf(frozen, eventTimeField, "event-time field");
                PravahaType t = frozen.get(etOrdinal).type();
                if (t.typeName() != TypeName.TIMESTAMP_LTZ) {
                    throw new IllegalArgumentException(
                            "event-time field '" + eventTimeField + "' must be TIMESTAMP, got " + t.sqlName());
                }
            }
            for (String pk : primaryKey) {
                ordinalOf(frozen, pk, "primary-key field");
            }
            return new StreamSchema(name, frozen, version, etOrdinal, List.copyOf(primaryKey));
        }

        private static int ordinalOf(List<Field> fs, String fieldName, String what) {
            for (Field f : fs) {
                if (f.name().equals(fieldName)) {
                    return f.ordinal();
                }
            }
            throw new IllegalArgumentException("unknown " + what + ": '" + fieldName + "'");
        }
    }
}

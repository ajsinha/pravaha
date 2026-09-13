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

import java.time.Duration;
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
    private final Duration outOfOrderness;
    private final List<String> primaryKey;

    /**
     * How late this stream's rows arrive, at worst.
     *
     * <p>The default when a stream does not say. Ten seconds is generous for a well-behaved source
     * and mean for a bad one, which is the right way round for a default: too small silently drops
     * data as late, too large only costs memory, and the second failure is visible while the first
     * is not.
     *
     * <p>A deployment moves this with {@code pravaha.watermark.out-of-orderness}; a stream that
     * knows its own source overrides both with {@link Builder#outOfOrderness}.
     */
    public static final Duration DEFAULT_OUT_OF_ORDERNESS = Duration.ofSeconds(10);

    private StreamSchema(
            String name,
            List<Field> fields,
            int version,
            int eventTimeOrdinal,
            List<String> primaryKey,
            Duration outOfOrderness) {
        this.outOfOrderness = outOfOrderness == null ? DEFAULT_OUT_OF_ORDERNESS : outOfOrderness;
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
    /**
     * How far behind the highest event time seen this stream's watermark should sit.
     *
     * <p>Declared on the stream because lateness is a property of the source, not of the query. A
     * topic fed by mobile clients over a flaky network and a table scan of data already at rest have
     * nothing in common here, and a single engine-wide number has to be wrong for one of them.
     *
     * <p>This is <em>out-of-orderness</em>, not allowed lateness. It decides how long the engine
     * waits before declaring a window complete. It does not decide what happens to a row that turns
     * up after that: such a row is still applied, as a retraction and a correction, which is what
     * Z-set weights are for.
     */
    public Duration outOfOrderness() {
        return outOfOrderness;
    }

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

    /**
     * The same schema under a different name, at the same version.
     *
     * <p>For a view served under more than one name. Two registrations of one question share a
     * computation and a copy of the state, and each name must be plannable -- the SQL planner keys
     * tables by the schema's own name, so handing it one schema twice makes the second name
     * invisible and {@code SELECT ... FROM second_name} answers "Object not found" from a name the
     * server has just acknowledged as RUNNING.
     */
    public StreamSchema renamedTo(String newName) {
        if (name.equals(newName)) {
            return this;
        }
        Builder b = new Builder(newName).version(version);
        fields.forEach(f -> b.field(f.name(), f.type()));
        if (eventTimeOrdinal >= 0) {
            b.eventTime(fields.get(eventTimeOrdinal).name());
            b.outOfOrderness(outOfOrderness);
        }
        primaryKey.forEach(b::primaryKeyField);
        return b.build();
    }

    /** This schema with the version incremented and the given fields. Used by catalog evolution. */
    public StreamSchema evolve(List<Field> newFields) {
        Builder b = new Builder(name).version(version + 1);
        newFields.forEach(f -> b.field(f.name(), f.type()));
        if (eventTimeOrdinal >= 0 && eventTimeOrdinal < newFields.size()) {
            b.eventTime(newFields.get(eventTimeOrdinal).name());
            b.outOfOrderness(outOfOrderness);
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
                && outOfOrderness.equals(other.outOfOrderness)
                && name.equals(other.name)
                && fields.equals(other.fields)
                && primaryKey.equals(other.primaryKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, fields, version, eventTimeOrdinal, primaryKey, outOfOrderness);
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
        private Duration outOfOrderness;

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

        /**
         * How late this stream's rows may be before the engine stops waiting for them.
         *
         * <p>Set it beside {@code eventTime}, because the two answer one question together: which
         * column carries time, and how much it can be trusted to be in order.
         */
        public Builder outOfOrderness(Duration lateness) {
            if (lateness == null || lateness.isNegative()) {
                throw new IllegalArgumentException("out-of-orderness must not be negative, got " + lateness
                        + ". Zero means the source is strictly ordered, which is a claim the "
                        + "engine will hold you to: a row behind the watermark arrives late.");
            }
            this.outOfOrderness = lateness;
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
            return new StreamSchema(name, frozen, version, etOrdinal, List.copyOf(primaryKey), outOfOrderness);
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

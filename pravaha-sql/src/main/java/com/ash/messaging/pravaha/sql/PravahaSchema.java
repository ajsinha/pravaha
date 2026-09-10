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
package com.ash.messaging.pravaha.sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * The catalog Calcite resolves names against.
 *
 * <p>Per-planner rather than global, so two queries can be planned against different schema versions
 * concurrently. That is not a nicety: a running query keeps the schema version it was planned with
 * (design section 11.4), so registering a new version must not disturb a query already planned against
 * the old one.
 */
public final class PravahaSchema extends AbstractSchema {

    private final Map<String, Table> tables = new LinkedHashMap<>();

    /** Registers a stream. Returns {@code this} so registration can be chained. */
    public PravahaSchema register(StreamSchema schema) {
        tables.put(schema.name(), new PravahaTable(schema));
        return this;
    }

    /**
     * Registers a dimension table: something to look rows up in rather than consume.
     *
     * <p>The difference is not cosmetic. A stream is read from beginning to end and its rows are
     * kept in join state; a lookup table is asked one key at a time and keeps nothing. Registering
     * a hundred-million-row customer table as a stream is how a query runs out of memory, and the
     * two are told apart here, at registration, rather than guessed at from the query.
     */
    public PravahaSchema registerLookup(StreamSchema schema) {
        tables.put(schema.name(), new PravahaTable(schema, true));
        return this;
    }

    public boolean contains(String name) {
        return tables.containsKey(name);
    }

    public Set<String> streamNames() {
        return tables.keySet();
    }

    @Override
    protected Map<String, Table> getTableMap() {
        return tables;
    }
}

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
package com.ash.messaging.pravaha.server.catalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * The streams this node knows about.
 *
 * <p>In-memory for now; the Raft-backed catalog arrives with the cluster in Wave 8. What matters
 * already is that a registered schema is <strong>immutable</strong> and versioned: a running query
 * keeps the version it was planned against, so registering a new version cannot change the meaning
 * of a query already in flight (design section 11.4).
 */
@Component
public class StreamCatalog {

    private final Map<String, StreamSchema> streams = new LinkedHashMap<>();

    private final Map<String, StreamSchema> lookups = new LinkedHashMap<>();

    public synchronized StreamSchema register(StreamSchema schema) {
        StreamSchema existing = streams.get(schema.name());
        if (existing != null && existing.version() == schema.version()) {
            throw new PravahaException(
                    SqlErrors.VALIDATION_FAILED,
                    "stream '" + schema.name() + "' version " + schema.version()
                            + " is already registered. Schema versions are immutable; register a new "
                            + "version rather than replacing one a query may be planned against.");
        }
        streams.put(schema.name(), schema);
        return schema;
    }

    /**
     * Records a dimension table, so SQL that joins one can be validated and explained.
     *
     * <p>Kept apart from the streams: the planner treats the two differently, and a dimension
     * registered as a stream plans a lookup join as a stream-to-stream join that waits forever. The
     * REST surface used to have no way to say which a schema was, so `POST /queries/validate` on a
     * lookup query said the table did not exist -- for a query the registry would accept.
     */
    public synchronized StreamSchema registerLookup(StreamSchema schema) {
        lookups.put(schema.name(), schema);
        return schema;
    }

    /** The dimension tables, which are not streams and must not be planned as ones. */
    public synchronized Collection<StreamSchema> lookups() {
        return List.copyOf(lookups.values());
    }

    public synchronized Optional<StreamSchema> find(String name) {
        return Optional.ofNullable(streams.get(name));
    }

    public synchronized StreamSchema require(String name) {
        return find(name)
                .orElseThrow(() -> new PravahaException(
                        SqlErrors.UNKNOWN_STREAM, "no stream named '" + name + "'. Registered: " + streams.keySet()));
    }

    public synchronized Collection<StreamSchema> all() {
        return java.util.List.copyOf(streams.values());
    }

    public synchronized boolean contains(String name) {
        return streams.containsKey(name);
    }

    public synchronized int size() {
        return streams.size();
    }
}

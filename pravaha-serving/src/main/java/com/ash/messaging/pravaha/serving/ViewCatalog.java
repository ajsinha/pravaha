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
package com.ash.messaging.pravaha.serving;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * The views a client may query, by name.
 *
 * <p>Small on purpose. It exists so that a request arriving over the wire can be answered without
 * the transport knowing anything about lanes, arenas or plans -- it asks for a view by the name the
 * user registered, and gets something with a schema and rows.
 *
 * <p>Concurrent, because reads arrive on transport threads while registration happens on whichever
 * thread started the query. The map is copy-on-write in effect: registrations are rare and reads are
 * constant, which is exactly the shape a concurrent hash map is good at and a lock is not.
 */
public final class ViewCatalog {

    private final Map<String, ServedView> views = new java.util.concurrent.ConcurrentHashMap<>();

    /** Publishes a view under its own name. */
    public ViewCatalog register(ServedView view) {
        views.put(view.name(), view);
        return this;
    }

    public Optional<ServedView> find(String name) {
        return Optional.ofNullable(views.get(name));
    }

    public Set<String> names() {
        return Set.copyOf(views.keySet());
    }

    /** Every view's schema, for a client browsing the catalogue. */
    public Map<String, StreamSchema> schemas() {
        Map<String, StreamSchema> schemas = new LinkedHashMap<>();
        views.forEach((name, view) -> schemas.put(name, view.schema()));
        return schemas;
    }

    public boolean isEmpty() {
        return views.isEmpty();
    }
}

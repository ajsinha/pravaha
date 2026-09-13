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

    /**
     * Bumped whenever the set of views or their schemas changes.
     *
     * <p>Exists so that anything caching a plan can tell, in one comparison, whether the catalogue
     * it planned against is still the catalogue it would plan against now. A plan built when a view
     * had three columns must not be reused after the view is re-registered with four.
     */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    /** Publishes a view under its own name. */
    /** The catalogue's version. A cached plan is valid only while this is unchanged. */
    public long generation() {
        return generation.get();
    }

    public ViewCatalog register(ServedView view) {
        return registerAs(view.name(), view);
    }

    /**
     * Registers one view under an additional name.
     *
     * <p>Two registrations of the same question share one computation and one copy of the state, and
     * the second name has to answer to the same view -- otherwise sharing, which is the feature,
     * quietly makes the second name unusable: {@code register} returned RUNNING and {@code SELECT
     * ... FROM second_name} answered "Object not found".
     */
    public ViewCatalog registerAs(String name, ServedView view) {
        views.put(name, view);
        generation.incrementAndGet();
        return this;
    }

    /**
     * Forgets a name.
     *
     * <p>A dropped query's view must stop answering. Leaving it in place serves whatever the closed
     * computation last committed, for ever, to a caller who has no way to tell that nothing is
     * maintaining it any more.
     */
    public ViewCatalog remove(String name) {
        views.remove(name);
        generation.incrementAndGet();
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
        // Renamed to the key it is registered under, not the name the schema was built with. The
        // planner keys tables by the schema's own name, so a view served under a second name handed
        // over its original schema and the alias was simply invisible: registration acknowledged
        // RUNNING and the read answered "Object not found. Known streams: [the first name]".
        views.forEach((name, view) -> schemas.put(name, view.schema().renamedTo(name)));
        return schemas;
    }

    public boolean isEmpty() {
        return views.isEmpty();
    }
}

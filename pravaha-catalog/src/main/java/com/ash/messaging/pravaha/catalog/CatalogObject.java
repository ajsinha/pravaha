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
package com.ash.messaging.pravaha.catalog;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One governed object and what the catalogue records about it (ADR-059 §1).
 *
 * @param fullName {@code tenant.namespace.object}; for a namespace {@code tenant.namespace}; for a
 *     tenant's root {@code tenant}; for the whole catalogue {@code *}
 * @param kind what it is
 * @param engineName the name the engine knows it by -- a view's registered name, a stream's, a sink
 *     binding's -- or empty for a namespace
 * @param owner exactly one: a user or a role
 * @param tags {@code key} (with an empty value) or {@code key=value}, in the order they were set
 * @param version increments on every change to the object
 */
public record CatalogObject(
        String fullName,
        ObjectKind kind,
        String engineName,
        Grantee owner,
        String description,
        Map<String, String> tags,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        long version) {

    public CatalogObject {
        description = description == null ? "" : description;
        engineName = engineName == null ? "" : engineName;
        tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags == null ? Map.of() : tags));
    }

    /** The tenant the object belongs to: the first part of its name, or {@code *} for the root. */
    public String tenant() {
        return CatalogNames.tenantOf(fullName);
    }

    /** The level holding it: an object's namespace, a namespace's tenant. */
    public String parent() {
        return CatalogNames.parentOf(fullName);
    }

    /** The last part of the name. */
    public String shortName() {
        return CatalogNames.lastPart(fullName);
    }

    /** A copy changed by {@code by} at {@code at}, one version on. */
    CatalogObject changed(Grantee newOwner, String newDescription, Map<String, String> newTags, String by, Instant at) {
        return new CatalogObject(
                fullName,
                kind,
                engineName,
                newOwner,
                newDescription,
                newTags,
                createdAt,
                createdBy,
                at,
                by,
                version + 1);
    }

    /** A copy under another full name, as a move into a namespace makes. */
    CatalogObject movedTo(String newFullName, String by, Instant at) {
        return new CatalogObject(
                newFullName, kind, engineName, owner, description, tags, createdAt, createdBy, at, by, version + 1);
    }
}

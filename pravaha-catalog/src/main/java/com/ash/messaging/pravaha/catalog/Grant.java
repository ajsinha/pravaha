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

/**
 * One allow: {@code privilege} on {@code object} to {@code grantee}.
 *
 * @param object the object's full name ({@code tenant.namespace.object}, a namespace, a tenant, or
 *     {@code *} for the whole catalogue)
 * @param grantedBy who granted it, for {@code SHOW GRANTS} and the audit
 */
public record Grant(String object, Privilege privilege, Grantee grantee, String grantedBy, Instant grantedAt) {

    /** Whether this is the same allow as {@code other}, whoever granted it and when. */
    public boolean sameAllow(Grant other) {
        return object.equals(other.object) && privilege == other.privilege && grantee.equals(other.grantee);
    }

    /** {@code SELECT on acme.sales to ROLE analyst}, for an explanation. */
    public String describe() {
        return privilege + " on " + object + " to " + grantee;
    }
}

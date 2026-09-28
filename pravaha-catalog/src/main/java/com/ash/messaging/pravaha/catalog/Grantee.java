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

import java.util.Locale;
import java.util.Objects;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

/**
 * Who a grant is to, or who owns an object: a role or a user.
 *
 * <p>Two roles are held implicitly: {@value #PUBLIC}, by every caller including an anonymous one, and
 * {@value #AUTHENTICATED}, by every caller that proved who it is. They exist so that a deployment's
 * {@code permissive} or {@code authenticated} policy can be imported as grants and mean exactly what
 * it meant before.
 *
 * @param role true for a role, false for a user
 * @param name the role's or the user's name
 */
public record Grantee(boolean role, String name) {

    /** Held by every caller, anonymous or not. */
    public static final String PUBLIC = "public";

    /** Held by every caller that is not anonymous. */
    public static final String AUTHENTICATED = "authenticated";

    public Grantee {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.chars().anyMatch(c -> c == ':' || Character.isISOControl(c))) {
            throw new PravahaException(CatalogErrors.INVALID_REQUEST, "'" + name + "' cannot name a role or a user");
        }
    }

    public static Grantee role(String name) {
        return new Grantee(true, name == null ? "" : name.strip());
    }

    public static Grantee user(String name) {
        return new Grantee(false, name == null ? "" : name.strip());
    }

    /** {@code ROLE analyst} or {@code USER ana}, as a statement or a request writes it. */
    public static Grantee parse(String kind, String name) {
        return switch (kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT)) {
            case "ROLE" -> role(name);
            case "USER" -> user(name);
            default ->
                throw new PravahaException(
                        CatalogErrors.INVALID_REQUEST, "a grantee is ROLE <name> or USER <name>, not '" + kind + "'");
        };
    }

    /** Whether {@code principal} is this user or holds this role. */
    public boolean covers(Principal principal) {
        if (!role) {
            return !principal.isAnonymous() && principal.id().equals(name);
        }
        if (PUBLIC.equals(name)) {
            return true;
        }
        if (AUTHENTICATED.equals(name)) {
            return !principal.isAnonymous();
        }
        return principal.hasRole(name);
    }

    /** {@code ROLE:analyst} or {@code USER:ana}, the journal's spelling. */
    public String encode() {
        return (role ? "ROLE:" : "USER:") + name;
    }

    public static Grantee decode(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) {
            return user(text);
        }
        return parse(text.substring(0, colon), text.substring(colon + 1));
    }

    /** {@code ROLE} or {@code USER}. */
    public String type() {
        return role ? "ROLE" : "USER";
    }

    @Override
    public String toString() {
        return type() + " " + name;
    }
}

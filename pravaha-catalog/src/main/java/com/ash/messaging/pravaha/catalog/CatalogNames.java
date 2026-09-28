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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Three-part names, {@code tenant.namespace.object}, and the chain a grant is inherited down.
 *
 * <p>The hierarchy is four levels deep: the catalogue itself ({@value #ROOT}), a tenant
 * ({@code acme}), a namespace ({@code acme.sales}) and an object ({@code acme.sales.revenue}). A grant
 * at any level applies to everything under it. A view registered today is named by one identifier,
 * unique on the node (ADR-050), and lands in {@code <tenant>.default}, so every statement written
 * before the catalogue keeps meaning what it meant.
 *
 * <p>What the node is configured with -- streams, source and sink bindings, lookups -- belongs to no
 * tenant. It lives under the pseudo-tenant {@value #NODE} ({@code node.streams.orders},
 * {@code node.sinks.audit_out}), which is not a wall: ADR-050 left reads, sources and sinks unscoped by
 * tenant, and a grant there reaches a principal of any tenant.
 */
public final class CatalogNames {

    /** The whole catalogue: a grant here reaches every object of every tenant. */
    public static final String ROOT = "*";

    /** The pseudo-tenant holding what the node is configured with. */
    public static final String NODE = "node";

    /** The namespace an unqualified name resolves to, in the caller's tenant. */
    public static final String DEFAULT_NAMESPACE = "default";

    private CatalogNames() {}

    /** Refuses a part a full name could not carry unambiguously. */
    public static String part(String text, String what) {
        String part = text == null ? "" : text.strip();
        if (part.isEmpty()
                || part.equals(ROOT)
                || part.chars()
                        .anyMatch(c -> c == '.'
                                || c == ':'
                                || c == '"'
                                || c == '\''
                                || Character.isWhitespace(c)
                                || Character.isISOControl(c))
                || part.length() > 128) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'" + text + "' cannot be " + what + ": a name part is 1 to 128 characters with no dot, colon, "
                            + "quote or whitespace");
        }
        return part;
    }

    /** {@code tenant.namespace}. */
    public static String namespace(String tenant, String namespace) {
        return part(tenant, "a tenant") + "." + part(namespace, "a namespace");
    }

    /** {@code tenant.namespace.object}. */
    public static String object(String namespace, String name) {
        return namespace + "." + part(name, "an object's name");
    }

    /** How many parts: 0 for the root, 1 for a tenant, 2 for a namespace, 3 for an object. */
    public static int depth(String fullName) {
        if (ROOT.equals(fullName)) {
            return 0;
        }
        return fullName.split("\\.", -1).length;
    }

    /** The tenant a name is in, or {@value #ROOT} for the root itself. */
    public static String tenantOf(String fullName) {
        if (ROOT.equals(fullName)) {
            return ROOT;
        }
        int dot = fullName.indexOf('.');
        return dot < 0 ? fullName : fullName.substring(0, dot);
    }

    /** The level above: an object's namespace, a namespace's tenant, a tenant's root; null above the root. */
    public static String parentOf(String fullName) {
        if (ROOT.equals(fullName)) {
            return null;
        }
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? ROOT : fullName.substring(0, dot);
    }

    /** The last part of a name. */
    public static String lastPart(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }

    /** The name and every level above it, nearest first, ending at the root. */
    public static List<String> chain(String fullName) {
        List<String> chain = new ArrayList<>();
        for (String level = fullName; level != null; level = parentOf(level)) {
            chain.add(level);
        }
        return chain;
    }

    /** Where the node's configured objects of {@code kind} live: {@code node.streams}, {@code node.sinks}. */
    public static String infrastructureNamespace(ObjectKind kind) {
        return switch (kind) {
            case STREAM -> NODE + ".streams";
            case SOURCE -> NODE + ".sources";
            case SINK -> NODE + ".sinks";
            case LOOKUP -> NODE + ".lookups";
            default -> throw new IllegalArgumentException(kind + " is not configured on the node");
        };
    }

    /** Whether {@code kind} is something the node is configured with rather than something a person registers. */
    public static boolean isInfrastructure(ObjectKind kind) {
        return kind == ObjectKind.STREAM
                || kind == ObjectKind.SOURCE
                || kind == ObjectKind.SINK
                || kind == ObjectKind.LOOKUP;
    }

    /** A tenant's default namespace. */
    public static String defaultNamespaceOf(String tenant) {
        return namespace(tenant, DEFAULT_NAMESPACE);
    }
}

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A statement that reads or changes the catalogue (ADR-059 §3), as {@link CatalogStatements} parsed
 * it. Nothing here is resolved: a {@link Target} is the words written, and {@link CatalogService}
 * decides what they name for the caller who wrote them.
 */
public sealed interface CatalogStatement
        permits CatalogStatement.CreateNamespace,
                CatalogStatement.GrantPrivileges,
                CatalogStatement.RevokePrivileges,
                CatalogStatement.Comment,
                CatalogStatement.SetTags,
                CatalogStatement.UnsetTags,
                CatalogStatement.SetOwner,
                CatalogStatement.SetNamespace,
                CatalogStatement.ShowGrantsOn,
                CatalogStatement.ShowGrantsTo,
                CatalogStatement.ShowEffectiveAccess,
                CatalogStatement.ShowNamespaces {

    /** The statement's leading words, for an audit record or a message. */
    String verb();

    /**
     * What a statement names.
     *
     * @param kind {@code CATALOG}, {@code TENANT}, {@code NAMESPACE}, {@code VIEW} (also written {@code
     *     QUERY}), {@code STREAM}, {@code SOURCE}, {@code SINK} or {@code LOOKUP}
     * @param parts the dotted name as written; empty for {@code CATALOG}
     */
    record Target(String kind, List<String> parts) {
        public Target {
            parts = List.copyOf(parts);
        }

        /** The name as written, for a message. */
        public String written() {
            return kind + (parts.isEmpty() ? "" : " " + String.join(".", parts));
        }
    }

    /** {@code CREATE NAMESPACE [IF NOT EXISTS] name [COMMENT 'text']}. */
    record CreateNamespace(List<String> parts, boolean ifNotExists, Optional<String> comment)
            implements CatalogStatement {
        @Override
        public String verb() {
            return "CREATE NAMESPACE";
        }
    }

    /** {@code GRANT privilege, ... ON target TO ROLE|USER name}; no privileges means {@code ALL}. */
    record GrantPrivileges(Set<Privilege> privileges, Target target, Grantee grantee) implements CatalogStatement {
        @Override
        public String verb() {
            return "GRANT";
        }
    }

    /** {@code REVOKE privilege, ... ON target FROM ROLE|USER name}; no privileges means {@code ALL}. */
    record RevokePrivileges(Set<Privilege> privileges, Target target, Grantee grantee) implements CatalogStatement {
        @Override
        public String verb() {
            return "REVOKE";
        }
    }

    /** {@code COMMENT ON target IS 'text' | NULL}. */
    record Comment(Target target, Optional<String> text) implements CatalogStatement {
        @Override
        public String verb() {
            return "COMMENT ON";
        }
    }

    /** {@code ALTER target SET TAGS ('key' [= 'value'], ...)}. */
    record SetTags(Target target, Map<String, String> tags) implements CatalogStatement {
        @Override
        public String verb() {
            return "ALTER " + target.kind() + " SET TAGS";
        }
    }

    /** {@code ALTER target UNSET TAGS ('key', ...)}. */
    record UnsetTags(Target target, List<String> keys) implements CatalogStatement {
        @Override
        public String verb() {
            return "ALTER " + target.kind() + " UNSET TAGS";
        }
    }

    /** {@code ALTER target OWNER TO ROLE|USER name}. */
    record SetOwner(Target target, Grantee owner) implements CatalogStatement {
        @Override
        public String verb() {
            return "ALTER " + target.kind() + " OWNER TO";
        }
    }

    /** {@code ALTER VIEW name SET NAMESPACE namespace}: moves a view, with its grants. */
    record SetNamespace(Target target, List<String> namespace) implements CatalogStatement {
        @Override
        public String verb() {
            return "ALTER " + target.kind() + " SET NAMESPACE";
        }
    }

    /** {@code SHOW GRANTS ON target}. */
    record ShowGrantsOn(Target target) implements CatalogStatement {
        @Override
        public String verb() {
            return "SHOW GRANTS ON";
        }
    }

    /** {@code SHOW GRANTS TO ROLE|USER name}. */
    record ShowGrantsTo(Grantee grantee) implements CatalogStatement {
        @Override
        public String verb() {
            return "SHOW GRANTS TO";
        }
    }

    /** {@code SHOW EFFECTIVE ACCESS FOR USER name ON target}: which grant, via which role or namespace. */
    record ShowEffectiveAccess(String user, Target target) implements CatalogStatement {
        @Override
        public String verb() {
            return "SHOW EFFECTIVE ACCESS";
        }
    }

    /** {@code SHOW NAMESPACES}: the namespaces the caller may use. */
    record ShowNamespaces() implements CatalogStatement {
        @Override
        public String verb() {
            return "SHOW NAMESPACES";
        }
    }
}

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
package com.ash.messaging.pravaha.registry;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.catalog.CatalogErrors;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogStatement;
import com.ash.messaging.pravaha.catalog.CatalogStatementExecutor;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * The catalogue's statements (ADR-059), run where the continuous-query statements run -- so Flight
 * SQL, the embedded engine and every other surface that takes {@code CREATE CONTINUOUS QUERY} takes
 * {@code GRANT} too, and decides it through the same {@link CatalogPolicy} the reads are decided by.
 *
 * <p>A node whose policy is not the catalogue's refuses them with {@code PRV-7030}: there is nowhere
 * for a grant to go, and accepting one it would not enforce would be worse than saying so.
 */
final class GovernanceStatements {

    private GovernanceStatements() {}

    /** The result's columns, known before it runs: every one text, and nullable. */
    static StreamSchema schemaOf(CatalogStatement statement) {
        StreamSchema.Builder builder = StreamSchema.builder("catalog");
        for (String column : CatalogStatementExecutor.columnsOf(statement)) {
            builder.field(column, Types.string().withNullable(true));
        }
        return builder.build();
    }

    static ViewQuery.Result execute(SecurityPolicy policy, CatalogStatement statement, Principal principal) {
        if (!(policy instanceof CatalogPolicy catalog)) {
            throw new PravahaException(
                    CatalogErrors.DISABLED,
                    statement.verb() + " changes or reads the Pravaha Catalog, and this node's catalogue is off "
                            + "(pravaha.catalog.enabled is false), so who may do what is decided by "
                            + "pravaha.security.policy and has no grants to show or change. Turn the catalogue "
                            + "on to govern access with grants.");
        }
        CatalogStatementExecutor.Answer answer =
                new CatalogStatementExecutor(catalog.service()).execute(statement, principal);
        List<Object[]> rows = new ArrayList<>();
        for (List<String> row : answer.rows()) {
            rows.add(row.toArray());
        }
        return new ViewQuery.Result(schemaOf(statement), rows);
    }
}

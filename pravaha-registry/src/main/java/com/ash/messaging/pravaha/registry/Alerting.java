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

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.alert.AlertErrors;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.AlertStatement;

/**
 * What the registry needs of the node's alerts (ADR-057), and no more: which alerts follow a view --
 * so that dropping or replacing it is refused while they do, as for a query that reads it (ADR-056
 * §4) -- and a door for the alert statements, so every surface that runs SQL against the registry runs
 * those too.
 */
public interface Alerting {

    /** No alert service: nothing follows any view, and every alert statement is refused ({@code PRV-8047}). */
    Alerting NONE = new Alerting() {
        @Override
        public List<String> followersOf(String view) {
            return List.of();
        }

        @Override
        public ViewQuery.Result execute(AlertStatement statement, Principal principal) {
            throw new PravahaException(
                    AlertErrors.NOT_SERVED,
                    statement.verb() + " needs the node's alert service, and this engine runs none: alerts are "
                            + "served by a Pravaha node (ADR-057), not an embedded engine");
        }
    };

    /** The alerts following {@code view}'s answer, as {@code ALERT <name>}; empty when none do. */
    List<String> followersOf(String view);

    /** Runs an alert statement as {@code principal}. */
    ViewQuery.Result execute(AlertStatement statement, Principal principal);
}

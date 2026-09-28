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
package com.ash.messaging.pravaha.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.LaneRebalancer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * An administrator's lane rebalance over HTTP. Nothing here runs unless an administrator asks:
 * {@code POST /api/v1/lanes/rebalance?dryRun=true} answers the plan, without it the moves start.
 * Every call requires the {@code admin} role.
 */
@RestController
@RequestMapping("/api/v1/lanes")
@Tag(name = "Lanes", description = "Where queries run, and an administrator's rebalance")
public class LaneController {

    private final RegistryAccess registry;
    private final HttpAuthorizer authorizer;
    private final LaneRebalancer rebalancer = new LaneRebalancer();

    public LaneController(RegistryAccess registry, HttpAuthorizer authorizer) {
        this.registry = registry;
        this.authorizer = authorizer;
    }

    @GetMapping("/rebalance")
    @Operation(summary = "The rebalance running or last run, or what one would do now (admin)")
    public LaneRebalancer.Plan rebalance(HttpServletRequest http) {
        authorizer.requireNodeAdmin(http, "a lane rebalance");
        return rebalancer.status(registry());
    }

    @PostMapping("/rebalance")
    @Operation(summary = "Move shared queries onto lanes of their own while there is room under auto-from (admin)")
    public LaneRebalancer.Plan start(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun, HttpServletRequest http) {
        authorizer.requireNodeAdmin(http, "a lane rebalance");
        QueryRegistry hosted = registry();
        return dryRun ? rebalancer.plan(hosted) : rebalancer.start(hosted, authorizer.principalOf(http));
    }

    LaneRebalancer rebalancer() {
        return rebalancer;
    }

    private QueryRegistry registry() {
        return registry.registry()
                .orElseThrow(() -> new PravahaException(
                        ApiErrors.INVALID_PARAMETER, "this node hosts no registry, so it has no lanes to rebalance"));
    }
}

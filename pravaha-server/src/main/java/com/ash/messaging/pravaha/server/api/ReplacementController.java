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

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.backfill.BackfillErrors;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryReplacement;
import com.ash.messaging.pravaha.registry.ReplacementOptions;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * Blue/green replacement over HTTP: start one, watch it, cut over, roll back (ADR-046).
 *
 * <p>The console's cutover screen is built on these, and design section 23.10 is what it has to be
 * able to show: how far the backfill has got and how fast, the two versions side by side, a cutover
 * that is deliberately deliberate, and a rollback that stays available for the retention window.
 * One call answers all of it -- a screen that has to ask three times shows three moments.
 *
 * <p>Every one of these requires the <strong>administer</strong> permission on the name, reading
 * included: a candidate's SQL and its progress describe a query the caller may not be allowed to
 * read, and answering "no replacement" rather than "no such query" would tell them the name exists.
 *
 * <p>{@code GET /api/v1/queries/{name}/backfill} is the path design section 16.2 names for
 * progress; it answers the same status, because a backfill without the replacement it belongs to is
 * half a story.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Replacements", description = "Blue/green replacement of a continuous query")
public class ReplacementController {

    private final RegistryAccess registry;
    private final HttpAuthorizer authorizer;

    public ReplacementController(RegistryAccess registry, HttpAuthorizer authorizer) {
        this.registry = registry;
        this.authorizer = authorizer;
    }

    /** What a caller asks for when starting a replacement. Everything but the SQL has a default. */
    public record StartReplacement(
            String sql,
            List<Integer> keyColumns,
            String backfill,
            Long rateLimit,
            String cutover,
            String rollbackRetention) {}

    @GetMapping("/replacements")
    @Operation(summary = "Every replacement this node knows about that the caller may administer")
    public List<ApiDtos.ReplacementStatus> list(HttpServletRequest http) {
        List<ApiDtos.ReplacementStatus> visible = new ArrayList<>();
        for (QueryReplacement.Status status : registry().replacements().all()) {
            if (authorizer.mayAdminister(http, status.name())) {
                visible.add(DtoMapper.replacement(status, principal(http)));
            }
        }
        return visible;
    }

    @GetMapping("/queries/{name}/replacement")
    @Operation(summary = "How the replacement of one query is getting on")
    public ApiDtos.ReplacementStatus get(@PathVariable String name, HttpServletRequest http) {
        String engine = engine(http, name);
        authorizer.requireAdminister(http, engine);
        return DtoMapper.replacement(
                registry().replacements().of(engine).orElseThrow(() -> noReplacement(name)), principal(http));
    }

    /** Design section 16.2's {@code /api/v1/queries/{id}/backfill}: the progress of one backfill. */
    @GetMapping("/queries/{name}/backfill")
    @Operation(summary = "A backfill's progress: rows read, rate, partitions on the live stream, lag")
    public ApiDtos.BackfillProgress backfill(@PathVariable String name, HttpServletRequest http) {
        String engine = engine(http, name);
        authorizer.requireAdminister(http, engine);
        return DtoMapper.replacement(
                        registry().replacements().of(engine).orElseThrow(() -> noReplacement(name)), principal(http))
                .backfill();
    }

    @PostMapping("/queries/{name}/replacement")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start replacing a query with a new version, beside the running one")
    public ApiDtos.ReplacementStatus start(
            @PathVariable String name, @RequestBody StartReplacement request, HttpServletRequest http) {
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            throw new PravahaException(
                    ApiErrors.MISSING_FIELD, "a replacement needs the new version's SQL in the request's 'sql' field");
        }
        ReplacementOptions options = ReplacementOptions.defaults();
        if (request.backfill() != null) {
            options = ReplacementOptions.with(options, "backfill", request.backfill());
        }
        if (request.rateLimit() != null) {
            options = options.withRateLimit(request.rateLimit());
        }
        if (request.cutover() != null) {
            options = ReplacementOptions.with(options, "cutover", request.cutover());
        }
        if (request.rollbackRetention() != null) {
            options = ReplacementOptions.with(options, "rollback.retention", request.rollbackRetention());
        }
        List<Integer> keys =
                request.keyColumns() == null || request.keyColumns().isEmpty()
                        ? keyOf(http, name)
                        : request.keyColumns();
        return DtoMapper.replacement(
                registry().replacements().replace(engine(http, name), request.sql(), keys, principal(http), options),
                principal(http));
    }

    @PostMapping("/queries/{name}/replacement/cutover")
    @Operation(summary = "Move the name to the new version, at a position both have consumed exactly")
    public ApiDtos.ReplacementStatus cutOver(@PathVariable String name, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().cutOver(engine(http, name), principal(http)), principal(http));
    }

    @PostMapping("/queries/{name}/replacement/rollback")
    @Operation(summary = "Put the replaced version back, while it is still retained")
    public ApiDtos.ReplacementStatus rollBack(@PathVariable String name, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().rollBack(engine(http, name), principal(http)), principal(http));
    }

    @PostMapping("/queries/{name}/replacement/finish")
    @Operation(summary = "Confirm a cutover: release the replaced version and end the rollback window")
    public ApiDtos.ReplacementStatus finish(@PathVariable String name, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().finish(engine(http, name), principal(http)), principal(http));
    }

    @DeleteMapping("/queries/{name}/replacement")
    @Operation(summary = "End a replacement that has not cut over, releasing the candidate")
    public ApiDtos.ReplacementStatus abandon(@PathVariable String name, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().abandon(engine(http, name), principal(http)), principal(http));
    }

    @PostMapping("/queries/{name}/backfill/throttle")
    @Operation(summary = "Set how fast the backfill reads history, up to the ceiling it was started with")
    public ApiDtos.ReplacementStatus throttle(
            @PathVariable String name, @RequestParam("rate") long rate, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().throttle(engine(http, name), rate, principal(http)), principal(http));
    }

    @PostMapping("/queries/{name}/backfill/pause")
    @Operation(summary = "Stop the backfill reading, without giving up what it has read")
    public ApiDtos.ReplacementStatus pause(@PathVariable String name, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().pause(engine(http, name), principal(http)), principal(http));
    }

    @PostMapping("/queries/{name}/backfill/resume")
    @Operation(summary = "Start the backfill reading again")
    public ApiDtos.ReplacementStatus resume(@PathVariable String name, HttpServletRequest http) {
        return DtoMapper.replacement(
                registry().replacements().resume(engine(http, name), principal(http)), principal(http));
    }

    /**
     * The key the running version is keyed by, for a request that does not say.
     *
     * <p>Almost every replacement keeps the key: it is the same view, answering a new question. A
     * request that means to change it says so.
     */
    private List<Integer> keyOf(HttpServletRequest http, String name) {
        return registry().require(principal(http), name).view().keyOrdinals();
    }

    /** The engine name {@code name} means to this caller: in its own tenant (ADR-060). */
    private String engine(HttpServletRequest http, String name) {
        return QueryRegistry.engineName(principal(http), name);
    }

    private QueryRegistry registry() {
        return registry.registry()
                .orElseThrow(() -> new PravahaException(
                        ApiErrors.INVALID_PARAMETER,
                        "this node hosts no registry, so it runs no continuous queries and has none to replace"));
    }

    private Principal principal(HttpServletRequest http) {
        return authorizer.principalOf(http);
    }

    private static PravahaException noReplacement(String name) {
        return new PravahaException(
                BackfillErrors.NO_REPLACEMENT,
                "'" + name + "' is not being replaced. Start one with POST /api/v1/queries/" + name
                        + "/replacement, CREATE OR REPLACE CONTINUOUS QUERY, or `pravaha replace`.");
    }
}

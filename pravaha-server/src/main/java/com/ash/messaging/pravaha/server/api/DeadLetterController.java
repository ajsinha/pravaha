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
import java.util.Base64;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.DeadLetters;
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterCounts;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * A query's dead letters over HTTP: list them, fetch one whole, count them, put them back.
 *
 * <p>The queue was a file on the node's disk read by nothing. For a deployment in a container,
 * that is a product feature nobody can reach: the operator who needs it has a browser and an API
 * token, not a shell on the pod.
 *
 * <p>Under {@code /api/v1/queries/{name}/dead-letters} rather than a top-level {@code
 * /dead-letters}, because a dead letter belongs to a query -- the query's name is what is
 * authorized, and a flat listing across the node would have to authorize each row and would then
 * be a listing endpoint pretending to be a queue.
 *
 * <p>Every authorization decision, including which entries have their bytes removed, is made by
 * {@link DeadLetters} and not here: the Flight action, the CLI and the SDKs ask the same question
 * and must get the same answer.
 */
@RestController
@RequestMapping("/api/v1/queries/{name}/dead-letters")
@Tag(name = "Dead letters", description = "Records a query's feed could not decode: read, count and replay")
public class DeadLetterController {

    private final HttpAuthorizer authorizer;
    private final RegistryAccess registry;

    public DeadLetterController(HttpAuthorizer authorizer, RegistryAccess registry) {
        this.authorizer = authorizer;
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "A page of a query's dead letters, newest first")
    public DeadLetterDtos.Page list(
            @PathVariable String name,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest http) {
        DeadLetters access = access(http, name);
        DeadLetters.View view = access.page(authorizer.principalOf(http), name, offset, limit);
        List<DeadLetterDtos.DeadLetter> entries = new ArrayList<>(view.entries().size());
        for (DeadLetters.Visible visible : view.entries()) {
            entries.add(toDto(name, visible));
        }
        DeadLetterCounts counts = view.counts();
        return new DeadLetterDtos.Page(
                name,
                entries,
                view.page().offset(),
                view.page().limit(),
                view.page().total(),
                view.page().more(),
                counts.bytes(),
                counts.evicted(),
                counts.evictedBytes(),
                counts.replayed(),
                counts.failedAgain(),
                counts.oldest().orElse(null),
                counts.newest().orElse(null),
                access.store().retention().describe(),
                access.store().configured());
    }

    @GetMapping("/count")
    @Operation(summary = "How deep a query's dead-letter queue is, and what retention has taken")
    public DeadLetterDtos.Count count(@PathVariable String name, HttpServletRequest http) {
        DeadLetters access = access(http, name);
        DeadLetterCounts counts = access.counts(authorizer.principalOf(http), name);
        return new DeadLetterDtos.Count(
                name,
                counts.entries(),
                counts.bytes(),
                counts.evicted(),
                counts.evictedBytes(),
                counts.replayed(),
                counts.failedAgain(),
                counts.oldest().orElse(null),
                counts.newest().orElse(null),
                access.store().retention().describe(),
                access.store().configured());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One dead letter whole: its bytes, its reason, its offset and when")
    public DeadLetterDtos.DeadLetter show(@PathVariable String name, @PathVariable String id, HttpServletRequest http) {
        return toDto(name, access(http, name).show(authorizer.principalOf(http), name, id));
    }

    /**
     * Feeds one dead letter, or a selection, back through the query.
     *
     * <p>A {@code POST} and not a {@code PUT}: it is not idempotent, and saying so in the method
     * is the only place a client will notice before it writes a retry loop. Replaying the same id
     * twice puts the row in twice, which for a query counting things is two.
     *
     * <p>A selection is replayed in the order given and each result is reported separately. One
     * that fails again does not stop the rest: a batch that abandoned the remaining ninety-nine
     * records because the first was still malformed would make a queue of mixed causes
     * unclearable.
     */
    @PostMapping("/replay")
    @Operation(summary = "Re-feed chosen dead letters into the query, as new rows at the current frontier")
    public DeadLetterDtos.ReplayResult replay(
            @PathVariable String name, @RequestBody DeadLetterDtos.ReplayRequest body, HttpServletRequest http) {
        if (body == null || body.ids() == null || body.ids().isEmpty()) {
            throw new IllegalArgumentException(
                    "say which dead letters to replay: {\"ids\": [\"<id>\", ...]}. Replaying a whole queue by "
                            + "omission is not offered -- a queue is usually a mix of causes, and most of it is "
                            + "still malformed.");
        }
        DeadLetters access = access(http, name);
        Principal principal = authorizer.principalOf(http);
        List<DeadLetterDtos.Replayed> results = new ArrayList<>(body.ids().size());
        int replayed = 0;
        int failedAgain = 0;
        for (String id : body.ids()) {
            DeadLetters.Replayed one = access.replay(principal, name, id);
            results.add(new DeadLetterDtos.Replayed(one.id(), one.outcome().name(), one.detail(), one.newId()));
            if (one.outcome() == DeadLetterEntry.Replay.REPLAYED) {
                replayed++;
            } else {
                failedAgain++;
            }
        }
        return new DeadLetterDtos.ReplayResult(name, results, replayed, failedAgain);
    }

    private static DeadLetterDtos.DeadLetter toDto(String query, DeadLetters.Visible visible) {
        DeadLetterEntry entry = visible.entry();
        byte[] raw = visible.raw();
        return new DeadLetterDtos.DeadLetter(
                entry.id(),
                entry.sequence(),
                query,
                entry.letter().stream(),
                entry.letter().sourceOffset(),
                entry.letter().code(),
                visible.reason(),
                entry.letter().at().orElse(null),
                visible.size(),
                visible.redacted() ? null : Base64.getEncoder().encodeToString(raw),
                visible.redacted() ? DeadLetters.WITHHELD : null,
                entry.replay().name(),
                entry.replayedAt());
    }

    /**
     * The rules, against the running node's registry and dead-letter directory.
     *
     * <p>Before the node has a registry there is nothing registered, so a name is answered exactly
     * as an unregistered one -- after the policy has had its say, so that a caller cannot learn
     * from a 404 that the node was still starting.
     */
    private DeadLetters access(HttpServletRequest http, String name) {
        return registry.registry()
                .map(found -> new DeadLetters(found, found.policy(), registry.audit(), registry.deadLetters()))
                .orElseGet(() -> {
                    authorizer.requireRead(http, name);
                    throw new PravahaException(
                            RegistryErrors.NO_SUCH_QUERY,
                            "'" + name + "' is not a query you may see on this server, so it has no dead "
                                    + "letters here.");
                });
    }
}

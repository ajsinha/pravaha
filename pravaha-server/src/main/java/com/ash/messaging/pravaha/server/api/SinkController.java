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
import java.util.Map;
import java.util.TreeMap;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.registry.SinkFactory;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The sinks this node has bound, as a client deciding where a query should write needs them.
 *
 * <p><strong>A binding's options are never read here.</strong> They are where a password, an access
 * key or a connection string with credentials in it lives, and the only safe way to be sure none of
 * them reaches a response is for no response to be built from them. What a sink promises -- the row
 * shape it was configured with, its key, the changelogs it can take -- comes from asking the plugin
 * ({@link SinkFactory#describe}), the same question registration asks before it will attach one.
 *
 * <p>Filtered like the stream catalogue: a sink appears to a caller the policy lets read its name, so
 * a deployment whose policy names its sinks decides who sees them, and an authenticated-only or
 * permissive node shows them to every caller it would show a stream to. The queries writing to each
 * sink are the ones the caller's own listing shows -- a sink is not a way to learn that a hidden view
 * exists.
 */
@RestController
@RequestMapping("/api/v1/sinks")
@Tag(name = "Sinks", description = "Configured sink bindings and what they accept")
public class SinkController {

    private final DtoMapper mapper;
    private final HttpAuthorizer authorizer;
    private final RegistryAccess registry;

    public SinkController(DtoMapper mapper, HttpAuthorizer authorizer, RegistryAccess registry) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "List the bound sinks this caller may see, with what each accepts")
    public List<ApiDtos.SinkSummary> list(HttpServletRequest http) {
        PluginSinks sinks = registry.sinks().orElse(null);
        if (sinks == null) {
            return List.of();
        }
        Principal principal = authorizer.principalOf(http);
        // Writers first, from the caller's own listing, so a hidden query cannot appear as a writer.
        Map<String, List<String>> writers = new TreeMap<>();
        registry.listing().ifPresent(listing -> {
            for (QueryListing.Entry entry : listing.list(principal, "http.sinks")) {
                entry.sink()
                        .ifPresent(sink -> writers.computeIfAbsent(sink, key -> new ArrayList<>())
                                .add(entry.name()));
            }
        });
        List<ApiDtos.SinkSummary> out = new ArrayList<>();
        for (SinkBinding binding : new TreeMap<>(sinks.bindings()).values()) {
            if (!authorizer.mayRead(http, binding.sinkName())) {
                continue;
            }
            out.add(summary(sinks, binding, writers.getOrDefault(binding.sinkName(), List.of())));
        }
        return out;
    }

    private ApiDtos.SinkSummary summary(PluginSinks sinks, SinkBinding binding, List<String> writers) {
        SinkFactory.Description description;
        try {
            description = sinks.describe(binding.sinkName());
        } catch (PravahaException e) {
            // The plugin's own text is not repeated: a plugin refusing its configuration is exactly the
            // moment it is most likely to quote the configuration back.
            return new ApiDtos.SinkSummary(
                    binding.sinkName(),
                    binding.plugin(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    null,
                    List.copyOf(writers),
                    new ApiDtos.Problem(
                            e.errorCode().code(),
                            "the '" + binding.plugin() + "' plugin could not describe this sink. Its own message "
                                    + "is in the node's log rather than here, because it can quote the "
                                    + "binding's options.",
                            e.helpUrl()));
        }
        SinkCapabilities capabilities = description.capabilities();
        return new ApiDtos.SinkSummary(
                binding.sinkName(),
                binding.plugin(),
                description.schema().map(mapper::toFields).orElse(List.of()),
                description.keyColumns(),
                capabilities.emitModes().stream().map(Enum::name).sorted().toList(),
                // The question PRV-2041 asks: can this sink take a query that revises its answer?
                capabilities.accepts(EmitMode.UPSERT) || capabilities.accepts(EmitMode.RETRACT),
                // What this node would give, not the SPI's best case: capabilities.guarantee() calls
                // idempotent upsert exactly once and cannot know whether the node checkpoints (HLP-4).
                registry.registry()
                        .map(r -> r.sinkGuaranteeFor(capabilities))
                        .orElse(capabilities.guarantee().name()),
                List.copyOf(writers),
                null);
    }
}

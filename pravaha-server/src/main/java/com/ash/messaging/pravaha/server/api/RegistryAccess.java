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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.server.PravahaNode;

/**
 * What the HTTP API may ask of the running node's registry and sinks, and nothing else.
 *
 * <p>The registry is built when the node starts, after Spring has built the controllers, so it is
 * looked up per request rather than injected. Before the node is running there is no registry, and
 * an endpoint describing registered queries answers as if none were registered -- which is true.
 *
 * <p>The listing rules are taken from {@link QueryListing} with the registry's <em>own</em> policy
 * and the node's audit sink: the same policy object and the same sink Flight's {@code pravaha.list}
 * uses, so the two surfaces decide with one rule-set and record into one log.
 */
@Component
public class RegistryAccess {

    private final Supplier<Optional<QueryRegistry>> registry;
    private final Supplier<Optional<PluginSinks>> sinks;
    private final Supplier<Optional<PluginSourceFeeds>> sources;
    private final AuditSink audit;

    /**
     * Where Flight is listening, as a client would write it; empty when Flight is off or the node
     * has not started. CFG-2(b): with {@code pravaha.flight.port: 0} the bound port is the only one
     * that can be connected to, and no served surface carried it.
     */
    private final Supplier<Optional<String>> flightAddress;

    @Autowired
    public RegistryAccess(PravahaNode node, AuditSink audit) {
        this(node::registry, node::sinks, node::sources, node::flightAddress, audit);
    }

    /** For a test, or anything else holding a registry directly. */
    public RegistryAccess(@Nullable QueryRegistry registry, @Nullable PluginSinks sinks, @Nullable AuditSink audit) {
        this(registry, sinks, null, audit);
    }

    /** As {@link #RegistryAccess(QueryRegistry, PluginSinks, AuditSink)}, with the source bindings too. */
    public RegistryAccess(
            @Nullable QueryRegistry registry,
            @Nullable PluginSinks sinks,
            @Nullable PluginSourceFeeds sources,
            @Nullable AuditSink audit) {
        this(
                () -> Optional.ofNullable(registry),
                () -> Optional.ofNullable(sinks),
                () -> Optional.ofNullable(sources),
                Optional::empty,
                audit);
    }

    private RegistryAccess(
            Supplier<Optional<QueryRegistry>> registry,
            Supplier<Optional<PluginSinks>> sinks,
            Supplier<Optional<PluginSourceFeeds>> sources,
            Supplier<Optional<String>> flightAddress,
            @Nullable AuditSink audit) {
        this.registry = registry;
        this.sinks = sinks;
        this.sources = sources;
        this.flightAddress = flightAddress;
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    /** Where Flight is listening; empty when it is disabled or the node has not started. */
    public Optional<String> flightAddress() {
        return flightAddress.get();
    }

    public Optional<QueryRegistry> registry() {
        return registry.get();
    }

    public Optional<PluginSinks> sinks() {
        return sinks.get();
    }

    /**
     * What has been dead-lettered, read from the source bindings' configured directory (B5).
     *
     * <p>Through the bindings rather than through the node, so that the dead-letter surfaces are
     * constructible over a bare registry the way the listing ones are -- and so that the one place
     * that knows where the files are is the one place that writes them.
     */
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters() {
        return sources.get()
                .map(PluginSourceFeeds::deadLetters)
                .orElse(com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE);
    }

    /** The node's audit sink, for a surface that builds its own rule object over the registry. */
    public AuditSink audit() {
        return audit;
    }

    /** The listing rules, with the registry's own policy; empty before the node has a registry. */
    public Optional<QueryListing> listing() {
        return registry().map(found -> new QueryListing(found, found.policy(), audit));
    }

    /**
     * {@code text} with every configured sink or source option value that could be a credential
     * removed.
     *
     * <p>A sink failure's message is the plugin's own exception text, and a plugin that echoes its
     * connection string into an exception is common enough that the message cannot be trusted not to.
     * A stopped source feed's message is the same kind of text from the other end (FEED-1), so the
     * source bindings' options are struck out too. Every value of a key that names a credential is
     * removed, and every value long enough to be one whatever its key -- {@code
     * Redaction.strikeOptionValues}, the one rule every surface applies (SINK-4).
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<Map<String, String>> options = new java.util.ArrayList<>();
        sinks().ifPresent(found -> found.bindings().values().forEach(binding -> options.add(binding.options())));
        sources.get().ifPresent(found -> found.bindings().values().forEach(binding -> options.add(binding.options())));
        return com.ash.messaging.pravaha.common.config.Redaction.strikeOptionValues(text, options);
    }
}

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
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
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
    private final AuditSink audit;

    @Autowired
    public RegistryAccess(PravahaNode node, AuditSink audit) {
        this(node::registry, node::sinks, audit);
    }

    /** For a test, or anything else holding a registry directly. */
    public RegistryAccess(QueryRegistry registry, PluginSinks sinks, AuditSink audit) {
        this(() -> Optional.ofNullable(registry), () -> Optional.ofNullable(sinks), audit);
    }

    private RegistryAccess(
            Supplier<Optional<QueryRegistry>> registry, Supplier<Optional<PluginSinks>> sinks, AuditSink audit) {
        this.registry = registry;
        this.sinks = sinks;
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    public Optional<QueryRegistry> registry() {
        return registry.get();
    }

    public Optional<PluginSinks> sinks() {
        return sinks.get();
    }

    /** The listing rules, with the registry's own policy; empty before the node has a registry. */
    public Optional<QueryListing> listing() {
        return registry().map(found -> new QueryListing(found, found.policy(), audit));
    }

    /** Option keys whose values are credentials or carry them, whatever their length. */
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i).*(pass|secret|token|key|credential|auth|url|uri|dsn|connection|user).*");

    /**
     * {@code text} with every configured sink option value that could be a credential removed.
     *
     * <p>A sink failure's message is the plugin's own exception text, and a plugin that echoes its
     * connection string into an exception is common enough that the message cannot be trusted not to.
     * Every value of a key that names a credential is removed, and every value long enough to be one
     * whatever its key: over-redacting a diagnostic costs a word, and under-redacting costs a password.
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        List<SinkBinding> bindings =
                sinks().map(found -> List.copyOf(found.bindings().values())).orElse(List.of());
        for (SinkBinding binding : bindings) {
            for (Map.Entry<String, String> option : binding.options().entrySet()) {
                String value = option.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                boolean sensitive = SENSITIVE_KEY.matcher(option.getKey()).matches();
                // Three characters at least: a one-letter value would strike every occurrence of that
                // letter from the message and leave nothing to read.
                if (value.length() >= 8 || (sensitive && value.length() >= 3)) {
                    out = out.replace(value, "[redacted " + option.getKey() + "]");
                }
            }
        }
        return out;
    }
}

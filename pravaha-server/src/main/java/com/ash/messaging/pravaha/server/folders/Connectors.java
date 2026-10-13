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
package com.ash.messaging.pravaha.server.folders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

/**
 * The named connections: one file per connection in {@code pravaha.config.connectors-dir}, holding a plugin
 * and its options, that a binding names with {@code connector:} instead of repeating them (ADR-064).
 *
 * <pre>
 * # config/connectors/kafka-prod.yaml
 * plugin: kafka
 * options:
 *   bootstrap.servers: broker1:9092
 *   ssl.truststore.password: ${KAFKA_TRUSTSTORE_PASSWORD}
 *
 * # config/streams/txn.yaml
 * schema: "id:INT64,amount:INT64"
 * source:
 *   connector: kafka-prod
 *   options: {topic: txn}          # laid over the connection's; the binding's own win
 * </pre>
 *
 * <p>A binding with no {@code connector:} is exactly what it always was. One that names a connection takes
 * its plugin from it -- naming a different plugin as well is refused ({@code PRV-1068}), and naming a
 * connection nobody declared is refused ({@code PRV-1067}).
 */
public final class Connectors {

    private final Map<String, DeclarationFile> byName = new LinkedHashMap<>();

    /** What a binding resolved to. */
    public record Resolved(String plugin, Map<String, String> options) {}

    public synchronized void put(DeclarationFile connector) {
        byName.put(connector.name(), connector);
    }

    public synchronized void remove(String name) {
        byName.remove(name);
    }

    public synchronized Optional<DeclarationFile> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Every connection, by name. */
    public synchronized Map<String, DeclarationFile> all() {
        return new TreeMap<>(byName);
    }

    /**
     * Resolves a binding against the connection it names, or empty when it names none.
     *
     * @param binding what the binding is, for a refusal: {@code source 'txn' (config/streams/txn.yaml)}
     */
    public Optional<Resolved> resolve(
            String binding, @Nullable String plugin, @Nullable String connector, Map<String, String> options) {
        if (connector == null || connector.isBlank()) {
            return Optional.empty();
        }
        DeclarationFile named = find(connector)
                .orElseThrow(() -> new PravahaException(
                        ConfigErrors.CONNECTOR_UNKNOWN,
                        binding + " names connector '" + connector + "', and no file in the connectors folder declares "
                                + "it. Known: " + all().keySet() + ". Add " + connector
                                + ".yaml there, or name another."));
        SourceBindingProperties.Spec spec = Objects.requireNonNull(named.source());
        String itsPlugin = Objects.requireNonNull(spec.getPlugin());
        if (plugin != null && !plugin.isBlank() && !plugin.strip().equals(itsPlugin)) {
            throw new PravahaException(
                    ConfigErrors.CONNECTOR_PLUGIN_CLASH,
                    binding + " names plugin '" + plugin.strip() + "' and connector '" + connector
                            + "', whose plugin is '"
                            + itsPlugin + "' (" + named.path() + "). The plugin comes from the connection: leave "
                            + "plugin: out, or name a connection for '" + plugin.strip() + "'.");
        }
        Map<String, String> merged = new LinkedHashMap<>(spec.getOptions());
        merged.putAll(options);
        return Optional.of(new Resolved(itsPlugin, merged));
    }

    /** Resolves a source or lookup binding in place; a binding with no connector is left as it is. */
    public void resolve(String binding, SourceBindingProperties.Spec spec) {
        resolve(binding, spec.getPlugin(), spec.getConnector(), spec.getOptions())
                .ifPresent(resolved -> spec.resolvedTo(resolved.plugin(), resolved.options()));
    }

    /** Resolves a sink binding in place; a binding with no connector is left as it is. */
    public void resolve(String binding, SinkBindingProperties.Spec spec) {
        resolve(binding, spec.getPlugin(), spec.getConnector(), spec.getOptions())
                .ifPresent(resolved -> spec.resolvedTo(resolved.plugin(), resolved.options()));
    }

    /** The bindings naming connection {@code name}: {@code source:txn}, {@code lookup:users}, {@code sink:out}. */
    public static List<String> users(String name, SourceBindingProperties sources, SinkBindingProperties sinks) {
        List<String> users = new ArrayList<>();
        synchronized (sources.getSources()) {
            sources.getSources().forEach((stream, spec) -> {
                if (name.equals(spec.getConnector())) {
                    users.add("source:" + stream);
                }
            });
        }
        synchronized (sources.getLookups()) {
            sources.getLookups().forEach((lookup, spec) -> {
                if (name.equals(spec.getConnector())) {
                    users.add("lookup:" + lookup);
                }
            });
        }
        synchronized (sinks.getSinks()) {
            sinks.getSinks().forEach((sink, spec) -> {
                if (name.equals(spec.getConnector())) {
                    users.add("sink:" + sink);
                }
            });
        }
        return users;
    }

    /** {@code options} with every password, secret, token and key struck out, for a listing. */
    public static Map<String, String> masked(Map<String, String> options) {
        Map<String, String> shown = new TreeMap<>();
        options.forEach((key, value) -> shown.put(key, DeclarationFile.isSecretKey(key) ? "****" : value));
        return shown;
    }
}

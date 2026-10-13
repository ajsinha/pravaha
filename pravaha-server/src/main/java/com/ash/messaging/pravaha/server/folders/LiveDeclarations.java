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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.bindings.ingest.PluginLookupSources;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarations;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

/**
 * A running node's half of {@link DeclarationWatcher}: what declaring, replacing and removing a stream,
 * dimension table or sink means once the node is up (ADR-064).
 *
 * <p>Each change asks the registry, under its lock, whether a query uses the object, and is made only if
 * none does -- under the same locks {@code POST /api/v1/streams} declares a stream under, so a
 * registration cannot fall between the question and the change. What a file declares is validated by the
 * code that validates the inline blocks at start ({@link StreamDeclarations}, the plugins' own discovery
 * and configuration), and before anything is changed, so a refusal leaves the node as it was.
 */
public final class LiveDeclarations implements DeclarationWatcher.Target {

    private static final Logger LOG = LoggerFactory.getLogger(LiveDeclarations.class);

    private final StreamCatalog catalog;
    private final QueryRegistry registry;
    private final PluginSourceFeeds feeds;
    private final PluginLookupSources lookups;
    private final PluginSinks sinks;
    private final StreamDeclarationProperties declaredStreams;
    private final SourceBindingProperties sourceSettings;
    private final SinkBindingProperties sinkSettings;
    private final Duration defaultOutOfOrderness;
    private final Connectors connectors;

    public LiveDeclarations(
            StreamCatalog catalog,
            QueryRegistry registry,
            PluginSourceFeeds feeds,
            PluginLookupSources lookups,
            PluginSinks sinks,
            StreamDeclarationProperties declaredStreams,
            SourceBindingProperties sourceSettings,
            SinkBindingProperties sinkSettings,
            Duration defaultOutOfOrderness,
            Connectors connectors) {
        this.catalog = catalog;
        this.registry = registry;
        this.feeds = feeds;
        this.lookups = lookups;
        this.sinks = sinks;
        this.declaredStreams = declaredStreams;
        this.sourceSettings = sourceSettings;
        this.sinkSettings = sinkSettings;
        this.defaultOutOfOrderness = defaultOutOfOrderness;
        this.connectors = connectors;
    }

    @Override
    public List<String> declare(DeclarationFile file) {
        return switch (file.kind()) {
            case STREAM -> declareStream(file);
            case LOOKUP -> declareLookup(file);
            case SINK -> declareSink(file);
            case CONNECTOR -> declareConnector(file);
        };
    }

    private List<String> declareStream(DeclarationFile file) {
        String name = file.name();
        StreamDeclarationProperties.Declaration declaration = Objects.requireNonNull(file.stream());
        StreamSchema schema =
                StreamDeclarations.schemaOf(name, declaration, defaultOutOfOrderness, "file " + file.path());
        SourceBindingProperties.Spec spec = file.source();
        if (spec != null) {
            connectors.resolve("source '" + name + "' (" + file.path() + ")", spec);
        }
        @Nullable
        SourceBinding binding = spec == null
                ? null
                : StreamDeclarations.withDeclaredEventTime(
                        new SourceBinding(
                                name, Objects.requireNonNull(spec.effectivePlugin()), spec.effectiveOptions()),
                        declaration);
        List<String> users = catalog.declareUnlessUsed(schema, () -> {
            if (binding != null) {
                feeds.bind(binding); // first: an unknown plugin is refused before anything changes
                sourceSettings.getSources().put(name, spec);
            } else {
                feeds.unbind(name);
                sourceSettings.getSources().remove(name);
            }
            declaredStreams.getStreams().put(name, declaration);
        });
        if (users.isEmpty()) {
            StreamDeclarations.describe(LOG, schema);
        }
        return users;
    }

    private List<String> declareLookup(DeclarationFile file) {
        String name = file.name();
        SourceBindingProperties.Spec spec = Objects.requireNonNull(file.source());
        connectors.resolve("lookup '" + name + "' (" + file.path() + ")", spec);
        SourceBinding binding =
                new SourceBinding(name, Objects.requireNonNull(spec.effectivePlugin()), spec.effectiveOptions());
        // Opened before the question, outside every lock: opening a table is network I/O.
        LookupSourcePlugin opened = lookups.openOne(binding);
        List<String> users = registry.unlessUsed(QueryRegistry.Use.LOOKUP, name, () -> {
            registry.lookingUp(opened);
            catalog.registerLookup(opened.schema());
            lookups.adopt(binding, opened);
            sourceSettings.getLookups().put(name, spec);
        });
        if (!users.isEmpty()) {
            try {
                opened.close();
            } catch (Exception e) {
                LOG.debug("closing dimension table '{}', opened for a change that was not applied", name, e);
            }
        }
        return users;
    }

    private List<String> declareSink(DeclarationFile file) {
        String name = file.name();
        SinkBindingProperties.Spec spec = Objects.requireNonNull(file.sink());
        connectors.resolve("sink '" + name + "' (" + file.path() + ")", spec);
        SinkBinding binding =
                new SinkBinding(name, Objects.requireNonNull(spec.effectivePlugin()), spec.effectiveOptions());
        // Asked of a binding of its own first, as start asks every configured sink: an unknown plugin or a
        // configuration it refuses is refused here, and the sink in force is untouched.
        try (PluginSinks trial = new PluginSinks()) {
            trial.bind(binding).capabilitiesOf(name);
        }
        return registry.unlessUsed(QueryRegistry.Use.SINK, name, () -> {
            sinks.bind(binding);
            sinkSettings.getSinks().put(name, spec);
        });
    }

    /**
     * A named connection: available at once when new; replaced only while no binding names it, because a
     * binding resolved its plugin and options from it when it was bound and would not see the change.
     */
    private List<String> declareConnector(DeclarationFile file) {
        if (connectors.find(file.name()).isPresent()) {
            List<String> users = Connectors.users(file.name(), sourceSettings, sinkSettings);
            if (!users.isEmpty()) {
                return users;
            }
        }
        connectors.put(file);
        return List.of();
    }

    @Override
    public List<String> remove(DeclarationKind kind, String name) {
        return switch (kind) {
            case CONNECTOR -> {
                List<String> users = Connectors.users(name, sourceSettings, sinkSettings);
                if (users.isEmpty()) {
                    connectors.remove(name);
                }
                yield users;
            }
            case STREAM ->
                catalog.forgetUnlessUsed(name, () -> {
                    feeds.unbind(name);
                    sourceSettings.getSources().remove(name);
                    declaredStreams.getStreams().remove(name);
                });
            case LOOKUP ->
                registry.unlessUsed(QueryRegistry.Use.LOOKUP, name, () -> {
                    registry.forgetLookup(name);
                    catalog.forgetLookup(name);
                    lookups.unbind(name);
                    sourceSettings.getLookups().remove(name);
                });
            case SINK ->
                registry.unlessUsed(QueryRegistry.Use.SINK, name, () -> {
                    sinks.unbind(name);
                    sinkSettings.getSinks().remove(name);
                });
        };
    }

    @Override
    public boolean declaredElsewhere(DeclarationKind kind, String name) {
        return switch (kind) {
            case CONNECTOR -> false;
            case STREAM -> catalog.contains(name);
            case LOOKUP -> lookups.bindings().containsKey(name);
            case SINK -> sinks.bindings().containsKey(name);
        };
    }
}

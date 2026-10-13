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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

/**
 * The per-object configuration folders: one YAML file per stream, lookup and sink, named after it, read at
 * start and looked at again every {@code pravaha.config.watch-interval} while the node runs (ADR-064).
 *
 * <p><b>At start</b> every file is read into the same property objects {@code pravaha.streams},
 * {@code pravaha.sources}, {@code pravaha.lookups} and {@code pravaha.sinks} bind into, before the node
 * reads them -- so a file declares exactly what the same block inline would, through the same code. Any
 * file that cannot be used stops the node, as invalid inline configuration does; so does a name declared
 * both in a file and inline ({@code PRV-1062}), and a folder named explicitly that does not exist
 * ({@code PRV-1060}).
 *
 * <p><b>While running</b>, {@link DeclarationWatcher} applies what changed. See it for the rules: a new file
 * declares, a removed file removes, a changed file replaces -- unless a registered query uses the object.
 */
@Component
public class ConfigFolders implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigFolders.class);

    private final @Nullable ConfigFolderProperties properties;
    private final PlaceholdersResolver placeholders;
    private final Map<DeclarationKind, Set<String>> inline = new EnumMap<>(DeclarationKind.class);
    private final Map<DeclarationKind, Map<String, DeclarationFile>> loaded = new EnumMap<>(DeclarationKind.class);
    private final Connectors connectors = new Connectors();
    private volatile @Nullable DeclarationWatcher watcher;
    private @Nullable ScheduledExecutorService executor;
    private boolean read;

    @Autowired
    public ConfigFolders(ConfigFolderProperties properties, Environment environment) {
        this(properties, new PropertySourcesPlaceholdersResolver(environment));
        CliDirectoryVariable.refuseIfAlone(environment); // ENVCONFIGDIR-1
    }

    /** Folders read with {@code placeholders} resolving {@code ${ENV}}: a test's, or a builder-made node's. */
    public ConfigFolders(@Nullable ConfigFolderProperties properties, PlaceholdersResolver placeholders) {
        this.properties = properties;
        this.placeholders = placeholders;
        for (DeclarationKind kind : DeclarationKind.values()) {
            inline.put(kind, Set.of());
            loaded.put(kind, Map.of());
        }
    }

    /** No folders at all: a node made by {@code PravahaNode.builder()} that was not given any. */
    public static ConfigFolders none() {
        return new ConfigFolders(null, new PropertySourcesPlaceholdersResolver(new StandardEnvironment()));
    }

    /**
     * Reads every folder into the inline blocks' property objects, refusing what cannot be used.
     *
     * <p>Once per node: a second call -- a node restarted in one process -- changes nothing.
     */
    public synchronized void loadInto(
            StreamDeclarationProperties streams, SourceBindingProperties sources, SinkBindingProperties sinks) {
        if (read) {
            return;
        }
        Set<String> inlineStreams = new LinkedHashSet<>(streams.getStreams().keySet());
        inlineStreams.addAll(sources.getSources().keySet());
        inline.put(DeclarationKind.STREAM, Set.copyOf(inlineStreams));
        inline.put(DeclarationKind.LOOKUP, Set.copyOf(sources.getLookups().keySet()));
        inline.put(DeclarationKind.SINK, Set.copyOf(sinks.getSinks().keySet()));
        inline.put(DeclarationKind.CONNECTOR, Set.of());
        read = true;
        ConfigFolderProperties settings = properties;
        if (settings == null) {
            return;
        }
        settings.interval(); // validated now, so a bad interval stops the node rather than the watch
        for (DeclarationKind kind : DeclarationKind.values()) {
            Map<String, DeclarationFile> files = readFolder(settings.folder(kind));
            loaded.put(kind, files);
            files.values().forEach(file -> {
                switch (kind) {
                    case STREAM -> {
                        streams.getStreams().put(file.name(), java.util.Objects.requireNonNull(file.stream()));
                        if (file.source() != null) {
                            sources.getSources().put(file.name(), file.source());
                        }
                    }
                    case LOOKUP ->
                        sources.getLookups().put(file.name(), java.util.Objects.requireNonNull(file.source()));
                    case SINK -> sinks.getSinks().put(file.name(), java.util.Objects.requireNonNull(file.sink()));
                    case CONNECTOR -> connectors.put(file);
                }
            });
            if (!files.isEmpty()) {
                LOG.info(
                        "{} declared by files in {}: {}",
                        kind.plural(),
                        settings.folder(kind).path(),
                        files.keySet());
            }
        }
        // Every binding naming a connection -- in a file or inline -- resolved now, so an unknown one
        // (PRV-1067) or a plugin clash (PRV-1068) stops the node rather than its first registration.
        sources.getSources()
                .forEach((name, spec) -> connectors.resolve(
                        "source '" + name + "' (" + where(DeclarationKind.STREAM, name, "pravaha.sources." + name)
                                + ")",
                        spec));
        sources.getLookups()
                .forEach((name, spec) -> connectors.resolve(
                        "lookup '" + name + "' (" + where(DeclarationKind.LOOKUP, name, "pravaha.lookups." + name)
                                + ")",
                        spec));
        sinks.getSinks()
                .forEach((name, spec) -> connectors.resolve(
                        "sink '" + name + "' (" + where(DeclarationKind.SINK, name, "pravaha.sinks." + name) + ")",
                        spec));
    }

    private String where(DeclarationKind kind, String name, String inlineKey) {
        DeclarationFile file = map(loaded, kind).get(name);
        return file == null ? inlineKey : file.path().toString();
    }

    private Set<String> names(DeclarationKind kind) {
        return inline.getOrDefault(kind, Set.of());
    }

    private static Map<String, DeclarationFile> map(
            Map<DeclarationKind, Map<String, DeclarationFile>> byKind, DeclarationKind kind) {
        return byKind.getOrDefault(kind, Map.of());
    }

    /** The named connections, as read at start and kept current by the watcher. */
    public Connectors connectors() {
        return connectors;
    }

    private Map<String, DeclarationFile> readFolder(ConfigFolderProperties.Folder folder) {
        Map<String, DeclarationFile> files = new LinkedHashMap<>();
        if (!Files.isDirectory(folder.path())) {
            if (folder.configured()) {
                throw new PravahaException(
                        ConfigErrors.FOLDER_MISSING,
                        folder.setting() + " is " + folder.path() + ", which is not a folder. Create it, or remove "
                                + "the setting to use " + folder.kind().plural() + "/ under pravaha.config.dir -- a "
                                + "folder found by convention may be missing, and is then simply empty.");
            }
            return files;
        }
        for (Path path : DeclarationFile.list(folder.path())) {
            DeclarationFile file = DeclarationFile.read(folder.kind(), path, placeholders);
            DeclarationFile twin = files.get(file.name());
            if (twin != null) {
                throw new PravahaException(
                        ConfigErrors.DECLARED_TWICE,
                        folder.kind().label() + " '" + file.name() + "' is declared by both " + twin.path() + " and "
                                + path + ". One file per " + folder.kind().label() + ": remove one.");
            }
            if (names(folder.kind()).contains(file.name())) {
                throw new PravahaException(
                        ConfigErrors.DECLARED_TWICE,
                        folder.kind().label() + " '" + file.name() + "' is declared both by " + path + " and inline "
                                + "under "
                                + (folder.kind() == DeclarationKind.STREAM
                                        ? "pravaha.streams or pravaha.sources"
                                        : folder.kind().inlineBlock())
                                + "." + file.name() + ". One name, one declaration: keep the file or the inline "
                                + "block, not both.");
            }
            files.put(file.name(), file);
        }
        return files;
    }

    /** Where a stream was declared, for a refusal of it: its file, or the inline block. */
    public String whereDeclared(String stream) {
        DeclarationFile file = map(loaded, DeclarationKind.STREAM).get(stream);
        DeclarationWatcher live = watcher;
        Optional<Path> now = live == null ? Optional.empty() : live.fileOf(DeclarationKind.STREAM, stream);
        return now.map(path -> "file " + path).orElse(file == null ? "pravaha.streams" : "file " + file.path());
    }

    /**
     * Starts applying the folders to the running node, polled every {@code pravaha.config.watch-interval};
     * off ({@code 0}) leaves what start read in force until a restart.
     */
    public synchronized void watch(DeclarationWatcher.Target target, AuditSink audit) {
        ConfigFolderProperties settings = properties;
        if (settings == null || watcher != null) {
            return;
        }
        Map<DeclarationKind, ConfigFolderProperties.Folder> folders = new EnumMap<>(DeclarationKind.class);
        for (DeclarationKind kind : DeclarationKind.values()) {
            folders.put(kind, settings.folder(kind));
        }
        DeclarationWatcher built = new DeclarationWatcher(folders, this::names, placeholders, loaded, target, audit);
        watcher = built;
        Optional<Duration> every = settings.interval();
        if (every.isEmpty()) {
            LOG.info(
                    "declaration folders are read at start only (pravaha.config.watch-interval is off): {}",
                    folders.values().stream()
                            .map(ConfigFolderProperties.Folder::path)
                            .toList());
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-config-watch");
            thread.setDaemon(true);
            return thread;
        });
        long millis = every.get().toMillis();
        var unused = scheduler.scheduleWithFixedDelay(built::poll, millis, millis, TimeUnit.MILLISECONDS);
        executor = scheduler;
        LOG.info(
                "declaration folders looked at every {}: a new file declares, a removed file removes, a changed "
                        + "file replaces -- except what a registered query uses: {}",
                every.get(),
                folders.values().stream()
                        .map(ConfigFolderProperties.Folder::path)
                        .toList());
    }

    /** The watcher, once the node has started one. */
    public Optional<DeclarationWatcher> watcher() {
        return Optional.ofNullable(watcher);
    }

    /**
     * Where an object came from: {@code file:<path>}, {@code config} (inline in application.yaml), or
     * {@code api} ({@code POST /api/v1/streams}).
     */
    public String origin(DeclarationKind kind, String name) {
        DeclarationWatcher live = watcher;
        Optional<Path> file = live != null
                ? live.fileOf(kind, name)
                : Optional.ofNullable(map(loaded, kind).get(name)).map(DeclarationFile::path);
        if (file.isPresent()) {
            return "file:" + file.get();
        }
        // A lookup or sink can only be declared by configuration; a stream also by the API.
        return kind == DeclarationKind.STREAM && !names(kind).contains(name) ? "api" : "config";
    }

    /** The files in force of one kind: the watcher's once it runs, what start read before. */
    public Map<String, DeclarationFile> files(DeclarationKind kind) {
        DeclarationWatcher live = watcher;
        return live != null ? live.files(kind) : map(loaded, kind);
    }

    /** Resolves {@code ${ENV}} in a file being read: what the watcher and a write read files with. */
    public PlaceholdersResolver placeholders() {
        return placeholders;
    }

    /**
     * The streams whose source is switched off: a stream file with {@code enabled: false}, and every stream
     * whose source names a connection whose file says {@code enabled: false} (ADR-064). The node keeps them
     * declared and bound, and reads nothing from them until they are enabled.
     */
    public Set<String> stoppedStreams(SourceBindingProperties sources) {
        Set<String> stopped = new java.util.TreeSet<>();
        files(DeclarationKind.STREAM).forEach((name, file) -> {
            if (!file.enabled()) {
                stopped.add(name);
            }
        });
        Set<String> offConnections = new java.util.HashSet<>();
        files(DeclarationKind.CONNECTOR).forEach((name, file) -> {
            if (!file.enabled()) {
                offConnections.add(name);
            }
        });
        if (!offConnections.isEmpty()) {
            synchronized (sources.getSources()) {
                sources.getSources().forEach((stream, spec) -> {
                    if (spec.getConnector() != null && offConnections.contains(spec.getConnector())) {
                        stopped.add(stream);
                    }
                });
            }
        }
        return stopped;
    }

    /** Every file not applied as written; empty before the node starts watching. */
    public List<DeclarationWatcher.Problem> problems() {
        DeclarationWatcher live = watcher;
        return live == null ? List.of() : live.problems();
    }

    /** Why an object's file is not in force as written, as {@code PRV-nnnn reason}; empty when it is. */
    public Optional<String> notApplied(DeclarationKind kind, String name) {
        return problems().stream()
                .filter(problem -> problem.kind() == kind && problem.name().equals(name))
                .findFirst()
                .map(problem -> problem.code() + " " + problem.message());
    }

    /** {@code pravaha.config.watch-interval} as in force: a duration, or {@code off}. */
    public String watchInterval() {
        ConfigFolderProperties settings = properties;
        return settings == null
                ? "off"
                : settings.interval().map(Duration::toString).orElse("off");
    }

    /** The folders, for the start-up line and the API. */
    public List<ConfigFolderProperties.Folder> folders() {
        ConfigFolderProperties settings = properties;
        List<ConfigFolderProperties.Folder> folders = new ArrayList<>();
        if (settings != null) {
            for (DeclarationKind kind : DeclarationKind.values()) {
                folders.add(settings.folder(kind));
            }
        }
        return folders;
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService scheduler = executor;
        if (scheduler != null) {
            scheduler.shutdownNow();
            executor = null;
        }
        watcher = null;
    }
}

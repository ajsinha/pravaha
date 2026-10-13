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
package com.ash.messaging.pravaha.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Two roots, and every other place a node keeps something found under them by convention (ADR-064).
 *
 * <pre>
 * pravaha.data.dir    (default data;   $PRAVAHA_HOME/data under a home; none = keep nothing)
 *   registry.journal        pravaha.registry.journal
 *   checkpoints/            pravaha.checkpoint.directory
 *   catalog.journal         pravaha.catalog.journal   (beside the registry journal, as before)
 *   alerts.journal          pravaha.alerts.journal    (beside the registry journal, as before)
 *   identity/identity.journal   pravaha.identity.store   (when pravaha.identity.enabled)
 * pravaha.config.dir  (default config; $PRAVAHA_HOME/conf under a home)
 *   streams/ lookups/ sinks/    pravaha.config.streams-dir, -lookups-dir, -sinks-dir
 *   ai/                     pravaha.ai.dir
 *   connectors/             pravaha.config.connectors-dir
 * pravaha.external-lib.dir (default external_lib; $PRAVAHA_HOME/external_lib under a home)
 *   java/*.jar              on the server's classpath at start (bin/pravaha-server, -Dloader.path)
 *   python/                 on the console's and the CLI's sys.path
 * </pre>
 *
 * <p>Each derived setting stays a setting: a value written for it -- in YAML, as {@code --pravaha.<key>=},
 * or as an environment variable -- wins over the convention, and {@code none} switches that one store
 * off. Resolved here, in one place, for the server (at environment preparation) and the embedded engine.
 * The engine takes no data root unless one is written: a library writing {@code ./data} into whatever
 * process embeds it would be a surprise; a server node keeps its state by default.
 */
public final class NodeLayout {

    public static final String DATA_DIR = "pravaha.data.dir";
    public static final String CONFIG_DIR = "pravaha.config.dir";

    /** The value that switches a root, or one derived store, off. */
    public static final String NONE = "none";

    /** A server node's data root when none is written. */
    public static final String DEFAULT_DATA_DIR = "data";

    /** A server node's configuration root when none is written. */
    public static final String DEFAULT_CONFIG_DIR = "config";

    /** The third root: code a deployment adds. {@code java/} jars and {@code python/} modules. */
    public static final String EXTERNAL_LIB_DIR = "pravaha.external-lib.dir";

    /** Its default, relative to the working directory; {@code $PRAVAHA_HOME/external_lib} under a home. */
    public static final String DEFAULT_EXTERNAL_LIB_DIR = "external_lib";

    /** Set by bin/pravaha-server: the jars it put on the classpath, comma-separated. */
    public static final String EXTERNAL_LIB_LOADED = "pravaha.external-lib.loaded";

    /** One setting found under a root by convention. */
    private record Convention(String key, String label, boolean data, String relative) {

        boolean besideRegistry() {
            return key.equals("pravaha.catalog.journal") || key.equals("pravaha.alerts.journal");
        }
    }

    private static final List<Convention> CONVENTIONS = List.of(
            new Convention("pravaha.registry.journal", "registry journal", true, "registry.journal"),
            new Convention("pravaha.checkpoint.directory", "checkpoints", true, "checkpoints"),
            new Convention("pravaha.catalog.journal", "catalogue", true, "catalog.journal"),
            new Convention("pravaha.alerts.journal", "alerts", true, "alerts.journal"),
            new Convention("pravaha.identity.store", "identity", true, "identity/identity.journal"),
            new Convention("pravaha.ai.dir", "ai", false, "ai"));

    private NodeLayout() {}

    /**
     * The data root in force: what {@code pravaha.data.dir} says, else {@code defaultDataDir}; empty when
     * it is {@code none}, or unset with no default (the embedded engine).
     */
    public static Optional<Path> dataDir(Function<String, @Nullable String> property, @Nullable String defaultDataDir) {
        return root(property.apply(DATA_DIR), defaultDataDir);
    }

    /** The configuration root in force: {@code pravaha.config.dir}, else {@code config}. */
    public static Path configDir(Function<String, @Nullable String> property) {
        return root(property.apply(CONFIG_DIR), DEFAULT_CONFIG_DIR).orElse(Path.of(DEFAULT_CONFIG_DIR));
    }

    /** The external code root in force: {@code pravaha.external-lib.dir}, else {@code external_lib}. */
    public static Path externalLibDir(Function<String, @Nullable String> property) {
        return root(property.apply(EXTERNAL_LIB_DIR), DEFAULT_EXTERNAL_LIB_DIR)
                .orElse(Path.of(DEFAULT_EXTERNAL_LIB_DIR));
    }

    /**
     * The jars in {@code <external-lib>/java/}: top level only, {@code *.jar}, sorted -- the order
     * bin/pravaha-server puts them on the classpath in. Empty when the folder is missing.
     */
    public static List<Path> externalJars(Path externalLib) {
        Path java = externalLib.resolve("java");
        if (!Files.isDirectory(java)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> entries = Files.list(java)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The start-up line about {@code <external-lib>/java/}: which jars are on the classpath, and any in the
     * folder that are not -- because the node was started without bin/pravaha-server, or the jar arrived
     * after start. A JVM cannot safely swap jars, so a new one needs a restart.
     */
    public static String describeExternalJars(Function<String, @Nullable String> property) {
        Path root = externalLibDir(property);
        List<Path> present = externalJars(root);
        String loaded = property.apply(EXTERNAL_LIB_LOADED);
        java.util.Set<String> onClasspath = new java.util.TreeSet<>();
        if (loaded != null && !loaded.isBlank()) {
            for (String jar : loaded.split(",")) {
                if (!jar.isBlank()) {
                    onClasspath.add(Path.of(jar.strip()).getFileName().toString());
                }
            }
        }
        List<String> missing = present.stream()
                .map(path -> path.getFileName().toString())
                .filter(name -> !onClasspath.contains(name))
                .toList();
        String line = "external_lib/java (" + shown(root.resolve("java")) + "): "
                + (onClasspath.isEmpty() ? "no jars on the classpath" : onClasspath + " on the classpath")
                + "; read at start only -- a new jar needs a restart";
        return missing.isEmpty()
                ? line
                : line + ". NOT on the classpath: " + missing + " -- start the node with bin/pravaha-server, which "
                        + "puts every jar there on -Dloader.path, or restart it to pick up a jar added since";
    }

    private static Optional<Path> root(@Nullable String written, @Nullable String fallback) {
        String value = written == null || written.isBlank() ? fallback : written.strip();
        if (value == null || value.isBlank() || value.equalsIgnoreCase(NONE)) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value));
    }

    /**
     * The settings the layout supplies: each derived one that is unset or blank, resolved under its root,
     * and each written as {@code none} turned into the empty value that means off. A setting written with
     * any other value is not touched.
     */
    public static Map<String, String> derived(
            Function<String, @Nullable String> property, @Nullable String defaultDataDir) {
        Map<String, String> derived = new LinkedHashMap<>();
        Optional<Path> data = dataDir(property, defaultDataDir);
        Path config = configDir(property);
        for (Convention convention : CONVENTIONS) {
            String written = property.apply(convention.key());
            if (written != null && written.strip().equalsIgnoreCase(NONE)) {
                derived.put(convention.key(), "");
                continue;
            }
            if (written != null && !written.isBlank()) {
                continue;
            }
            if (convention.key().equals("pravaha.identity.store") && !enabled(property, "pravaha.identity.enabled")) {
                continue;
            }
            if (convention.besideRegistry()) {
                // Already found beside the registry journal when unset, wherever that is -- so a node that
                // names its registry journal elsewhere keeps its grants and alerts where they always were.
                continue;
            }
            if (convention.data()) {
                data.ifPresent(root -> derived.put(
                        convention.key(), root.resolve(convention.relative()).toString()));
            } else {
                derived.put(
                        convention.key(), config.resolve(convention.relative()).toString());
            }
        }
        return derived;
    }

    /**
     * Creates the data root, and the identity store's folder when identity is on, so the stores the
     * convention puts there can be opened; refuses ({@code PRV-1066}) a root that cannot be created or
     * written. Does nothing when the root is {@code none}.
     */
    public static void prepare(Function<String, @Nullable String> property, @Nullable String defaultDataDir) {
        Optional<Path> data = dataDir(property, defaultDataDir);
        if (data.isEmpty()) {
            return;
        }
        Path root = data.get();
        try {
            Files.createDirectories(root);
            if (enabled(property, "pravaha.identity.enabled")) {
                String store = property.apply("pravaha.identity.store");
                Path parent = store == null || store.isBlank()
                        ? root.resolve("identity")
                        : Path.of(store.strip()).toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw unusable(root, "cannot be created (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        if (!Files.isWritable(root)) {
            throw unusable(root, "is not writable by this process");
        }
    }

    private static PravahaException unusable(Path root, String why) {
        return new PravahaException(
                ConfigErrors.DATA_DIR_UNUSABLE,
                DATA_DIR + " is " + root.toAbsolutePath() + ", which " + why + ". The registry journal, checkpoints "
                        + "and journals are kept beneath it. Give the directory to the user the node runs as, set "
                        + DATA_DIR + " elsewhere, or set it to none to keep nothing across a restart.");
    }

    /** The one start-up line naming where everything lives. */
    public static String describe(Function<String, @Nullable String> property) {
        StringBuilder line = new StringBuilder("layout: data=");
        Optional<Path> data = dataDir(property, null);
        line.append(data.map(NodeLayout::shown).orElse("none (nothing is kept across a restart)"));
        for (Convention convention : CONVENTIONS) {
            if (!convention.data()) {
                continue;
            }
            line.append(", ").append(convention.label()).append('=');
            String switchedBy =
                    switch (convention.key()) {
                        case "pravaha.catalog.journal" -> "pravaha.catalog.enabled";
                        case "pravaha.identity.store" -> "pravaha.identity.enabled";
                        default -> null;
                    };
            if (switchedBy != null && !enabled(property, switchedBy)) {
                line.append("off (").append(switchedBy).append(')');
                continue;
            }
            String value = property.apply(convention.key());
            String registry = property.apply("pravaha.registry.journal");
            if ((value == null || value.isBlank())
                    && convention.besideRegistry()
                    && registry != null
                    && !registry.isBlank()) {
                Path parent = Path.of(registry.strip()).toAbsolutePath().getParent();
                value = parent == null
                        ? null
                        : parent.resolve(convention.relative()).toString();
            }
            line.append(value == null || value.isBlank() ? "none" : shown(Path.of(value.strip())));
        }
        Path config = configDir(property);
        line.append("; config=").append(shown(config));
        for (String folder : List.of("streams", "lookups", "sinks")) {
            String written = property.apply("pravaha.config." + folder + "-dir");
            line.append(", ")
                    .append(folder)
                    .append('=')
                    .append(shown(
                            written == null || written.isBlank() ? config.resolve(folder) : Path.of(written.strip())));
        }
        String ai = property.apply("pravaha.ai.dir");
        line.append(", ai=").append(shown(ai == null || ai.isBlank() ? config.resolve("ai") : Path.of(ai.strip())));
        String connectors = property.apply("pravaha.config.connectors-dir");
        line.append(", connectors=")
                .append(shown(
                        connectors == null || connectors.isBlank()
                                ? config.resolve("connectors")
                                : Path.of(connectors.strip())));
        line.append("; external_lib=").append(shown(externalLibDir(property)));
        return line.toString();
    }

    private static String shown(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static boolean enabled(Function<String, @Nullable String> property, String key) {
        String value = property.apply(key);
        return value != null && value.strip().toLowerCase(Locale.ROOT).equals("true");
    }
}

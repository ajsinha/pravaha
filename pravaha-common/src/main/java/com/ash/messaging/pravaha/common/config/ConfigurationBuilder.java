/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * Assembles a {@link Configuration} from layered sources.
 *
 * <p>Precedence runs low to high: defaults, then files in the order given, then each file's
 * {@code .local} overlay, then system properties, then environment, then command line, then anything
 * set programmatically. A higher-ranked source always wins, regardless of the order the sources were
 * added -- so a caller cannot accidentally let a config file override a command-line flag by
 * registering it later.
 *
 * <p>Sources are read eagerly, but {@code ${...}} references are resolved once at {@link #build()},
 * against the merged result. That is what lets a value in a file refer to a key supplied by the
 * environment.
 */
public final class ConfigurationBuilder {

    private final Map<String, ConfigValue> entries = new LinkedHashMap<>();

    ConfigurationBuilder() {}

    // ------------------------------------------------------------------ sources

    /** Compiled-in defaults. Lowest precedence; anything at all overrides them. */
    public ConfigurationBuilder addDefaults(Map<String, String> defaults) {
        defaults.forEach((k, v) -> put(new ConfigValue(k, v, ConfigSource.DEFAULT, "built-in")));
        return this;
    }

    /**
     * Adds a configuration file, and its {@code <name>.local.<ext>} sibling if one exists.
     *
     * <p>The overlay is a deployment-local layer for values a machine needs in order to start but
     * that must not be committed -- a signing secret being the usual case. It is git-ignored, sets
     * only the keys it names, and its absence changes nothing.
     *
     * @throws ConfigurationException if the file is missing, unreadable, malformed, or has an
     *     extension no registered format handles
     */
    public ConfigurationBuilder addFile(Path file) {
        return addFile(file, true);
    }

    /** As {@link #addFile(Path)}, but tolerates the file not existing. */
    public ConfigurationBuilder addOptionalFile(Path file) {
        return addFile(file, false);
    }

    private ConfigurationBuilder addFile(Path file, boolean required) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            if (required) {
                throw new ConfigurationException(
                        ConfigErrors.FILE_UNREADABLE, "configuration file does not exist: " + file.toAbsolutePath());
            }
            return this;
        }
        readInto(file, ConfigSource.FILE);
        localOverlayFor(file).ifPresent(overlay -> readInto(overlay, ConfigSource.LOCAL_OVERLAY));
        return this;
    }

    private void readInto(Path file, ConfigSource source) {
        ConfigFormat format = ConfigFormat.forFile(file)
                .orElseThrow(() -> new ConfigurationException(
                        ConfigErrors.FILE_MALFORMED,
                        "no configuration format handles " + file + "; registered extensions are "
                                + ConfigFormats.supportedExtensions()));
        String origin = file.toAbsolutePath().toString();
        format.parse(file).forEach((k, v) -> put(new ConfigValue(k, v, source, origin)));
    }

    private static java.util.Optional<Path> localOverlayFor(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return java.util.Optional.empty();
        }
        Path overlay = file.resolveSibling(name.substring(0, dot) + ".local" + name.substring(dot));
        return Files.exists(overlay) ? java.util.Optional.of(overlay) : java.util.Optional.empty();
    }

    /**
     * Adds environment variables whose names begin with {@code prefix}.
     *
     * <p>{@code PRAVAHA_RUNTIME_LANES} becomes {@code pravaha.runtime.lanes}. The mapping is
     * lossy in one direction only -- a key containing an underscore cannot be expressed as an
     * environment variable -- so keys are conventionally dotted and hyphenated, never underscored.
     */
    public ConfigurationBuilder addEnvironment(String prefix) {
        return addEnvironment(prefix, System::getenv);
    }

    /** Testable form of {@link #addEnvironment(String)}. */
    ConfigurationBuilder addEnvironment(String prefix, Supplier<Map<String, String>> environment) {
        String envPrefix = prefix.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        environment.get().forEach((name, value) -> {
            if (name.toUpperCase(Locale.ROOT).startsWith(envPrefix)) {
                String key = name.toLowerCase(Locale.ROOT).replace('_', '.');
                put(new ConfigValue(key, value, ConfigSource.ENVIRONMENT, name));
            }
        });
        return this;
    }

    /** Adds JVM system properties beginning with {@code prefix}. */
    public ConfigurationBuilder addSystemProperties(String prefix) {
        System.getProperties().stringPropertyNames().forEach(name -> {
            if (name.startsWith(prefix)) {
                put(new ConfigValue(name, System.getProperty(name), ConfigSource.SYSTEM_PROPERTY, "-D" + name));
            }
        });
        return this;
    }

    /**
     * Adds {@code --key=value} arguments.
     *
     * <p>Anything not in that form is ignored rather than rejected: the same argument vector usually
     * carries a subcommand and positional arguments that are none of this parser's business.
     */
    public ConfigurationBuilder addCommandLine(String... args) {
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq < 3) {
                continue;
            }
            String key = arg.substring(2, eq).strip();
            if (!key.isEmpty()) {
                put(new ConfigValue(key, arg.substring(eq + 1), ConfigSource.COMMAND_LINE, "--" + key));
            }
        }
        return this;
    }

    /** Sets a value in code. Outranks every external source, because the caller asked explicitly. */
    public ConfigurationBuilder set(String key, String value) {
        put(new ConfigValue(key, value, ConfigSource.PROGRAMMATIC, "set in code"));
        return this;
    }

    public ConfigurationBuilder set(String key, Object value) {
        return set(key, String.valueOf(value));
    }

    // ------------------------------------------------------------------ merge and build

    private void put(ConfigValue candidate) {
        ConfigValue existing = entries.get(candidate.key());
        // Precedence is by source rank, not insertion order, so registering sources in an unusual
        // order cannot silently invert which one wins. Equal rank means last-one-wins, which is
        // what makes "rightmost file wins" work.
        if (existing == null || !existing.source().outranks(candidate.source())) {
            entries.put(candidate.key(), candidate);
        }
    }

    /**
     * Resolves references and produces the configuration.
     *
     * @throws ConfigurationException if a reference cannot be resolved or is circular. Failing here
     *     is deliberate: a literal {@code ${DB_PASSWORD}} reaching a connection string is worse than
     *     not starting.
     */
    public Configuration build() {
        return new Configuration(ConfigResolver.resolveAll(entries));
    }
}

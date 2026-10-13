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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.config.ConfigurationCheck;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

/**
 * One declaration file, read: {@code <name>.yaml} in a streams, lookups or sinks folder (ADR-064).
 *
 * <p>The file's content is exactly what {@code pravaha.<kind>s.<name>} accepts inline -- plus, for a
 * stream, a {@code source:} block holding what {@code pravaha.sources.<name>} accepts -- and it is bound
 * by Spring's own binder into the same classes the inline blocks bind into, after Spring's own YAML
 * flattening. So a unitless {@code out-of-orderness: 60} is sixty seconds here as it is inline, an
 * option key with a dot in it means the same thing in both, and {@code ${ENV}} / {@code ${ENV:default}}
 * resolve against the same environment. Where a file is stricter than an inline block it says so: an
 * unknown key is refused (inline, Spring ignores one), and a literal secret is refused.
 *
 * @param canonical the file's settings, sorted, before placeholders are resolved: two files that differ
 *     only in comments, order or spacing are the same declaration
 * @param stream a stream file's declaration, else null
 * @param source a stream file's {@code source:} block, or a lookup file's binding, else null
 * @param sink a sink file's binding, else null
 */
public record DeclarationFile(
        DeclarationKind kind,
        String name,
        Path path,
        String canonical,
        StreamDeclarationProperties.@Nullable Declaration stream,
        SourceBindingProperties.@Nullable Spec source,
        SinkBindingProperties.@Nullable Spec sink) {

    private static final Logger LOG = LoggerFactory.getLogger(DeclarationFile.class);

    /** The names an inline block binds without brackets: what Spring keeps of a map key (CFG-3). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    /** A setting that holds a secret, by the rule NoSecretsInConfigurationTest applies to the repository. */
    private static final Pattern SECRET_KEY =
            Pattern.compile(".*(password|secret|token|api[_-]?key).*", Pattern.CASE_INSENSITIVE);

    /** What may stand in a secret setting: nothing, a placeholder, or a flag, a count or a duration. */
    private static final Pattern SAFE_VALUE =
            Pattern.compile("|\\$\\{[^}]*}|true|false|[0-9]+|[0-9]+(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    /** A stream file: a stream declaration, and where its rows come from. */
    public static class StreamFile extends StreamDeclarationProperties.Declaration {

        private SourceBindingProperties.@Nullable Spec source;

        public SourceBindingProperties.@Nullable Spec getSource() {
            return source;
        }

        public void setSource(SourceBindingProperties.@Nullable Spec source) {
            this.source = source;
        }
    }

    /** {@code txn.yaml} and {@code txn.yml} -- not hidden, regular files -- sorted; anything else is skipped. */
    public static List<Path> list(Path dir) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                String file = String.valueOf(entry.getFileName());
                if (file.startsWith(".")) {
                    continue;
                }
                if (Files.isRegularFile(entry) && (file.endsWith(".yaml") || file.endsWith(".yml"))) {
                    found.add(entry);
                } else {
                    LOG.debug("{}: not a .yaml or .yml file, so not a declaration; ignored", entry);
                }
            }
        } catch (IOException e) {
            throw new PravahaException(
                    ConfigErrors.FILE_UNREADABLE,
                    "the folder " + dir + " cannot be listed (" + e.getClass().getSimpleName() + ")");
        }
        return found;
    }

    /** The object's name: the file's, without its extension. */
    public static String nameOf(Path file) {
        String fileName = String.valueOf(file.getFileName());
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    /** Reads, checks and binds one file; refuses it, coded and naming it, if it cannot be used. */
    public static DeclarationFile read(DeclarationKind kind, Path file, PlaceholdersResolver placeholders) {
        String name = nameOf(file);
        if (!NAME.matcher(name).matches()) {
            throw new PravahaException(
                    ConfigErrors.FILE_NAME_INVALID,
                    file + ": the file name is the " + kind.label() + "'s name, and '" + name + "' is not one a "
                            + kind.label() + " can have without brackets. Use letters, digits, '_' and '-' (at "
                            + "most 128), or declare it inline under " + kind.inlineBlock() + " as \"[" + name
                            + "]\".");
        }
        Map<String, String> raw = settingsOf(file);
        refuseLiteralSecrets(file, raw);
        Map<String, Object> prefixed = new LinkedHashMap<>();
        raw.forEach((key, value) -> prefixed.put("file." + key, value));
        Binder binder = new Binder(
                List.of(ConfigurationPropertySource.from(new MapPropertySource(file.toString(), prefixed))),
                placeholders);
        BindHandler strict = new NoUnboundElementsBindHandler(BindHandler.DEFAULT);
        String canonical = new TreeMap<>(raw).toString();
        try {
            return switch (kind) {
                case STREAM -> {
                    StreamFile bound = binder.bind("file", Bindable.ofInstance(new StreamFile()), strict)
                            .orElseGet(StreamFile::new);
                    SourceBindingProperties.Spec source = bound.getSource();
                    if (source != null) {
                        requirePlugin(file, "source.plugin", source.getPlugin(), source.getConnector());
                        refuseDivergentSchemas(file, bound, source);
                    }
                    yield new DeclarationFile(kind, name, file, canonical, bound, source, null);
                }
                case LOOKUP -> {
                    SourceBindingProperties.Spec bound = binder.bind(
                                    "file", Bindable.ofInstance(new SourceBindingProperties.Spec()), strict)
                            .orElseGet(SourceBindingProperties.Spec::new);
                    requirePlugin(file, "plugin", bound.getPlugin(), bound.getConnector());
                    yield new DeclarationFile(kind, name, file, canonical, null, bound, null);
                }
                case CONNECTOR -> {
                    SourceBindingProperties.Spec bound = binder.bind(
                                    "file", Bindable.ofInstance(new SourceBindingProperties.Spec()), strict)
                            .orElseGet(SourceBindingProperties.Spec::new);
                    if (bound.getConnector() != null) {
                        throw new PravahaException(
                                ConfigErrors.FILE_INVALID,
                                file + ": a connection names its plugin; it cannot name another connection.");
                    }
                    requirePlugin(file, "plugin", bound.getPlugin(), null);
                    yield new DeclarationFile(kind, name, file, canonical, null, bound, null);
                }
                case SINK -> {
                    SinkBindingProperties.Spec bound = binder.bind(
                                    "file", Bindable.ofInstance(new SinkBindingProperties.Spec()), strict)
                            .orElseGet(SinkBindingProperties.Spec::new);
                    requirePlugin(file, "plugin", bound.getPlugin(), bound.getConnector());
                    yield new DeclarationFile(kind, name, file, canonical, null, null, bound);
                }
            };
        } catch (BindException e) {
            throw refusedBinding(kind, file, e, raw.keySet());
        }
    }

    /** The file as Spring flattens a YAML document: {@code source.options.path} and so on. */
    private static Map<String, String> settingsOf(Path file) {
        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file));
        } catch (IOException | RuntimeException e) {
            String why = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            throw new PravahaException(ConfigErrors.FILE_INVALID, file + ": not readable YAML (" + why.strip() + ").");
        }
        if (documents.size() > 1) {
            throw new PravahaException(
                    ConfigErrors.FILE_INVALID,
                    file + ": holds " + documents.size() + " YAML documents; a declaration file holds one.");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        if (!documents.isEmpty() && documents.get(0) instanceof EnumerablePropertySource<?> document) {
            for (String key : document.getPropertyNames()) {
                Object value = document.getProperty(key);
                settings.put(key, value == null ? "" : String.valueOf(value));
            }
        }
        if (settings.isEmpty()) {
            throw new PravahaException(
                    ConfigErrors.FILE_INVALID,
                    file + ": is empty. A declaration file holds the settings "
                            + "pravaha.<streams|lookups|sinks>.<name> would hold inline.");
        }
        return settings;
    }

    /**
     * Refuses a password, secret, token or key written into the file, naming the setting and never the
     * value -- the AI layer's rule for its provider files (ADR-063), on the key names the repository's
     * own NoSecretsInConfigurationTest treats as secrets.
     */
    /** Whether a setting holds a secret, by its last segment; one naming where a secret is does not. */
    public static boolean isSecretKey(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1).replaceAll("\\[\\d+]$", "");
        String lower = last.toLowerCase(Locale.ROOT);
        return SECRET_KEY.matcher(last).matches() && !lower.endsWith("-file") && !lower.endsWith("-env");
    }

    private static void refuseLiteralSecrets(Path file, Map<String, String> settings) {
        settings.forEach((key, value) -> {
            if (!isSecretKey(key)) {
                return;
            }
            if (!SAFE_VALUE.matcher(value.strip()).matches()) {
                throw new PravahaException(
                        ConfigErrors.FILE_LITERAL_SECRET,
                        file + ": " + key + " holds a literal value. A secret is never written into a "
                                + "declaration file: write it as ${ENV_NAME} and set the variable where the node "
                                + "runs.");
            }
        });
    }

    private static void requirePlugin(Path file, String key, @Nullable String plugin, @Nullable String connector) {
        if ((plugin == null || plugin.isBlank()) && connector == null) {
            throw new PravahaException(
                    ConfigErrors.MISSING_REQUIRED,
                    file + ": " + key + " is missing. A binding names the plugin that reads or writes it -- "
                            + "GET /api/v1/plugins lists the ones this node can load -- or a named connection "
                            + "(connector:) that does.");
        }
    }

    /** CFG-8's refusal, for the two schemas one stream file can hold. */
    private static void refuseDivergentSchemas(
            Path file, StreamDeclarationProperties.Declaration stream, SourceBindingProperties.Spec source) {
        String fromBinding = source.getOptions().get("schema");
        String fromDeclaration = stream.getSchema();
        if (fromBinding != null
                && fromDeclaration != null
                && !ConfigurationCheck.sameSchema(fromBinding, fromDeclaration)) {
            throw new PravahaException(
                    ConfigErrors.CONTRADICTION,
                    file + ": one stream, two schemas -- schema is \"" + fromDeclaration
                            + "\" and source.options.schema is \"" + fromBinding + "\". Write the schema once, "
                            + "at the top of the file, and leave the source's option out.");
        }
    }

    private static PravahaException refusedBinding(
            DeclarationKind kind, Path file, BindException e, java.util.Set<String> written) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
            if (cause instanceof UnboundConfigurationPropertiesException unbound) {
                List<String> keys = unbound.getUnboundProperties().stream()
                        .map(ConfigurationProperty::getName)
                        // As written, not as Spring canonicalised it: event_tme, not eventtme.
                        .map(name -> written.stream()
                                .filter(key ->
                                        org.springframework.boot.context.properties.source.ConfigurationPropertyName
                                                .adapt("file." + key, '.')
                                                .equals(name))
                                .findFirst()
                                .orElse(name.toString().substring("file.".length())))
                        .sorted()
                        .toList();
                return new PravahaException(
                        ConfigErrors.FILE_INVALID,
                        file + ": unknown setting" + (keys.size() == 1 ? " " : "s ") + keys + ". A " + kind.label()
                                + " file accepts " + accepted(kind) + ".");
            }
        }
        String property = e.getProperty() == null
                ? e.getName().toString()
                : e.getProperty().getName().toString();
        String setting = property.startsWith("file.") ? property.substring("file.".length()) : property;
        String why = String.valueOf(cause.getMessage())
                .lines()
                .findFirst()
                .orElse("")
                .strip();
        ErrorCode code = why.contains("Could not resolve placeholder")
                ? ConfigErrors.UNRESOLVED_REFERENCE
                : ConfigErrors.FILE_INVALID;
        return new PravahaException(code, file + ": " + setting + " cannot be read: " + why);
    }

    private static String accepted(DeclarationKind kind) {
        return switch (kind) {
            case STREAM ->
                "schema, event-time, out-of-orderness, allowed-lateness and a source: block "
                        + "(plugin or connector, options)";
            case LOOKUP, SINK -> "plugin (or connector) and options";
            case CONNECTOR -> "plugin and options";
        };
    }
}

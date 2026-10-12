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
package com.ash.messaging.pravaha.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code pravaha.ai.dir}, one provider per {@code *.yaml} file. A file that cannot be used is
 * reported (file name to reason) and skipped; the others still load. A key must be an {@code ${ENV_NAME}}
 * placeholder: <b>a literal key in a file is refused</b>, and so is a literal secret in any other
 * credential setting ({@code aws-secret-access-key}, {@code client-secret}, {@code proxy.password}, the TLS
 * store passwords, a credential header). A {@code *.yaml.example} is not read.
 */
public final class ProviderConfigLoader {

    /**
     * What a folder held.
     *
     * @param providers the files that could be used
     * @param errors file name to why it could not
     */
    public record Loaded(List<ProviderConfig> providers, Map<String, String> errors) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENV = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?}$");
    private static final Pattern EMBEDDED = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern CREDENTIAL_HEADER = Pattern.compile("(?i).*(auth|key|token|secret|cookie).*");
    private static final Set<String> TOP_LEVEL =
            Set.of("name", "type", "enabled", "base-url", "api-key", "timeout", "retries", "default-model", "models");
    private static final List<String> SECRET_SETTINGS =
            List.of("proxy.password", "tls.truststore.password", "tls.keystore.password");

    private ProviderConfigLoader() {}

    /** Every provider file in {@code dir}; a missing folder is no providers and no errors. */
    public static Loaded load(@Nullable Path dir, Function<String, @Nullable String> env) {
        List<ProviderConfig> ok = new ArrayList<>();
        Map<String, String> errors = new TreeMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return new Loaded(ok, errors);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(ProviderConfigLoader::isProviderFile).sorted().toList();
        } catch (IOException e) {
            errors.put(dir.toString(), "cannot read the folder (" + e.getClass().getSimpleName() + ")");
            return new Loaded(ok, errors);
        }
        Set<String> seen = new HashSet<>();
        for (Path f : files) {
            String file = String.valueOf(f.getFileName());
            try {
                ProviderConfig c = parse(f, env);
                if (!seen.add(c.name())) {
                    errors.put(file, "another file already defines provider '" + c.name() + "'");
                } else {
                    ok.add(c);
                }
            } catch (LlmException e) {
                errors.put(file, e.detail());
            }
        }
        return new Loaded(ok, errors);
    }

    /** Whether a path is one the loader reads: {@code *.yaml} or {@code *.yml}, not hidden. */
    public static boolean isProviderFile(Path p) {
        String n = String.valueOf(p.getFileName());
        return Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml")) && !n.startsWith(".");
    }

    static ProviderConfig parse(Path file, Function<String, @Nullable String> env) {
        String fileName = String.valueOf(file.getFileName());
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        JsonNode root;
        try {
            Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
            root = JSON.valueToTree(doc);
        } catch (IOException | RuntimeException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "not readable YAML");
        }
        return fromNode(base, root, env);
    }

    /** One provider from its parsed block (a file's content, or the map {@code LLMProviders.fromSettings} is given); {@code base} is the name when the block gives none. */
    public static ProviderConfig fromNode(
            String base, @Nullable JsonNode root, Function<String, @Nullable String> env) {
        if (root == null || !root.isObject()) {
            throw new LlmException(LlmException.Kind.CONFIG, "the file is empty or not a mapping");
        }
        String name = text(root, "name", base);
        if (!NAME.matcher(name).matches()) {
            throw new LlmException(
                    LlmException.Kind.CONFIG, "name '" + name + "' must be letters, digits, '.', '_' or '-'");
        }
        String type = text(root, "type", "");
        if (type.isEmpty()) {
            throw new LlmException(LlmException.Kind.CONFIG, "type is missing (openai, anthropic, mock, ...)");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        root.properties().forEach(e -> {
            if (!TOP_LEVEL.contains(e.getKey())) {
                flatten(e.getKey(), e.getValue(), settings);
            }
        });
        settings.replaceAll((k, v) -> k.startsWith("header.") ? header(k.substring(7), v, env) : v);
        List<ProviderConfig.ModelConfig> models = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode m : root.path("models")) {
            String mn = text(m, "name", "");
            if (!NAME.matcher(mn).matches()) {
                throw new LlmException(
                        LlmException.Kind.CONFIG, "a model needs a logical name (letters, digits, '.', '_', '-')");
            }
            if (!names.add(mn)) {
                throw new LlmException(LlmException.Kind.CONFIG, "model '" + mn + "' is listed twice");
            }
            Set<Capability> caps = new LinkedHashSet<>();
            for (JsonNode c : m.path("capabilities")) {
                try {
                    caps.add(Capability.of(c.asText()));
                } catch (IllegalArgumentException e) {
                    throw new LlmException(
                            LlmException.Kind.CONFIG, "model '" + mn + "': unknown capability '" + c.asText() + "'");
                }
            }
            models.add(new ProviderConfig.ModelConfig(
                    mn,
                    text(m, "id", mn),
                    text(m, "kind", "chat"),
                    m.hasNonNull("max-tokens") ? m.get("max-tokens").asInt() : null,
                    m.hasNonNull("temperature") ? m.get("temperature").asDouble() : null,
                    caps,
                    m.path("cost-per-1k-input").asDouble(0),
                    m.path("cost-per-1k-output").asDouble(0)));
        }
        List<String> secrets = new ArrayList<>(LlmCatalog.CREDENTIAL_SETTINGS);
        secrets.addAll(SECRET_SETTINGS);
        for (String k : secrets) {
            String raw = settings.getOrDefault(k, "");
            if (!raw.isBlank()) {
                if (!ENV.matcher(raw.strip()).matches()) {
                    throw new LlmException(
                            LlmException.Kind.CONFIG,
                            k + " must be an ${ENV_NAME} placeholder; secrets are never written into a file");
                }
                settings.put(k, resolve(raw, env));
            }
        }
        LlmCatalog.Applied a = LlmCatalog.applyDefaults(
                type,
                settings,
                models,
                text(root, "base-url", ""),
                resolveKey(text(root, "api-key", ""), env),
                text(root, "default-model", ""),
                env);
        return new ProviderConfig(
                name,
                type,
                root.path("enabled").asBoolean(true),
                a.baseUrl(),
                a.apiKey(),
                duration(text(root, "timeout", "")),
                root.path("retries").asInt(0),
                a.defaultModel(),
                settings,
                a.models());
    }

    /** {@code ${NAME}} or {@code ${NAME:-fallback}} read from the environment; anything else is a literal and refused. */
    public static String resolveKey(String raw, Function<String, @Nullable String> env) {
        if (raw.isEmpty()) {
            return "";
        }
        if (!ENV.matcher(raw.strip()).matches()) {
            throw new LlmException(
                    LlmException.Kind.CONFIG,
                    "api-key must be an ${ENV_NAME} placeholder; keys are never written into a file");
        }
        return resolve(raw, env);
    }

    private static String resolve(String raw, Function<String, @Nullable String> env) {
        Matcher m = ENV.matcher(raw.strip());
        if (!m.matches()) {
            return "";
        }
        String v = env.apply(String.valueOf(m.group(1)));
        String fallback = m.group(2);
        return v == null || v.isBlank() ? (fallback == null ? "" : fallback) : v.strip();
    }

    /** A {@code header.<Name>} value: {@code ${ENV}} placeholders resolved; a literal credential header refused. */
    static String header(String name, String raw, Function<String, @Nullable String> env) {
        if (!raw.contains("${")) {
            if (CREDENTIAL_HEADER.matcher(name).matches() && !raw.isBlank()) {
                throw new LlmException(
                        LlmException.Kind.CONFIG,
                        "header " + name
                                + " must use an ${ENV_NAME} placeholder; credentials are never written into a file");
            }
            return raw;
        }
        return EMBEDDED.matcher(raw).replaceAll(m -> {
            String v = env.apply(String.valueOf(m.group(1)));
            String fallback = m.group(2);
            return Matcher.quoteReplacement(v != null && !v.isBlank() ? v.strip() : fallback == null ? "" : fallback);
        });
    }

    static @Nullable Duration duration(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            String t = s.strip().toLowerCase(Locale.ROOT);
            if (t.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(t.substring(0, t.length() - 2).strip()));
            }
            if (t.endsWith("s")) {
                return Duration.ofSeconds(
                        Long.parseLong(t.substring(0, t.length() - 1).strip()));
            }
            if (t.endsWith("m")) {
                return Duration.ofMinutes(
                        Long.parseLong(t.substring(0, t.length() - 1).strip()));
            }
            return Duration.ofSeconds(Long.parseLong(t));
        } catch (NumberFormatException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "timeout '" + s + "' is not a duration such as 30s");
        }
    }

    static void flatten(String prefix, JsonNode v, Map<String, String> out) {
        if (v.isObject()) {
            v.properties().forEach(e -> flatten(prefix + "." + e.getKey(), e.getValue(), out));
        } else if (v.isArray()) {
            for (int i = 0; i < v.size(); i++) {
                flatten(prefix + "." + i, v.get(i), out);
            }
        } else if (v.isValueNode() && !v.isNull()) {
            out.put(prefix, v.asText());
        }
    }

    private static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank()
                ? dflt
                : v.asText().strip();
    }
}

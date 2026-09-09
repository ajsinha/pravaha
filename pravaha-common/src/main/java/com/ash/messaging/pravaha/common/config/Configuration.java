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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * An immutable, fully-resolved configuration.
 *
 * <p><strong>Not a singleton.</strong> The Python configurator this is modelled on was one, and that
 * would be wrong here: embedded mode runs several engines in a single JVM, and {@code @PravahaTest}
 * gives every test its own. A process-wide instance would make both impossible and make tests
 * order-dependent. Each engine owns its own {@code Configuration}.
 *
 * <p>Values are resolved and validated when the configuration is built, not when a key is first
 * read. Starting successfully and then failing on the first cache miss because {@code lanes} said
 * "sixteen" is precisely the failure mode this design avoids.
 *
 * <p>Instances are safe to share across threads; there is no mutable state and no reload behind the
 * caller's back. Reloading produces a <em>new</em> instance that the engine adopts deliberately
 * (design section 18.8: performance may auto-tune, semantics may not).
 */
public final class Configuration {

    private final NavigableMap<String, ConfigValue> entries;

    Configuration(Map<String, ConfigValue> entries) {
        this.entries = java.util.Collections.unmodifiableNavigableMap(new TreeMap<>(entries));
    }

    /** An empty configuration. Useful as a starting point and in tests. */
    public static Configuration empty() {
        return new Configuration(Map.of());
    }

    public static ConfigurationBuilder builder() {
        return new ConfigurationBuilder();
    }

    // ------------------------------------------------------------------ presence and provenance

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public Set<String> keys() {
        return entries.keySet();
    }

    public int size() {
        return entries.size();
    }

    /** Where this key's value came from, or empty if it is not set. */
    public Optional<ConfigSource> sourceOf(String key) {
        return Optional.ofNullable(entries.get(key)).map(ConfigValue::source);
    }

    /** The full entry, including the specific origin. */
    public Optional<ConfigValue> entry(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * A view of every key under {@code prefix}, with the prefix stripped.
     *
     * <p>Lets a plugin or subsystem be handed only its own configuration, which keeps a connector
     * from reading -- or logging -- something that is none of its business.
     */
    public Configuration subset(String prefix) {
        String p = prefix.endsWith(".") ? prefix : prefix + ".";
        Map<String, ConfigValue> sub = new TreeMap<>();
        entries.tailMap(p, true).forEach((k, v) -> {
            if (k.startsWith(p)) {
                String stripped = k.substring(p.length());
                sub.put(stripped, new ConfigValue(stripped, v.value(), v.source(), v.origin()));
            }
        });
        return new Configuration(sub);
    }

    // ------------------------------------------------------------------ typed access

    public Optional<String> getString(String key) {
        return Optional.ofNullable(entries.get(key)).map(ConfigValue::value);
    }

    public String getString(String key, String fallback) {
        return getString(key).orElse(fallback);
    }

    public String requireString(String key) {
        return getString(key).orElseThrow(() -> missing(key));
    }

    public Optional<Boolean> getBoolean(String key) {
        return getString(key).map(v -> ConfigParsers.parseBoolean(key, v));
    }

    public boolean getBoolean(String key, boolean fallback) {
        return getBoolean(key).orElse(fallback);
    }

    public Optional<Long> getLong(String key) {
        return getString(key).map(v -> ConfigParsers.parseLong(key, v));
    }

    public long getLong(String key, long fallback) {
        return getLong(key).orElse(fallback);
    }

    public Optional<Integer> getInt(String key) {
        return getLong(key).map(v -> toInt(key, v));
    }

    public int getInt(String key, int fallback) {
        return getInt(key).orElse(fallback);
    }

    public int requireInt(String key) {
        return getInt(key).orElseThrow(() -> missing(key));
    }

    public Optional<Double> getDouble(String key) {
        return getString(key).map(v -> ConfigParsers.parseDouble(key, v));
    }

    public double getDouble(String key, double fallback) {
        return getDouble(key).orElse(fallback);
    }

    /** A duration such as {@code 200us}, {@code 30s} or {@code 5min}. A bare number is rejected. */
    public Optional<Duration> getDuration(String key) {
        return getString(key).map(v -> ConfigParsers.parseDuration(key, v));
    }

    public Duration getDuration(String key, Duration fallback) {
        return getDuration(key).orElse(fallback);
    }

    /** A byte size such as {@code 4MB} or {@code 1GB}, in binary units. */
    public Optional<Long> getDataSize(String key) {
        return getString(key).map(v -> ConfigParsers.parseDataSize(key, v));
    }

    public long getDataSize(String key, long fallback) {
        return getDataSize(key).orElse(fallback);
    }

    public <E extends Enum<E>> Optional<E> getEnum(String key, Class<E> type) {
        return getString(key).map(v -> ConfigParsers.parseEnum(key, v, type));
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E fallback) {
        return getEnum(key, type).orElse(fallback);
    }

    /** A comma-separated list. Absent and empty both yield an empty list. */
    public List<String> getList(String key) {
        return getString(key).map(ConfigParsers::parseList).orElseGet(List::of);
    }

    // ------------------------------------------------------------------ validation helpers

    /** Fails unless the value is within {@code [min, max]}. Returns it, so it can be used inline. */
    public int requireInRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new ConfigurationException(
                    ConfigErrors.OUT_OF_RANGE,
                    "'" + key + "' is " + value + "; must be between " + min + " and " + max + describeOrigin(key));
        }
        return value;
    }

    private static int toInt(String key, long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ConfigurationException(
                    ConfigErrors.OUT_OF_RANGE, "'" + key + "' is " + value + "; does not fit in a 32-bit int");
        }
        return (int) value;
    }

    private ConfigurationException missing(String key) {
        return new ConfigurationException(
                ConfigErrors.MISSING_REQUIRED, "required configuration key '" + key + "' is not set");
    }

    private String describeOrigin(String key) {
        ConfigValue v = entries.get(key);
        return v == null ? "" : " (" + v.source().display() + ": " + v.origin() + ")";
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Every entry with its provenance, secrets masked.
     *
     * <p>This is what gets logged at startup and shown in the console. It is deliberately the only
     * bulk rendering available, so there is no convenient way to dump the configuration unmasked.
     */
    public List<String> describe() {
        return entries.values().stream().map(ConfigValue::describe).toList();
    }

    @Override
    public String toString() {
        return "Configuration[" + entries.size() + " keys]";
    }
}

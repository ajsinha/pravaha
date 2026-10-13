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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.connect.PluginDiscovery;
import com.ash.messaging.pravaha.server.folders.DeclarationFile;

/**
 * Tests a named connection through its own plugin: configured with the connection's options (and any laid
 * over them for the test), opened, asked its health, closed -- in bounded time (ADR-064).
 *
 * <p>The plugin's own lifecycle, nothing invented: {@code configure} is where a plugin refuses settings,
 * {@code open} is where it connects, {@code health} is what it reports. The answer carries the outcome,
 * the time it took and the plugin's words, with every secret option's value struck out of them -- never
 * an option's value.
 */
final class ConnectionTester {

    /** The longest a test may be asked to take. */
    static final int MAX_SECONDS = 60;

    private ConnectionTester() {}

    static ApiDtos.ConnectionTest test(
            String name,
            String plugin,
            Map<String, String> connection,
            @Nullable Map<String, String> extra,
            int seconds) {
        Map<String, String> options = new LinkedHashMap<>(connection);
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (key != null && value != null) {
                    options.put(key, value);
                }
            });
        }
        return test(name, plugin, find(plugin), options, seconds);
    }

    /** Tests {@code found} -- the plugin instance a connection names, or null when none is loadable. */
    static ApiDtos.ConnectionTest test(
            String name, String plugin, @Nullable Found found, Map<String, String> options, int seconds) {
        int bounded = Math.max(1, Math.min(MAX_SECONDS, seconds));
        long started = System.nanoTime();
        if (found == null) {
            return failed(
                    name,
                    plugin,
                    null,
                    started,
                    "FAILED",
                    "no source, lookup or sink plugin named '" + plugin
                            + "' is on this node's classpath (GET /api/v1/plugins lists the ones it can load)");
        }
        PravahaPlugin instance = found.plugin();
        CompletableFuture<HealthStatus> run = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual()
                .name("pravaha-connection-test-" + name)
                .start(() -> {
                    try {
                        instance.configure(context(name, options));
                        instance.open();
                        run.complete(instance.health());
                    } catch (Throwable e) {
                        run.completeExceptionally(e);
                    } finally {
                        try {
                            instance.close();
                        } catch (RuntimeException e) {
                            // Closing after a test is tidying up; the outcome is already decided.
                        }
                    }
                });
        try {
            HealthStatus health = run.get(bounded, TimeUnit.SECONDS);
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean ok = health.state() != HealthStatus.State.UNHEALTHY;
            return new ApiDtos.ConnectionTest(
                    name,
                    plugin,
                    ok,
                    found.role(),
                    millis,
                    health.state().name(),
                    redact(health.detail(), options),
                    ok ? null : ConfigErrors.CONNECTION_TEST_FAILED.code());
        } catch (TimeoutException e) {
            worker.interrupt();
            return failed(name, plugin, found.role(), started, "TIMED_OUT", "no answer within " + bounded + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return failed(
                    name,
                    plugin,
                    found.role(),
                    started,
                    "FAILED",
                    redact(cause.getClass().getSimpleName() + ": " + cause.getMessage(), options));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(name, plugin, found.role(), started, "FAILED", "interrupted");
        }
    }

    /** A plugin instance, and the role it was found in: {@code source}, {@code lookup} or {@code sink}. */
    record Found(PravahaPlugin plugin, String role) {}

    private static @Nullable Found find(String plugin) {
        var source = PluginDiscovery.find(StreamSourcePlugin.class, plugin).plugin();
        if (source != null) {
            return new Found(source, "source");
        }
        var lookup = PluginDiscovery.find(LookupSourcePlugin.class, plugin).plugin();
        if (lookup != null) {
            return new Found(lookup, "lookup");
        }
        var sink = PluginDiscovery.find(StreamSinkPlugin.class, plugin).plugin();
        return sink == null ? null : new Found(sink, "sink");
    }

    private static PluginContext context(String name, Map<String, String> options) {
        Map<String, String> config = Map.copyOf(options);
        return new PluginContext() {
            @Override
            public Map<String, String> config() {
                return config;
            }

            @Override
            public String instanceName() {
                return "connection-test:" + name;
            }
        };
    }

    private static ApiDtos.ConnectionTest failed(
            String name, String plugin, @Nullable String role, long started, String state, String detail) {
        return new ApiDtos.ConnectionTest(
                name,
                plugin,
                false,
                role,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                state,
                detail,
                ConfigErrors.CONNECTION_TEST_FAILED.code());
    }

    /** {@code text} with the value of every password, secret, token and key option struck out. */
    static String redact(@Nullable String text, Map<String, String> options) {
        String out = String.valueOf(text);
        for (Map.Entry<String, String> option : options.entrySet()) {
            String value = option.getValue();
            if (DeclarationFile.isSecretKey(option.getKey()) && value != null && value.length() >= 3) {
                out = out.replace(value, "****");
            }
        }
        return out;
    }
}

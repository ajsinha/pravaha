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
package com.ash.messaging.pravaha.registry.alert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.api.plugin.NotifierPlugin;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

/**
 * The node's notifier channels (ADR-057): each {@code pravaha.notifiers.<channel>} binding, resolved to
 * a {@link NotifierPlugin} by the name it reports -- the two built in ({@code webhook}, {@code log}) or
 * any on the classpath under {@code ServiceLoader} -- configured and opened once, at start, so a
 * binding the node cannot use refuses the start ({@code PRV-8046}) rather than the first page at 3 a.m.
 *
 * <p>One instance per channel, shared by every alert that names it.
 */
public final class Notifiers implements AutoCloseable {

    /** A channel's configuration: the plugin's name and its options. Never a secret (ADR-052). */
    public record Binding(String channel, String plugin, Map<String, String> options) {
        public Binding {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    private record Context(String instanceName, Map<String, String> config) implements PluginContext {}

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, NotifierPlugin> open = new ConcurrentHashMap<>();

    /** No channels: every {@code NOTIFY} is refused with {@code PRV-8043}. */
    public static Notifiers none() {
        return new Notifiers();
    }

    /** Binds a channel; nothing is opened until {@link #open()}. */
    public synchronized Notifiers bind(Binding binding) {
        bindings.put(binding.channel(), binding);
        return this;
    }

    /** Binds a channel to an instance already configured and opened: for an embedding, or a test. */
    public synchronized Notifiers bind(String channel, NotifierPlugin plugin) {
        bindings.put(channel, new Binding(channel, plugin.name(), Map.of()));
        open.put(channel, plugin);
        return this;
    }

    /**
     * Configures and opens every bound channel.
     *
     * @throws PravahaException {@code PRV-8046} naming the channel, when its plugin is not on the
     *     classpath or refuses its configuration
     */
    public synchronized Notifiers open() {
        for (Binding binding : bindings.values()) {
            if (open.containsKey(binding.channel())) {
                continue;
            }
            NotifierPlugin plugin = discover(binding);
            try {
                plugin.configure(new Context(binding.channel(), binding.options()));
                plugin.open();
            } catch (ConfigurationException e) {
                throw new PravahaException(
                        AlertErrors.NOTIFIER_MISCONFIGURED,
                        "pravaha.notifiers." + binding.channel() + ": " + e.getMessage(),
                        e);
            } catch (RuntimeException e) {
                throw new PravahaException(
                        AlertErrors.NOTIFIER_MISCONFIGURED,
                        "pravaha.notifiers." + binding.channel() + " (" + binding.plugin() + ") could not be opened: "
                                + redact(String.valueOf(e.getMessage())),
                        e);
            }
            open.put(binding.channel(), plugin);
        }
        return this;
    }

    /** Whether a channel of this name is bound. */
    public synchronized boolean has(String channel) {
        return bindings.containsKey(channel);
    }

    /** The bound channels, by name. */
    public synchronized Set<String> channels() {
        return new TreeSet<>(bindings.keySet());
    }

    /** Each channel and the plugin it uses. */
    public synchronized Map<String, String> plugins() {
        Map<String, String> plugins = new LinkedHashMap<>();
        bindings.values().forEach(b -> plugins.put(b.channel(), b.plugin()));
        return plugins;
    }

    /** Delivers through {@code channel}; a channel no longer bound is a delivery that failed. */
    NotifierPlugin.Delivery send(String channel, Notification notification) {
        NotifierPlugin plugin = open.get(channel);
        if (plugin == null) {
            return NotifierPlugin.Delivery.failed(
                    0, "no notifier is bound to '" + channel + "' (pravaha.notifiers." + channel + ")");
        }
        try {
            NotifierPlugin.Delivery delivery = plugin.send(notification);
            return delivery == null ? NotifierPlugin.Delivery.failed(1, "the notifier answered nothing") : delivery;
        } catch (RuntimeException e) {
            return NotifierPlugin.Delivery.failed(1, redact(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** {@code text} with every bound option value that could be a credential struck out. */
    public synchronized String redact(String text) {
        List<Map<String, String>> options = new ArrayList<>();
        bindings.values().forEach(b -> options.add(b.options()));
        return com.ash.messaging.pravaha.common.config.Redaction.strikeOptionValues(text, options);
    }

    @Override
    public synchronized void close() {
        open.values().forEach(plugin -> {
            try {
                plugin.close();
            } catch (RuntimeException e) {
                // One channel refusing to close must not stop the rest.
            }
        });
        open.clear();
    }

    private static NotifierPlugin discover(Binding binding) {
        String wanted = binding.plugin() == null ? "" : binding.plugin().strip();
        for (NotifierPlugin found : ServiceLoader.load(NotifierPlugin.class)) {
            if (found.name().equals(wanted)) {
                return found;
            }
        }
        return switch (wanted) {
            case "webhook" -> new WebhookNotifier();
            case "log" -> new LogNotifier();
            default ->
                throw new PravahaException(
                        AlertErrors.NOTIFIER_MISCONFIGURED,
                        "pravaha.notifiers." + binding.channel() + " names the notifier plugin '" + wanted
                                + "', and none by that name is on the classpath. Built in: webhook, log");
        };
    }
}

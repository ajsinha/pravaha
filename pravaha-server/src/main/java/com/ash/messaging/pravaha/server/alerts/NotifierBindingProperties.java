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
package com.ash.messaging.pravaha.server.alerts;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.registry.alert.Notifiers;

/**
 * {@code pravaha.notifiers.<channel>}: the channels an alert may {@code NOTIFY} (ADR-057), each a
 * notifier plugin and its options -- the shape of {@code pravaha.sinks.*}.
 *
 * <pre>
 * pravaha:
 *   notifiers:
 *     buyers:
 *       plugin: webhook
 *       options:
 *         url: https://hooks.example.com/pravaha
 *         secret-env: PRAVAHA_BUYERS_HOOK_SECRET   # or secret-file: /run/secrets/buyers-hook
 *         timeout: 10s
 *         retries: 4
 *     ops-log:
 *       plugin: log
 * </pre>
 *
 * <p>A secret is never an option's value (ADR-052): the webhook refuses {@code secret}, {@code token}
 * and {@code password} and reads the secret from where {@code secret-env} or {@code secret-file} says.
 */
@Component
@ConfigurationProperties(prefix = "pravaha")
public class NotifierBindingProperties {

    private final Map<String, Spec> notifiers = new LinkedHashMap<>();

    public Map<String, Spec> getNotifiers() {
        return notifiers;
    }

    /** The bindings, unopened. */
    public Notifiers toNotifiers() {
        Notifiers bound = Notifiers.none();
        notifiers.forEach((channel, spec) ->
                bound.bind(new Notifiers.Binding(channel, spec.plugin == null ? "" : spec.plugin, spec.options)));
        return bound;
    }

    public static class Spec {

        private @Nullable String plugin;
        private Map<String, String> options = new LinkedHashMap<>();

        public @Nullable String getPlugin() {
            return plugin;
        }

        public void setPlugin(@Nullable String plugin) {
            this.plugin = plugin;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }
    }
}

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
package com.ash.messaging.pravaha.server.egress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.bindings.egress.SinkBinding;

/**
 * {@code pravaha.sinks.*}: where a query's output may go.
 *
 * <pre>
 * pravaha:
 *   sinks:
 *     audit_trail:
 *       plugin: filesystem
 *       options:
 *         path: /var/lib/pravaha/outgoing/audit_trail.csv
 *         schema: "id:INT64,user:STRING,amount:INT64"
 * </pre>
 *
 * <p>The mirror of {@code SourceBindingProperties}, on purpose -- same shape, same
 * {@code ServiceLoader}-by-{@code name()} resolution, same reason the prefix is {@code pravaha} and
 * the field is {@code sinks} rather than the other way round: with
 * {@code prefix = "pravaha.sinks"}, Spring binds {@code pravaha.sinks.<field>}, so a map called
 * {@code bindings} would be filled from {@code pravaha.sinks.bindings.audit_trail} -- and the
 * configuration documented everywhere, {@code pravaha.sinks.audit_trail}, would bind nothing at all.
 * {@code SourceBindingProperties} found this by running one; this class does not repeat the mistake.
 *
 * <p>Binding a sink here is not the same as attaching one to a query. Nothing today resolves a
 * {@code sinkName} against a registered query's output -- see {@code PluginSinks}, which is the
 * discovery half of that, and ADR-039 item 5 (W8-13), which is the finding that nothing did either
 * half before this existed.
 */
@Component
@ConfigurationProperties(prefix = "pravaha")
public class SinkBindingProperties {

    private final Map<String, Spec> sinks = new LinkedHashMap<>();

    /**
     * Spring binds into this map; the key is the sink name a query would address.
     *
     * <p>See the class javadoc for why the prefix is {@code pravaha} and this field is
     * {@code sinks}: the same trap {@code SourceBindingProperties.getSources()} documents applies
     * here unchanged.
     */
    public Map<String, Spec> getSinks() {
        return sinks;
    }

    /** The configured sink bindings, validated. */
    public List<SinkBinding> toBindings() {
        List<SinkBinding> configured = new ArrayList<>();
        sinks.forEach((name, spec) -> configured.add(new SinkBinding(name, spec.plugin, spec.options)));
        return configured;
    }

    /** One sink's binding, as written in configuration. */
    public static class Spec {

        private String plugin;
        private Map<String, String> options = new LinkedHashMap<>();

        public String getPlugin() {
            return plugin;
        }

        public void setPlugin(String plugin) {
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

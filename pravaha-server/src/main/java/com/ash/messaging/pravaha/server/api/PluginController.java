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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.connect.PluginRegistry;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * Every plugin this node can load, as its code declares it, and what the caller may see bound to it.
 *
 * <p>Two sources, merged by name. The node's source, sink and lookup bindings find their plugins by
 * {@code ServiceLoader}, so the plugins a node <em>can</em> run are the ones that finds: each is
 * instantiated once, asked its name, version, required API and declared capabilities, and closed
 * without being configured. A plugin an embedding application registered with the engine
 * ({@code PravahaEngine.plugins()}) adds its manifest's settings and a health it reports live.
 *
 * <p><strong>No binding's options, ever.</strong> A binding is reported as its kind and its name.
 * The options are where credentials live, and nothing in this class reads them. The settings listed
 * are the <em>names</em> a manifest declares, not anybody's values.
 *
 * <p>Bindings are filtered like the catalogue: a stream, lookup table or sink appears to a caller the
 * policy lets read its name. A plugin a visible binding names that is not on the classpath is listed
 * as not loaded rather than dropped, because "configured and cannot run" is the finding.
 *
 * <p>Health is honest about where it comes from. The node holds no long-lived instance of a
 * classpath plugin that it could ask -- each binding configures its own -- so such a plugin's health
 * is {@code UNKNOWN} with {@code reported: false}, and a binding's failures show where they happen:
 * on the query ({@code PRV-8009} for a sink) and in the node's log.
 */
@RestController
@RequestMapping("/api/v1/plugins")
@Tag(name = "Plugins", description = "Loadable plugins, their declared capabilities, and what is bound to them")
public class PluginController {

    private final Supplier<PluginRegistry> registered;
    private final SourceBindingProperties sources;
    private final SinkBindingProperties sinks;
    private final HttpAuthorizer authorizer;
    private final Supplier<List<Declared>> discovery;
    private volatile @Nullable List<Declared> discovered;

    @Autowired
    public PluginController(
            PravahaEngine engine,
            SourceBindingProperties sources,
            SinkBindingProperties sinks,
            HttpAuthorizer authorizer) {
        this(engine::plugins, sources, sinks, authorizer, PluginController::discover);
    }

    /** For a test: the classpath discovery is replaceable, the rules applied to it are not. */
    PluginController(
            Supplier<PluginRegistry> registered,
            SourceBindingProperties sources,
            SinkBindingProperties sinks,
            HttpAuthorizer authorizer,
            Supplier<List<Declared>> discovery) {
        this.registered = registered;
        this.sources = sources;
        this.sinks = sinks;
        this.authorizer = authorizer;
        this.discovery = discovery;
    }

    @GetMapping
    @Operation(summary = "List the plugins this node can load, their manifests, capabilities and visible bindings")
    public List<AdminDtos.PluginInfo> list(HttpServletRequest http) {
        Map<String, Merged> byName = new TreeMap<>();
        for (Declared declared : declared()) {
            byName.computeIfAbsent(key(declared.name()), name -> new Merged(declared.name()))
                    .declared(declared);
        }
        PluginRegistry registry = registered.get();
        if (registry != null) {
            for (PluginRegistry.Registration registration : registry.registrations()) {
                byName.computeIfAbsent(
                                key(registration.manifest().name()),
                                name -> new Merged(registration.manifest().name()))
                        .registered(registration);
            }
        }
        // Bindings last, and only the ones this caller may see. A plugin named by a visible binding
        // that nothing above found is "configured and cannot run", which is the finding.
        sources.getSources().forEach((stream, spec) -> bind(byName, http, "source", stream, spec.getPlugin()));
        sources.getLookups().forEach((table, spec) -> bind(byName, http, "lookup", table, spec.getPlugin()));
        sinks.getSinks().forEach((sink, spec) -> bind(byName, http, "sink", sink, spec.getPlugin()));
        return byName.values().stream().map(Merged::toDto).toList();
    }

    private void bind(
            Map<String, Merged> byName, HttpServletRequest http, String kind, String name, @Nullable String plugin) {
        if (plugin == null || plugin.isBlank() || !authorizer.mayRead(http, name)) {
            return;
        }
        byName.computeIfAbsent(key(plugin), key -> new Merged(plugin))
                .bindings
                .add(new AdminDtos.PluginBinding(kind, name));
    }

    private List<Declared> declared() {
        List<Declared> found = discovered;
        if (found == null) {
            // Once per node: what is on the classpath does not change while the node runs, and each
            // discovery instantiates every plugin.
            found = List.copyOf(discovery.get());
            discovered = found;
        }
        return found;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** What one plugin class declares, before any binding configures it. */
    record Declared(
            String name,
            String version,
            String requiredApiVersion,
            String kind,
            AdminDtos.@Nullable SourceCapabilities source,
            AdminDtos.@Nullable SinkCapabilities sink,
            @Nullable String problem) {}

    /** Every plugin {@code ServiceLoader} finds for the three roles a binding can give one. */
    static List<Declared> discover() {
        List<Declared> out = new ArrayList<>();
        discover(StreamSourcePlugin.class, "source", out);
        discover(StreamSinkPlugin.class, "sink", out);
        discover(LookupSourcePlugin.class, "lookup", out);
        return out;
    }

    private static <T extends PravahaPlugin> void discover(Class<T> type, String kind, List<Declared> out) {
        Iterator<T> candidates = ServiceLoader.load(type).iterator();
        int broken = 0;
        while (broken < 64) {
            T candidate;
            try {
                if (!candidates.hasNext()) {
                    return;
                }
                candidate = candidates.next();
            } catch (java.util.ServiceConfigurationError e) {
                // A provider entry naming a class that will not load. The bindings report it by
                // name when they need it (PRV-5xxx); this listing skips it rather than failing whole,
                // and gives up on a classpath that is broken rather than looping on it.
                broken++;
                continue;
            }
            try {
                out.add(declared(candidate, kind));
            } catch (RuntimeException | LinkageError e) {
                // A plugin whose name() itself throws cannot be listed by name; there is nothing to show.
            } finally {
                try {
                    candidate.close();
                } catch (Exception e) {
                    // Never opened; closing is tidying up.
                }
            }
        }
    }

    private static Declared declared(PravahaPlugin plugin, String kind) {
        AdminDtos.SourceCapabilities source = null;
        AdminDtos.SinkCapabilities sink = null;
        String problem = null;
        try {
            if (plugin instanceof StreamSourcePlugin reader && "source".equals(kind)) {
                source = source(reader.capabilities());
            } else if (plugin instanceof StreamSinkPlugin writer && "sink".equals(kind)) {
                sink = sink(writer.capabilities());
            }
        } catch (RuntimeException | LinkageError e) {
            // Its capabilities depend on configuration it has not been given. Said, not guessed.
            problem = "declares its " + kind + " capabilities only once a binding configures it";
        }
        return new Declared(
                plugin.name(),
                String.valueOf(plugin.version()),
                String.valueOf(plugin.requiredApiVersion()),
                kind,
                source,
                sink,
                problem);
    }

    private static AdminDtos.SourceCapabilities source(SourceCapabilities capabilities) {
        return new AdminDtos.SourceCapabilities(
                capabilities.replayableOffsets(),
                capabilities.orderedWithinPartition(),
                capabilities.emitsDeletes(),
                capabilities.emitsBeforeImage(),
                capabilities.guarantee().name(),
                capabilities.pushdown().stream().map(Enum::name).sorted().toList(),
                capabilities.typicalLatency() == null
                        ? null
                        : capabilities.typicalLatency().toString());
    }

    private static AdminDtos.SinkCapabilities sink(SinkCapabilities capabilities) {
        return new AdminDtos.SinkCapabilities(
                capabilities.emitModes().stream().map(Enum::name).sorted().toList(),
                capabilities.transactional(),
                capabilities.idempotentUpsert(),
                capabilities.maxBatchRows(),
                capabilities.guarantee().name());
    }

    /** One plugin, assembled from whatever knows about it. */
    private static final class Merged {

        private final String name;
        private @Nullable String version;
        private @Nullable String requiredApiVersion;
        private boolean loaded;
        private final TreeSet<String> kinds = new TreeSet<>();
        private AdminDtos.@Nullable SourceCapabilities source;
        private AdminDtos.@Nullable SinkCapabilities sink;
        private final List<String> problems = new ArrayList<>();
        private final List<String> settings = new ArrayList<>();
        private AdminDtos.@Nullable PluginHealth health;
        private final List<AdminDtos.PluginBinding> bindings = new ArrayList<>();

        Merged(String name) {
            this.name = name;
        }

        void declared(Declared declared) {
            loaded = true;
            version = version == null ? declared.version() : version;
            requiredApiVersion = requiredApiVersion == null ? declared.requiredApiVersion() : requiredApiVersion;
            kinds.add(declared.kind());
            source = source == null ? declared.source() : source;
            sink = sink == null ? declared.sink() : sink;
            if (declared.problem() != null) {
                problems.add(declared.problem());
            }
        }

        void registered(PluginRegistry.Registration registration) {
            loaded = true;
            version = registration.manifest().version().toString();
            requiredApiVersion = registration.manifest().requiredApiVersion().toString();
            PravahaPlugin plugin = registration.plugin();
            if (plugin instanceof StreamSourcePlugin) {
                kinds.add("source");
            }
            if (plugin instanceof StreamSinkPlugin) {
                kinds.add("sink");
            }
            if (plugin instanceof LookupSourcePlugin) {
                kinds.add("lookup");
            }
            settings.addAll(new TreeSet<>(registration.manifest().configSchema().keySet()));
            try {
                HealthStatus status = plugin.health();
                health = new AdminDtos.PluginHealth(status.state().name(), status.detail(), true);
            } catch (RuntimeException e) {
                health = new AdminDtos.PluginHealth(
                        HealthStatus.State.UNHEALTHY.name(),
                        "its health check threw " + e.getClass().getSimpleName(),
                        true);
            }
        }

        AdminDtos.PluginInfo toDto() {
            boolean compatible = requiredApiVersion != null && compatible(requiredApiVersion);
            String note = problems.isEmpty()
                    ? (source != null || sink != null
                            ? "as the plugin declares them before configuration; a binding's configuration can "
                                    + "narrow them, and each sink's own are on /api/v1/sinks"
                            : null)
                    : String.join("; ", problems);
            AdminDtos.PluginHealth reported = health != null
                    ? health
                    : new AdminDtos.PluginHealth(
                            "UNKNOWN",
                            loaded
                                    ? "no instance this node holds reports health; each binding configures its "
                                            + "own, and its failures show on the query or sink that uses it"
                                    : "not on this node's classpath, so nothing bound to it can run",
                            false);
            List<AdminDtos.PluginBinding> sorted = new ArrayList<>(bindings);
            sorted.sort(java.util.Comparator.comparing(AdminDtos.PluginBinding::kind)
                    .thenComparing(AdminDtos.PluginBinding::name));
            return new AdminDtos.PluginInfo(
                    name,
                    version,
                    requiredApiVersion,
                    compatible,
                    loaded,
                    List.copyOf(kinds),
                    loaded ? new AdminDtos.PluginCapabilities(source, sink, note) : null,
                    List.copyOf(settings),
                    reported,
                    List.copyOf(sorted));
        }

        private static boolean compatible(String required) {
            try {
                return Version.apiVersion().isCompatibleWith(Version.parse(required));
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}

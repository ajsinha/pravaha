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
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.connect.PluginErrors;
import com.ash.messaging.pravaha.registry.SinkFactory;

/**
 * Turns configured {@link SinkBinding}s into opened {@link StreamSinkPlugin} instances.
 *
 * <p>The mirror of {@code PluginSourceFeeds} (see {@code com.ash.messaging.pravaha.server.ingest}),
 * deliberately -- discovery by {@code ServiceLoader}, configure-before-open, the same shape of
 * refusal when a name is not on the classpath. What it is <strong>not</strong> the mirror of is the
 * pumping: a source feed drives rows into a running query on its own thread, and nothing here does
 * the equivalent for a sink: a sink is written to by the query that names it, on that query's own
 * commits. This class is the {@link SinkFactory} the node hands the registry, and the registry is
 * what attaches a query to what {@link #open} returns (ADR-043) -- this resolver never decides which
 * query writes where.
 *
 * <p>What this class makes true, on its own and testably: a sink can be named in configuration,
 * found on the classpath by the name it reports for itself, configured, opened, written to, flushed
 * and closed -- the same lifecycle {@link StreamSinkPlugin} documents, exercised end to end rather
 * than only declared.
 */
public final class PluginSinks implements SinkFactory, AutoCloseable {

    private final Map<String, SinkBinding> bindings = new ConcurrentHashMap<>();

    /** Every plugin this instance has opened, so {@link #close()} can release all of them. */
    private final List<StreamSinkPlugin> opened = new ArrayList<>();

    private final Object openLock = new Object();

    /**
     * Binds a sink name to a plugin, replacing any previous binding.
     *
     * <p>Binding alone opens nothing -- exactly like {@code PluginSourceFeeds.bind}, and for the
     * same reason: a deployment that names a sink in configuration but registers no query against it
     * should not pay for a connection nobody uses.
     */
    public PluginSinks bind(SinkBinding binding) {
        bindings.put(binding.sinkName(), binding);
        return this;
    }

    /** The bindings in force, by sink name. */
    public Map<String, SinkBinding> bindings() {
        return Map.copyOf(bindings);
    }

    /**
     * Resolves, configures and opens the named sink.
     *
     * <p>A fresh instance every call, deliberately -- unlike {@code PluginSourceFeeds}'s SRC-3
     * sharing, which exists because a thousand queries scanning one store a thousand times is a cost
     * that lands on somebody else's infrastructure. A sink has no equivalent: each writer is
     * producing its own rows, and whether two queries may safely share one open sink (one file
     * handle, one connection, one transaction) is a question about the sink's own concurrency
     * contract that only the code attaching a query to it -- not this resolver -- can answer. This
     * class hands back an independent, ready-to-write instance and lets that decision be made where
     * the information to make it actually lives.
     *
     * @throws PravahaException {@link EgressErrors#NO_SUCH_SINK_PLUGIN} when no binding named {@code
     *     sinkName} exists, or when no plugin on the classpath reports that name; {@link
     *     EgressErrors#SINK_BINDING_FAILED} when the plugin refuses its configuration or cannot open
     */
    @Override
    public StreamSinkPlugin open(String sinkName) {
        SinkBinding binding = bindings.get(sinkName);
        if (binding == null) {
            throw new PravahaException(
                    EgressErrors.NO_SUCH_SINK_PLUGIN,
                    "no sink is bound to '" + sinkName + "'. Bind one under pravaha.sinks." + sinkName);
        }
        StreamSinkPlugin plugin = openConfigured(configure(binding), binding);
        synchronized (openLock) {
            opened.add(plugin);
        }
        return plugin;
    }

    /**
     * What the named sink can promise, without holding it open.
     *
     * <p>A fresh instance is configured, asked, and closed again -- mirroring {@code
     * PluginSourceFeeds.groupFor}'s reasoning for sources: what a plugin can promise is a property of
     * its configuration, not of a live connection, and asking before opening means a caller deciding
     * whether a query's changelog is even compatible with this sink -- the check {@code
     * ChangelogAnalysis.checkAgainst} exists to make -- does not pay for a connection it may then
     * have to throw away.
     */
    @Override
    public SinkCapabilities capabilitiesOf(String sinkName) {
        SinkBinding binding = bindings.get(sinkName);
        if (binding == null) {
            throw new PravahaException(
                    EgressErrors.NO_SUCH_SINK_PLUGIN,
                    "no sink is bound to '" + sinkName + "'. Bind one under pravaha.sinks." + sinkName);
        }
        StreamSinkPlugin plugin = configure(binding);
        try {
            return plugin.capabilities();
        } finally {
            closeQuietly(plugin);
        }
    }

    /**
     * Closes a sink {@link #open} returned and forgets it, when the registration writing to it is
     * dropped. Without the forgetting, a node that registers and drops queries all day holds every
     * sink it ever opened until shutdown.
     */
    @Override
    public void release(StreamSinkPlugin sink) {
        synchronized (openLock) {
            opened.remove(sink);
        }
        closeQuietly(sink);
    }

    /** How many sinks are open right now, across every registration writing to one. */
    public int openCount() {
        synchronized (openLock) {
            return opened.size();
        }
    }

    /**
     * What the named sink accepts, the schema it was configured with, and its key -- from one fresh,
     * configured, never-opened instance, like {@link #capabilitiesOf}.
     */
    @Override
    public Description describe(String sinkName) {
        SinkBinding binding = bindings.get(sinkName);
        if (binding == null) {
            throw new PravahaException(
                    EgressErrors.NO_SUCH_SINK_PLUGIN,
                    "no sink is bound to '" + sinkName + "'. Bind one under pravaha.sinks." + sinkName);
        }
        StreamSinkPlugin plugin = configure(binding);
        try {
            return new Description(plugin.capabilities(), plugin.schema(), plugin.keyColumns());
        } finally {
            closeQuietly(plugin);
        }
    }

    /** Closes every sink this instance has opened. Idempotent, like the plugins it closes. */
    @Override
    public void close() {
        synchronized (openLock) {
            opened.forEach(PluginSinks::closeQuietly);
            opened.clear();
        }
    }

    private StreamSinkPlugin configure(SinkBinding binding) {
        StreamSinkPlugin plugin = discover(binding);
        try {
            plugin.configure(new BindingContext(binding));
            return plugin;
        } catch (RuntimeException e) {
            closeQuietly(plugin);
            throw new PravahaException(
                    EgressErrors.SINK_BINDING_FAILED,
                    "the '" + binding.plugin() + "' sink plugin refused its configuration for '" + binding.sinkName()
                            + "': " + e,
                    e);
        } catch (Exception e) {
            closeQuietly(plugin);
            throw new PravahaException(
                    EgressErrors.SINK_BINDING_FAILED,
                    "the '" + binding.plugin() + "' sink plugin refused its configuration for '" + binding.sinkName()
                            + "': " + e,
                    e);
        }
    }

    private StreamSinkPlugin openConfigured(StreamSinkPlugin plugin, SinkBinding binding) {
        try {
            plugin.open();
            return plugin;
        } catch (RuntimeException e) {
            closeQuietly(plugin);
            throw new PravahaException(
                    EgressErrors.SINK_BINDING_FAILED,
                    "the '" + binding.plugin() + "' sink plugin could not be opened for '" + binding.sinkName() + "': "
                            + e,
                    e);
        } catch (Exception e) {
            closeQuietly(plugin);
            throw new PravahaException(
                    EgressErrors.SINK_BINDING_FAILED,
                    "the '" + binding.plugin() + "' sink plugin could not be opened for '" + binding.sinkName() + "': "
                            + e,
                    e);
        }
    }

    /**
     * Finds the named plugin on the classpath.
     *
     * <p>A fresh instance per call, exactly as {@code PluginSourceFeeds.discover} does for sources:
     * two sinks configured from the same plugin class are two configurations of it, and {@code
     * configure} is called once per instance.
     */
    private StreamSinkPlugin discover(SinkBinding binding) {
        List<String> available = new ArrayList<>();
        try {
            for (StreamSinkPlugin candidate : ServiceLoader.load(StreamSinkPlugin.class)) {
                if (candidate.name().equalsIgnoreCase(binding.plugin())) {
                    return candidate;
                }
                available.add(candidate.name());
                closeQuietly(candidate);
            }
        } catch (java.util.ServiceConfigurationError e) {
            // ERRC-059's sibling on the egress side: ServiceLoader raises this, uncaught, from
            // inside the iteration -- not a RuntimeException, so left alone it passes straight
            // through every PravahaException handler as a bare, uncoded Error.
            throw new PravahaException(
                    PluginErrors.LOAD_FAILED,
                    "a sink plugin on the classpath could not be loaded while looking for '" + binding.plugin() + "': "
                            + e.getMessage(),
                    e);
        }
        throw new PravahaException(
                EgressErrors.NO_SUCH_SINK_PLUGIN,
                "no sink plugin named '" + binding.plugin() + "' is on the classpath, so '" + binding.sinkName()
                        + "' cannot be written to. Available: "
                        + (available.isEmpty() ? "none -- no sink plugin jar is on the classpath" : available));
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            // Already unwinding, or tidying up a candidate this resolver rejected. Reporting this
            // would replace the failure that matters with a failure to tidy up after it.
        }
    }

    /**
     * A plugin's view of its binding.
     *
     * <p>Package-private rather than private, on the chance a second sink-facing type needs exactly
     * this -- the same reasoning {@code PluginSourceFeeds.BindingContext} gives for the source side.
     */
    record BindingContext(SinkBinding binding) implements PluginContext {

        @Override
        public Map<String, String> config() {
            return binding.options();
        }

        @Override
        public String instanceName() {
            return binding.sinkName();
        }
    }
}

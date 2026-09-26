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
package com.ash.messaging.pravaha.connect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;

/**
 * Finds one plugin by name among the providers {@link ServiceLoader} lists, taking each provider on
 * its own (PKG-3).
 *
 * <p>The three resolvers -- sources, sinks, lookups -- each iterated the loader in one loop inside one
 * {@code try}, so the first provider that could not be loaded or constructed threw {@link
 * ServiceConfigurationError} out of the loop and ended discovery for every plugin of its kind. A
 * deployment's {@code filesystem} binding failed because the Cassandra driver was missing, for a
 * connector it never named.
 *
 * <p>Here a provider that fails is recorded with its class and its cause, and the walk goes on. If
 * the plugin asked for is among those that loaded, it is returned: the broken one was not asked for.
 * If it is not, and some provider failed, the request is refused with {@link PluginErrors#LOAD_FAILED}
 * naming each failure, because the plugin asked for may be the one that failed and "no such plugin"
 * would send the operator to check a name that is right. Not lenient: nothing broken is ever used, and
 * nothing broken is passed over in silence when it could be the answer.
 */
public final class PluginDiscovery {

    /**
     * A walk that gives up after this many consecutive failures: a classpath broken so thoroughly is
     * not going to yield the plugin, and a provider iterator that kept failing on one entry would
     * otherwise spin.
     */
    static final int MAX_FAILURES = 64;

    private PluginDiscovery() {}

    /**
     * What a walk found.
     *
     * @param plugin the plugin whose {@code name()} matched, or {@code null}
     * @param available the names of the plugins that loaded and did not match, already closed
     * @param failures one line per provider that could not be loaded, constructed or named: its class,
     *     then its cause
     * @param firstFailure the first failure's throwable, carried as the cause of any refusal
     */
    public record Found<T>(T plugin, List<String> available, List<String> failures, Throwable firstFailure) {}

    /** {@link #find(Class, String, ClassLoader)} through the thread's context class loader, as {@link ServiceLoader#load(Class)} does. */
    public static <T extends PravahaPlugin> Found<T> find(Class<T> type, String name) {
        return find(type, name, Thread.currentThread().getContextClassLoader());
    }

    public static <T extends PravahaPlugin> Found<T> find(Class<T> type, String name, ClassLoader loader) {
        List<String> available = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Throwable firstFailure = null;
        Iterator<T> candidates = ServiceLoader.load(type, loader).iterator();
        int consecutive = 0;
        while (consecutive < MAX_FAILURES) {
            T candidate;
            try {
                if (!candidates.hasNext()) {
                    break;
                }
                candidate = candidates.next();
            } catch (ServiceConfigurationError e) {
                // The loader names the provider class in its message ("Provider x.Y not found",
                // "... could not be instantiated") and carries the cause; the iterator has moved past
                // the entry, so the next call reaches the next provider.
                failures.add(describe(e));
                firstFailure = firstFailure == null ? e : firstFailure;
                consecutive++;
                continue;
            }
            consecutive = 0;
            String candidateName;
            try {
                candidateName = candidate.name();
            } catch (RuntimeException | LinkageError e) {
                failures.add(candidate.getClass().getName() + " could not report its name: " + e);
                firstFailure = firstFailure == null ? e : firstFailure;
                closeQuietly(candidate);
                continue;
            }
            if (candidateName != null && candidateName.equalsIgnoreCase(name)) {
                return new Found<>(candidate, available, failures, firstFailure);
            }
            available.add(candidateName);
            closeQuietly(candidate);
        }
        return new Found<>(null, available, failures, firstFailure);
    }

    /**
     * The refusal for a plugin that was not found while other providers of its kind failed.
     *
     * @param kind "source", "sink" or "lookup", for the message
     */
    public static PravahaException loadFailed(String kind, String wanted, Found<?> found) {
        return new PravahaException(
                PluginErrors.LOAD_FAILED,
                "no " + kind + " plugin named '" + wanted + "' is among those that loaded ("
                        + (found.available().isEmpty() ? "none" : found.available()) + "), and "
                        + found.failures().size() + " " + kind + " plugin"
                        + (found.failures().size() == 1 ? "" : "s")
                        + " on the classpath could not be loaded, so it may be one of them: "
                        + String.join("; ", found.failures())
                        + ". The other plugins of this kind are unaffected; the one named here is refused "
                        + "until its jar and its dependencies are complete.",
                found.firstFailure());
    }

    private static String describe(ServiceConfigurationError e) {
        Throwable cause = e.getCause();
        return e.getMessage() + (cause == null ? "" : " (" + cause + ")");
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            // A candidate that was never configured or opened; closing it is tidying up, and a
            // failure to tidy must not replace the answer.
        }
    }
}

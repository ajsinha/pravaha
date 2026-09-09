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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * A parent-last classloader, one per plugin.
 *
 * <p>This is what lets {@code pravaha-plugin-cassandra} ship Netty 4.1 while the gateway uses 4.2,
 * and lets two plugins disagree about Guava. Skipping it is the recurring operational failure of
 * plugin systems: everything works until the day two connectors need incompatible versions of the
 * same library, and by then the fix is a migration rather than a configuration.
 *
 * <p>Parent-first is kept for exactly the packages that must be shared:
 *
 * <ul>
 *   <li>{@code com.ash.messaging.pravaha.api} -- the plugin and the engine must agree on these
 *       types, or a {@code RowWriter} handed across the boundary would be a different class to each
 *       side and every call would throw {@code ClassCastException}.
 *   <li>The JDK, which cannot be duplicated.
 *   <li>SLF4J, so a plugin's logging reaches the engine's configured appenders rather than
 *       disappearing into a second, unconfigured logging framework.
 * </ul>
 *
 * <p>Everything else resolves from the plugin's own jars first.
 */
public final class PluginClassLoader extends URLClassLoader {

    private static final List<String> PARENT_FIRST =
            List.of("com.ash.messaging.pravaha.api.", "java.", "javax.", "jdk.", "sun.", "org.slf4j.");

    private final String pluginName;

    static {
        registerAsParallelCapable();
    }

    public PluginClassLoader(String pluginName, URL[] jars, ClassLoader parent) {
        super("pravaha-plugin-" + pluginName, jars, parent);
        this.pluginName = pluginName;
    }

    public String pluginName() {
        return pluginName;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return resolved(loaded, resolve);
            }
            if (isParentFirst(name)) {
                return resolved(getParent().loadClass(name), resolve);
            }
            try {
                // The plugin's own jars win, which is the whole point.
                return resolved(findClass(name), resolve);
            } catch (ClassNotFoundException notInPlugin) {
                return resolved(getParent().loadClass(name), resolve);
            }
        }
    }

    private Class<?> resolved(Class<?> type, boolean resolve) {
        if (resolve) {
            resolveClass(type);
        }
        return type;
    }

    static boolean isParentFirst(String className) {
        for (String prefix : PARENT_FIRST) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The prefixes always loaded from the parent, for diagnostics and tests. */
    public static List<String> parentFirstPrefixes() {
        return PARENT_FIRST;
    }

    @Override
    public String toString() {
        return "PluginClassLoader[" + pluginName + ", " + getURLs().length + " jars]";
    }
}

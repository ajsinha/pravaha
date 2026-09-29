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
package com.ash.messaging.pravaha.registry;

import java.lang.reflect.InvocationTargetException;

/**
 * Formats a generated fixture the way this repository's build does (FIX-3).
 *
 * <p>A fixture is written to be dropped into {@code pravaha-it}, whose build runs {@code
 * spotless:check} with the Palantir formatter; hand-built source never matched it, so the file the
 * export suggested committing broke the build until somebody formatted it. When the formatter is
 * on the classpath of the process exporting -- a test, or a tool built with it -- the source is
 * formatted here, by the same formatter. A node does not carry it: the formatter reaches into the
 * compiler's internals and would need them opened in every node's JVM for one debug command. There
 * the source says so, in a comment above its {@code package} line that {@code spotless:apply}
 * replaces with the licence header, so following the instruction removes it.
 *
 * <p>Looked up by name rather than linked, so this module does not depend on a formatter.
 */
final class FixtureFormatter {

    /** The comment an unformatted fixture carries, above its package line. */
    static final String UNFORMATTED_NOTE =
            "// Not formatted: run ./mvnw -pl pravaha-it spotless:apply before committing this file.\n"
                    + "// (The node that wrote it has no Java formatter on its classpath.)\n";

    private static final String FORMATTER = "com.palantir.javaformat.java.Formatter";

    private FixtureFormatter() {}

    /**
     * {@code source}, Palantir-formatted if the formatter can run here; otherwise {@code source}
     * with {@link #UNFORMATTED_NOTE} before its {@code package} line.
     */
    static String format(String source) {
        String formatted = tryFormat(source);
        if (formatted != null) {
            return formatted;
        }
        int packageLine = source.indexOf("\npackage ");
        return packageLine < 0
                ? UNFORMATTED_NOTE + source
                : source.substring(0, packageLine + 1) + UNFORMATTED_NOTE + source.substring(packageLine + 1);
    }

    /** Whether {@code source} carries the note that it is not formatted. */
    static boolean isUnformatted(String source) {
        return source.contains(UNFORMATTED_NOTE);
    }

    private static String tryFormat(String source) {
        try {
            Class<?> formatter = Class.forName(FORMATTER, true, FixtureFormatter.class.getClassLoader());
            Object instance = formatter.getMethod("create").invoke(null);
            Object result = formatter.getMethod("formatSource", String.class).invoke(instance, source);
            return result instanceof String text ? text : null;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException absent) {
            return null;
        } catch (InvocationTargetException | LinkageError | RuntimeException failed) {
            // Present but unable to run -- most often the compiler's internals not opened to it.
            // The fixture is still correct Java; it says it needs formatting rather than failing
            // an export over layout.
            return null;
        }
    }
}

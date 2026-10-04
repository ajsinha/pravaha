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
package com.ash.messaging.pravaha.cli;

import java.io.Console;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jspecify.annotations.Nullable;

/**
 * Terminal colour, applied only when a person will actually read it.
 *
 * <p>Escape codes are suppressed when output is redirected, when {@code NO_COLOR} is set, or when
 * {@code TERM} is {@code dumb}. Colour codes landing in a log file or a CI transcript make the one
 * thing someone is grepping for harder to find, which is the opposite of the point.
 */
public final class Ansi {

    private static final String ESCAPE = "\u001B";
    private static final boolean ENABLED = detect();

    private Ansi() {}

    // Error Prone holds that System.console() is never null on JDK 22+; under surefire's forked JVM
    // (no stdin) it still is, and on JDK 21 a null console is exactly "not a terminal".
    @SuppressWarnings("SystemConsoleNull") // still null in a JVM with no console at all
    private static boolean detect() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return detect(System.getenv("TERM"), isTerminal(System.console()));
    }

    /**
     * Whether this JVM's console is a terminal, on every JDK from 21. On 21 {@code System.console()}
     * is null unless the JVM is attached to one, so non-null is the answer. From 22 it is never null
     * and {@code Console.isTerminal()} (new in 22) is the question; the classes target 21, so it is
     * looked up rather than called (ANSICONSOLE-1).
     */
    static boolean isTerminal(@Nullable Console console) {
        if (console == null) {
            return false;
        }
        MethodHandle probe = IsTerminal.HANDLE;
        if (probe == null) {
            return true;
        }
        try {
            return (boolean) probe.invokeExact(console);
        } catch (Throwable unexpected) {
            return false;
        }
    }

    /** {@code Console.isTerminal()} where the JDK has it (22 and later), else null. */
    private static final class IsTerminal {
        static final @Nullable MethodHandle HANDLE = find();

        private static @Nullable MethodHandle find() {
            try {
                return MethodHandles.publicLookup()
                        .findVirtual(Console.class, "isTerminal", MethodType.methodType(boolean.class));
            } catch (NoSuchMethodException | IllegalAccessException onJdk21) {
                return null;
            }
        }
    }

    /**
     * Colour for a terminal that is one and is not dumb. ANSICONSOLE-1: this asked only whether
     * {@code System.console()} was null, which it was when output was redirected -- until JDK 22, after
     * which it never is, so a redirected {@code pravaha} wrote escape codes into files and pipes.
     */
    static boolean detect(@Nullable String term, boolean terminal) {
        if (term == null || "dumb".equals(term)) {
            return false;
        }
        return terminal;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    private static String wrap(String code, String text) {
        return ENABLED ? ESCAPE + "[" + code + "m" + text + ESCAPE + "[0m" : text;
    }

    public static String bold(String text) {
        return wrap("1", text);
    }

    public static String dim(String text) {
        return wrap("2", text);
    }

    public static String accent(String text) {
        return wrap("36", text);
    }

    public static String good(String text) {
        return wrap("32", text);
    }

    public static String bad(String text) {
        return wrap("31", text);
    }
}

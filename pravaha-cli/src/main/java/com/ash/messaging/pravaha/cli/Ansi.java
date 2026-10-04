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

    private static boolean detect() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        // Never null on JDK 22 and later; whether it is a terminal is the question.
        return detect(System.getenv("TERM"), System.console().isTerminal());
    }

    /**
     * Colour for a terminal that is one and is not dumb. ANSICONSOLE-1: this asked only whether
     * {@code System.console()} was null, which it was when output was redirected -- until JDK 22, after
     * which it never is, so a redirected {@code pravaha} wrote escape codes into files and pipes.
     */
    static boolean detect(String term, boolean terminal) {
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

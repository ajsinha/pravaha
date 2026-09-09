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
package com.ash.messaging.pravaha.common.config;

/**
 * Where a configuration value came from, in ascending order of precedence.
 *
 * <p>Tracked per key rather than discarded after loading, because "where did this value come from?"
 * is one of the first questions asked when a deployment behaves unexpectedly, and reconstructing the
 * answer afterwards from four overlapping sources is miserable. {@link Configuration#sourceOf} is
 * cheap precisely so the console can show it next to every setting (design section 23.6).
 */
public enum ConfigSource {

    /** Compiled-in default. Lowest precedence. */
    DEFAULT("default"),

    /** A configuration file. Later files in the list win over earlier ones. */
    FILE("file"),

    /**
     * A {@code <name>.local.<ext>} sitting beside a configured file.
     *
     * <p>Exists for values a machine needs in order to start but that must never be committed --
     * a signing secret being the usual case. The overlay is git-ignored, sets only what it names,
     * and its absence is a no-op.
     */
    LOCAL_OVERLAY("local overlay"),

    /** A JVM system property, {@code -Dkey=value}. */
    SYSTEM_PROPERTY("system property"),

    /** An environment variable, matched with dots and dashes mapped to underscores. */
    ENVIRONMENT("environment"),

    /** A command-line argument, {@code --key=value}. Highest precedence among external sources. */
    COMMAND_LINE("command line"),

    /** Set in code on the builder. Wins over everything, because the caller asked for it explicitly. */
    PROGRAMMATIC("programmatic");

    private final String display;

    ConfigSource(String display) {
        this.display = display;
    }

    /** Human-readable name for logs, API responses and the console. */
    public String display() {
        return display;
    }

    /** Whether this source outranks {@code other}. Ordinal order is the precedence order. */
    public boolean outranks(ConfigSource other) {
        return ordinal() > other.ordinal();
    }
}

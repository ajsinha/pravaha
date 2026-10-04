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
package com.ash.messaging.pravaha.api.plugin;

import java.util.Objects;

/** A semantic version. Used for plugin versions and for the API range a plugin declares. */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must be non-negative");
        }
    }

    public static Version parse(String text) {
        // Limit 0, spelled out: trailing empty parts are dropped, so "1.2." still reads as 1.2.0.
        String[] parts = text.strip().split("[.-]", 0);
        if (parts.length < 2) {
            throw new IllegalArgumentException("not a version: '" + text + "'; expected major.minor[.patch]");
        }
        try {
            return new Version(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a version: '" + text + "'", e);
        }
    }

    /**
     * Whether this version can host something built against {@code required}.
     *
     * <p>Same major, and at least the required minor -- the ordinary semver contract. A plugin
     * compiled against API 1.2 runs on 1.5 and not on 2.0.
     */
    public boolean isCompatibleWith(Version required) {
        return major == required.major && minor >= required.minor;
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        return c != 0 ? c : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /** The API version plugins are checked against. */
    public static Version apiVersion() {
        return Objects.requireNonNull(API_VERSION);
    }

    private static final Version API_VERSION = new Version(0, 1, 0);
}

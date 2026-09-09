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

import java.util.Objects;

/**
 * One configuration entry, with its provenance.
 *
 * @param key the fully-qualified dotted key
 * @param value the raw value, before {@code ${...}} interpolation
 * @param source which kind of source supplied it
 * @param origin a specific location -- a file path, {@code "PRAVAHA_LANES"}, {@code "--lanes"} --
 *     so an operator can go and change the right thing rather than hunting for it
 */
public record ConfigValue(String key, String value, ConfigSource source, String origin) {

    public ConfigValue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
    }

    /** This value with its raw text replaced, keeping the provenance. */
    public ConfigValue withValue(String resolved) {
        return resolved.equals(value) ? this : new ConfigValue(key, resolved, source, origin);
    }

    /**
     * Renders for logs and diagnostics, masking anything that looks like a secret.
     *
     * <p>{@link #toString()} is overridden to call this, so a stray {@code log.info(value)} cannot
     * leak a password. Getting that wrong once is enough to matter.
     */
    public String describe() {
        return key + "=" + Redaction.mask(key, value) + "  (" + source.display() + ": " + origin + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}

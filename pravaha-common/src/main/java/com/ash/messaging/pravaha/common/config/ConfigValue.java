/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

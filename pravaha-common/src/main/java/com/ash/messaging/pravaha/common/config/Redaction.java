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

import java.util.List;
import java.util.Locale;

/**
 * Masks values whose keys suggest they are secret.
 *
 * <p>A deliberately blunt instrument. It matches on the key name, which will occasionally mask
 * something harmless -- and that is the right way for it to fail. The alternative failure, printing
 * a password into a log that is then shipped to a collector, is not recoverable.
 *
 * <p>This is a safety net, not the mechanism: secrets should be resolved from a secret manager and
 * never written into configuration files at all (design section 25).
 */
public final class Redaction {

    private static final List<String> SECRET_MARKERS = List.of(
            "password", "passwd", "secret", "token", "credential", "private-key", "privatekey", "apikey", "api-key");

    /** What a masked value is replaced with. Fixed-width so it does not leak the length. */
    public static final String MASK = "********";

    private Redaction() {}

    /** Whether a key's name suggests its value is sensitive. */
    public static boolean isSecret(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        // "key" alone is far too common to match on -- pravaha.state.key.fields is not a secret --
        // so it counts only as a trailing segment, where it usually does mean a credential.
        return lower.endsWith(".key") || lower.equals("key");
    }

    /** The value, or {@link #MASK} if the key looks sensitive. */
    public static String mask(String key, String value) {
        return isSecret(key) ? MASK : value;
    }
}

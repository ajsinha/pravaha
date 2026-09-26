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

    /**
     * Option keys whose values are credentials or carry them, whatever their length -- wider than
     * {@link #isSecret}, because a connection option's URL, DSN or user name is itself the thing a
     * plugin's exception text leaks.
     */
    private static final java.util.regex.Pattern SENSITIVE_OPTION = java.util.regex.Pattern.compile(
            "(?i).*(pass|secret|token|key|credential|auth|url|uri|dsn|connection|user).*");

    /**
     * {@code text} with every value of every binding option in {@code optionSets} that could be a
     * credential struck out, as {@code [redacted <key>]}.
     *
     * <p>The rule for a plugin's own failure text before it leaves the node (SINK-3, FEED-1): every
     * value of a key that names a credential, at three characters or more, and every value of eight
     * or more whatever its key. Over-redacting a diagnostic costs a word; under-redacting costs a
     * password. Three characters at least, because a one-letter value would strike every occurrence of
     * that letter from the message and leave nothing to read.
     *
     * <p>Written once (SINK-4). The sinks' resolver, the source feeds and the HTTP surface each
     * carried their own copy of this pattern and these two lengths, so the next change to the rule
     * would have reached one of three.
     */
    public static String strikeOptionValues(String text, Iterable<? extends java.util.Map<String, String>> optionSets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (java.util.Map<String, String> options : optionSets) {
            for (java.util.Map.Entry<String, String> option : options.entrySet()) {
                String value = option.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                boolean sensitive = SENSITIVE_OPTION.matcher(option.getKey()).matches();
                if (value.length() >= 8 || (sensitive && value.length() >= 3)) {
                    out = out.replace(value, "[redacted " + option.getKey() + "]");
                }
            }
        }
        return out;
    }
}

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
package com.ash.messaging.pravaha.server.observe;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * A request's correlation id: the one a caller sent, if it is safe to write into a log line, or a
 * fresh one.
 *
 * <p>Safe means short and plain -- letters, digits, dot, dash, underscore, colon, at most 64. A header
 * is written into every log line of the request, and one carrying a newline or a quote could forge a
 * line or break a JSON one; so an id that is not plain is replaced, not escaped. A name the caller did
 * not choose is better than a log that says something they did.
 */
public final class Correlation {

    /** The header a caller sends an id in, and the node answers it in. */
    public static final String HEADER = "X-Correlation-Id";

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Correlation() {}

    /** {@code given} when it is plain, else a new id. */
    public static String idOr(String given) {
        if (given != null && PLAIN.matcher(given).matches()) {
            return given;
        }
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Whether {@code text} is plain enough to carry in a log line as it is. */
    public static boolean plain(String text) {
        return text != null && PLAIN.matcher(text).matches();
    }
}

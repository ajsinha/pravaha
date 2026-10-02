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
package com.ash.messaging.pravaha.api.wire;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * How a failure's {@link ErrorCode} survives the trip from the server to a client.
 *
 * <p>Written for S-4: the server states a refusal precisely -- {@code PRV-4023  this server serves
 * [user_volume]} -- and the Java SDK used to re-stamp every one of them as its own
 * {@code PRV-1041 CLIENT_QUERY_REFUSED}, keeping only the text. An application could then branch on
 * exactly one thing, "something went wrong", and the console recovered the real code by searching
 * the message for {@code PRV-} with a regular expression. A code nobody can read programmatically
 * is a string.
 *
 * <p>Two carriers, because they fail differently. The message already begins with the rendered code
 * ({@link PravahaException} formats it that way), so the number survives any transport that carries
 * a description at all -- that is the one this relies on. The <em>name</em> is not in the message
 * and has to travel separately, in a trailer; a transport or proxy that drops trailers costs the
 * name and nothing else, which is why the number is never read from there.
 *
 * <p>It lives in the API module for the same reason {@link ControlWire} does: both ends need it and
 * neither owns it.
 */
public final class ErrorWire {

    /**
     * The trailer carrying {@link ErrorCode#name()}.
     *
     * <p>Lower case and {@code x-} prefixed because gRPC metadata keys are ASCII and lower-cased in
     * transit; a key with an upper-case letter in it is rejected by the transport rather than
     * ignored.
     */
    public static final String NAME_HEADER = "x-pravaha-error-name";

    /**
     * The name given to a recovered code when the trailer did not arrive.
     *
     * <p>Deliberately not a guess at the real name. A caller branches on {@link ErrorCode#number()}
     * or {@link ErrorCode#code()}, both of which are exact here; inventing a plausible-looking name
     * would make a wrong one indistinguishable from a right one in a log.
     */
    private static final String UNNAMED = "SERVER_REPORTED";

    /** {@code PRV-nnnn} at the very start, and only there: a code mentioned mid-message is prose. */
    private static final Pattern LEADING_CODE = Pattern.compile("^PRV-(\\d{4})(?:\\s|$)");

    private ErrorWire() {}

    /**
     * The code a server reported, or empty when it reported none.
     *
     * <p>Empty is a real answer and not a failure: a gRPC status the server never produced -- a
     * connection refused, a channel shut down, a proxy's own 503 -- has no Pravaha code in it, and
     * the caller has to say something different about those. Guessing one here is how
     * {@code PRV-1041} came to mean "the server refused this" and "there was no server".
     *
     * @param description the failure text as it arrived, usually {@code PravahaException.getMessage()};
     *     null reads as no code
     * @param name the {@link #NAME_HEADER} trailer, or null if the transport carried none
     */
    public static Optional<ErrorCode> recover(@Nullable String description, @Nullable String name) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher matcher = LEADING_CODE.matcher(description);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int number = Integer.parseInt(matcher.group(1));
        if (number < 1000 || number > 9999) {
            return Optional.empty();
        }
        return Optional.of(new ErrorCode(number, name == null || name.isBlank() ? UNNAMED : name));
    }
}

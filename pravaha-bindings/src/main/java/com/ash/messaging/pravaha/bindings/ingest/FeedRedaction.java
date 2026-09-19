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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Strikes a source binding's option values out of the failure a stopped feed records.
 *
 * <p>FEED-1 put that failure's message on every surface that describes a query -- the HTTP API, the
 * Flight listing and so {@code pravaha queries}, both SDKs, the console. It is the plugin's own
 * exception text, and a plugin that echoes its connection string or password into an exception is
 * common enough that the text cannot be trusted not to. Done once, where the failure is recorded,
 * so no surface can forget; the HTTP API's own redaction of sink options runs over it again.
 *
 * <p>The same rule as the server's sink redaction: every value of a key that names a credential, and
 * every value long enough to be one whatever its key. Over-redacting a diagnostic costs a word;
 * under-redacting costs a password. The stream and partition a stop reports say which binding it
 * was, which is what a redacted path would have said.
 */
final class FeedRedaction {

    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i).*(pass|secret|token|key|credential|auth|url|uri|dsn|connection|user).*");

    private FeedRedaction() {}

    /** {@code failure} with its message redacted, or {@code failure} itself when nothing needed striking. */
    static PravahaException redact(PravahaException failure, Collection<SourceBinding> bindings) {
        String message = failure.getMessage();
        String redacted = redact(message, bindings);
        if (redacted == null || redacted.equals(message)) {
            return failure;
        }
        // The cause is kept for the log, which is the operator's own; what leaves the node is the
        // message, and that is what this changes.
        return new PravahaException(failure.errorCode(), redacted, failure);
    }

    static String redact(String text, Collection<SourceBinding> bindings) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (SourceBinding binding : bindings) {
            for (Map.Entry<String, String> option : binding.options().entrySet()) {
                String value = option.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                boolean sensitive = SENSITIVE_KEY.matcher(option.getKey()).matches();
                if (value.length() >= 8 || (sensitive && value.length() >= 3)) {
                    out = out.replace(value, "[redacted " + option.getKey() + "]");
                }
            }
        }
        return out;
    }
}

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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.Map;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Dead-letters a row whose evaluation fails before it touched any state (DLQPROJ-1).
 *
 * <p>A filter, a projection and a computed column hold nothing: a row that throws in them has
 * changed nothing yet, so leaving it out is exact -- the view is what it would have been had the
 * row never arrived, and the row is kept, with the reason, in the query's dead-letter queue. Once a
 * row has been handed to something that holds state -- an aggregate, a window, a join, a top-N, a
 * lookup, the view itself -- that is no longer true: the state has taken the row, and an overflow
 * above it (a {@code HAVING}, a projection of an aggregate) cannot be undone by dropping the row, so
 * it stops the query as before. {@link #boundary} marks that crossing; {@link #guard} decides.
 *
 * <p>Only {@link ArithmeticException}: what the expression tree throws for a division by zero and
 * every overflow. Anything else is a bug or a resource failure, not something wrong with the row.
 * With no sink attached the guard rethrows, which is the old behaviour exactly: without somewhere
 * durable to put a row, leaving it out would be dropping it.
 *
 * <p>Lane-confined: one pipeline runs on one lane thread, so {@link #crossed} needs no fence. The
 * sink is volatile because it is attached from the thread that opens the query's feed.
 */
final class RowGuard {

    private volatile RowFailureSink sink;

    /** Whether the row in hand has reached an operator that holds state. */
    private boolean crossed;

    void sendTo(RowFailureSink failures) {
        this.sink = failures;
    }

    /** {@code head}, with a failure before any state dead-lettered when a sink is attached. */
    RowProcessor guard(String stream, StreamSchema schema, RowProcessor head) {
        return row -> {
            RowFailureSink failures = sink;
            if (failures == null) {
                head.process(row);
                return;
            }
            crossed = false;
            try {
                head.process(row);
            } catch (ArithmeticException failure) {
                if (crossed) {
                    throw failure;
                }
                failures.reject(stream, schema, json(RowText.of(row, schema)), failure);
            }
        };
    }

    /** {@code stateful}, marking that the row in hand has reached state. */
    RowProcessor boundary(RowProcessor stateful) {
        return new RowProcessor() {
            @Override
            public void process(com.ash.messaging.pravaha.api.data.RowView row) {
                crossed = true;
                stateful.process(row);
            }

            @Override
            public void finish() {
                stateful.finish();
            }
        };
    }

    private static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            quote(out, value.getKey()).append(':');
            quote(out, value.getValue());
        }
        return out.append('}').toString();
    }

    private static StringBuilder quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"');
    }
}

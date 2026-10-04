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
package com.ash.messaging.pravaha.sdk.flight;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.flight.FlightStream;

/**
 * Bounds how long a stream may take to open, and nothing after that (SDKDEADLINE-1).
 *
 * <p>A gRPC deadline bounds a whole call, which is right for a request and wrong for a subscription:
 * a subscription is a call meant to run for hours, and a query result read slowly is not a server
 * that stalled. What a stream does share with a request is its opening -- the server either answers
 * with a schema or it does not -- so that is what this bounds. When the deadline passes with no
 * schema, the stream is cancelled; whoever is waiting for it (the {@link QueryResult} constructor,
 * {@link Subscription#awaitOpen()} or {@link Subscription#run()}) sees the cancellation, and {@link
 * #expired()} lets the client report it as the deadline it was rather than as a cancellation nobody
 * asked for.
 *
 * <p>Scheduled on the JDK's shared delay thread rather than on a thread of this client's own: one
 * short task per stream, which does nothing at all when the schema arrived in time.
 */
final class OpenDeadline implements Runnable {

    private final FlightStream stream;
    private volatile boolean expired;

    private OpenDeadline(FlightStream stream) {
        this.stream = stream;
    }

    /** Starts the clock on {@code stream}'s opening. */
    static OpenDeadline arm(FlightStream stream, Duration deadline) {
        OpenDeadline opening = new OpenDeadline(stream);
        CompletableFuture.delayedExecutor(deadline.toNanos(), TimeUnit.NANOSECONDS)
                .execute(opening);
        return opening;
    }

    /** Whether the deadline passed before the stream opened, and the stream was cancelled for it. */
    boolean expired() {
        return expired;
    }

    @Override
    public void run() {
        // hasRoot() is also true once the stream has failed: a refused stream is answered, not late.
        if (!stream.hasRoot()) {
            expired = true;
            try {
                stream.cancel("not opened within the request deadline", null);
            } catch (RuntimeException alreadyGone) {
                // Closed in the meantime; nobody is waiting for it.
            }
        }
    }
}

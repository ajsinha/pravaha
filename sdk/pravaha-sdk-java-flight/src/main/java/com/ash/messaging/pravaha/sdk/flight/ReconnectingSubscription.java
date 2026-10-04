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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * A subscription that opens itself again when a server restart ends it.
 *
 * <p>A node that restarts ends every stream it serves, and a plain {@link Subscription} then ends
 * with {@code PRV-1040}. This one reopens instead: after the stream ends, after a retryable
 * failure, after {@code PRV-6105} (the subscriber fell too far behind), and after a stream that
 * broke with no diagnosis from the engine -- the transport going away under it. It retries with
 * backoff from 250 ms up to 10 s, for at most {@link Reconnect#giveUpAfter} without a stream open.
 * A refusal that will not change -- the view was dropped, a filter names no column -- is thrown at
 * once.
 *
 * <p>Opened from a snapshot ({@link PravahaFlightClient#subscribeFromSnapshot(String, java.util.Map,
 * Reconnect, Consumer)}), the first batch after reopening is a fresh snapshot: replace what you
 * hold with it and nothing is lost or counted twice. A plain one resumes at the next commit, and
 * what was committed while the stream was down is not delivered. Either way {@link
 * Reconnect#onReconnected} runs before that first batch.
 */
public final class ReconnectingSubscription implements AutoCloseable {

    /** How a subscription reconnects. */
    public record Reconnect(@Nullable Duration giveUpAfter, Runnable onReconnected) {

        /** Five minutes without a stream, and nothing told of a reconnection. */
        public static Reconnect defaults() {
            return new Reconnect(Duration.ofMinutes(5), () -> {});
        }

        /** Never give up. */
        public static Reconnect forever() {
            return new Reconnect(null, () -> {});
        }

        public Reconnect onReconnected(Runnable callback) {
            return new Reconnect(giveUpAfter, callback);
        }
    }

    static final long FIRST_DELAY_MILLIS = 250;
    static final long MAX_DELAY_MILLIS = 10_000;

    /** The engine's code for a subscriber ended because it fell behind. */
    private static final int FELL_BEHIND = 6105;

    private final Function<Consumer<ChangeBatch>, Subscription> open;
    private final Consumer<ChangeBatch> onBatch;
    private final Reconnect reconnect;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Subscription> current = new AtomicReference<>();
    private final AtomicInteger reconnections = new AtomicInteger();

    ReconnectingSubscription(
            Function<Consumer<ChangeBatch>, Subscription> open, Consumer<ChangeBatch> onBatch, Reconnect reconnect) {
        this.open = open;
        this.onBatch = onBatch;
        this.reconnect = reconnect == null ? Reconnect.defaults() : reconnect;
    }

    /** Delivers batches, reopening the stream as needed, until closed. Blocks. */
    public void run() {
        long delay = FIRST_DELAY_MILLIS;
        long downSince = -1;
        boolean reopened = false;
        while (!closed.get()) {
            boolean streaming = false;
            boolean[] announce = {reopened};
            Consumer<ChangeBatch> delivery = batch -> {
                if (announce[0]) {
                    announce[0] = false;
                    reconnect.onReconnected().run();
                }
                onBatch.accept(batch);
            };
            try {
                Subscription subscription = open.apply(delivery);
                current.set(subscription);
                if (closed.get()) {
                    subscription.close();
                    return;
                }
                subscription.awaitOpen();
                streaming = true;
                downSince = -1;
                delay = FIRST_DELAY_MILLIS;
                subscription.run();
                if (closed.get()) {
                    return;
                }
                // Ended without an error: what a node shutting down does. Wait a moment and see.
                pause(FIRST_DELAY_MILLIS);
            } catch (PravahaClientException failure) {
                if (closed.get()) {
                    return;
                }
                int code = failure.errorCode().number();
                boolean undiagnosed = streaming && code == ClientErrors.READ_FAILED.number();
                if (!(failure.retryable() || code == FELL_BEHIND || undiagnosed)) {
                    throw failure;
                }
                long now = System.nanoTime();
                if (downSince < 0) {
                    downSince = now;
                } else if (reconnect.giveUpAfter() != null
                        && now - downSince >= reconnect.giveUpAfter().toNanos()) {
                    throw failure;
                }
                pause(delay);
                delay = Math.min(delay * 2, MAX_DELAY_MILLIS);
            }
            reopened = true;
            reconnections.incrementAndGet();
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed.set(true);
        }
    }

    /** How many times the stream has been opened again. */
    public int reconnections() {
        return reconnections.get();
    }

    /** Ends the subscription and stops reconnecting. */
    @Override
    public void close() {
        closed.set(true);
        Subscription subscription = current.get();
        if (subscription != null) {
            subscription.close();
        }
    }
}

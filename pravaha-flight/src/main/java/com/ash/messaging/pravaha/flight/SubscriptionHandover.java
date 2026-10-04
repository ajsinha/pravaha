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
package com.ash.messaging.pravaha.flight;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;

/**
 * What stands between the engine's commit thread and one subscriber's socket.
 *
 * <p>Two decisions, kept together because they are two halves of "what happens to a subscriber
 * that cannot keep up": the buffer the subscriber <em>asked</em> for, which is applied inside the
 * engine before a change is ever handed over, and the hand-over's own bound, which is applied
 * after. The first is the subscriber's choice and the second is the node protecting itself.
 *
 * <p>Out of {@code PravahaFlightSqlProducer} because that file is at its size limit and because
 * neither of these is about Flight: they are policy, and policy that a test should be able to ask
 * about without standing up a server.
 */
final class SubscriptionHandover {

    /**
     * Committed batches that may wait for a subscriber's socket.
     *
     * <p>Small on purpose. This queue exists to decouple the engine's thread from the network, not
     * to be a buffer -- {@link SubscriptionOptions} already decides how far behind a subscriber may
     * fall, with a policy the subscriber chose. A deep queue here would silently override that
     * choice with a different one.
     */
    static final int BATCHES = 64;

    /**
     * And how many rows those batches may hold between them (STRM-15).
     *
     * <p>The batch bound above is not a bound on memory, and it was the only thing between a
     * stalled client and the node's heap. A batch is one commit, and a commit under a real feed was
     * measured at up to 2830 rows, so 64 of them is 64 &times; whatever a commit happens to be --
     * in {@code ViewChange} objects, their {@code Object[]} payloads and the strings inside those.
     * One stalled subscriber took the server's RSS from 1577 MB to 3262 MB; ten would not have fit
     * on the machine that measured one.
     *
     * <p>250 000 rows is a bound somebody can reason about: at a hundred bytes a row it is tens of
     * megabytes per stalled subscriber rather than gigabytes, and it is far above anything a
     * subscriber that is keeping up will ever have queued. Whichever bound is reached first drops
     * the batch, and the drop is now reported to the subscriber rather than only to an audit sink
     * (STRM-10).
     */
    static final long ROWS = 250_000;

    private SubscriptionHandover() {}

    /**
     * Whether a commit of {@code batchRows} rows may join a hand-over already holding
     * {@code queuedRows}.
     *
     * <p>Separate from the queue's own capacity, and the point of STRM-15: the queue counts
     * <em>batches</em>, and 64 batches is not a quantity of memory. Both bounds apply and the first
     * one reached drops the batch.
     */
    static boolean hasRoomFor(long queuedRows, int batchRows) {
        return queuedRows + batchRows <= ROWS;
    }

    /**
     * The server-side buffer a remote subscriber asked for, or this node's default (STRM-16).
     *
     * <p>Every remote subscriber used to be {@code SubscriptionOptions.DEFAULT} --
     * {@code (10 000, CONFLATE)} -- whatever it asked for, because the ticket carried nothing and
     * {@code ClientOptions.subscriberBufferRows} and {@code conflateOnOverflow} had no reader
     * anywhere. {@code CONFLATE} is the default and, by its own javadoc, "wrong for anything
     * maintaining its own aggregate from the weights": fed {@code +1 10, -1 10, +1 30, -1 30,
     * +1 60} on one key under a buffer of two it delivers a weighted sum of 50 where the
     * un-conflated truth is 60, and until STRM-10 that was silent from the client's side as well.
     *
     * <p>An overflow this server does not know is a refusal, not a default. A client that asked
     * for {@code FAIL} and was quietly given {@code CONFLATE} is exactly the corruption above.
     */
    static SubscriptionOptions optionsOf(ControlWire.@Nullable SubscriberPreference preference) {
        if (preference == null) {
            return SubscriptionOptions.DEFAULT;
        }
        try {
            return SubscriptionOptions.of(
                    preference.bufferRows(),
                    preference.overflow().isEmpty()
                            ? SubscriptionOptions.Overflow.CONFLATE
                            : SubscriptionOptions.Overflow.valueOf(preference.overflow()));
        } catch (IllegalArgumentException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE,
                    "'" + preference.overflow() + "' is not an overflow policy this server knows. It is "
                            + "CONFLATE (the latest value per key wins), DROP_OLDEST or FAIL (end this "
                            + "subscription rather than lose a change). Refused rather than defaulted: a "
                            + "subscriber that asked to be failed and was quietly conflated instead is the "
                            + "corrupted total this setting exists to avoid.");
        }
    }
}

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
package com.ash.messaging.pravaha.api.plugin;

/**
 * Where an alert's notifications go (ADR-057): a webhook, a log, and later e-mail, Slack, Teams or
 * PagerDuty.
 *
 * <p>The sink SPI's shape, for the same reasons: discovered by {@code ServiceLoader} under the name it
 * reports, configured from a binding ({@code pravaha.notifiers.<channel>}) before it is opened, and
 * never handed a secret in its configuration -- a binding names where the secret is ({@code secret-env},
 * {@code secret-file}), never the secret itself (ADR-052).
 *
 * <p><strong>The contract is at-least-once.</strong> The engine keeps an alert's state exactly once
 * (journalled before anything is sent), and delivers each notification until a channel accepts it; a
 * restart between the send and the record of it sends it again. So every notification carries an
 * {@link Notification#idempotencyKey()} that is the same on every attempt, across retries and restarts,
 * and a receiver that must not act twice de-duplicates on it. A plugin passes it on to the receiver in
 * whatever form the receiver understands (a header, a field, a dedup key).
 *
 * <p>One instance serves every alert naming the channel, from the engine's delivery threads, so {@link
 * #send} must be safe to call concurrently. It may retry inside one call (the webhook does, with
 * backoff); the engine retries again later, at a slower pace, whatever {@link #send} could not deliver.
 */
public interface NotifierPlugin extends PravahaPlugin {

    /**
     * Delivers one notification, or says why it could not.
     *
     * <p>Never throws for a delivery failure: a refusal, a timeout or a network error is a {@link
     * Delivery} with {@code delivered == false}, and the engine records it on the alert and tries again.
     */
    Delivery send(Notification notification);

    /**
     * What one {@link #send} came to.
     *
     * @param delivered whether the receiver accepted it
     * @param attempts how many attempts the plugin made inside this call
     * @param detail what the receiver or the network said: a status, an error; never a secret
     */
    record Delivery(boolean delivered, int attempts, String detail) {

        public Delivery {
            detail = detail == null ? "" : detail;
        }

        public static Delivery delivered(int attempts, String detail) {
            return new Delivery(true, attempts, detail);
        }

        public static Delivery failed(int attempts, String detail) {
            return new Delivery(false, attempts, detail);
        }
    }
}

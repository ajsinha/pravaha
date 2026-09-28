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
package com.ash.messaging.pravaha.registry.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What an alert shows a reader who may see it (ADR-057): SQL's {@code SHOW ALERTS}, REST, the console. */
public final class AlertStatus {

    private AlertStatus() {}

    /**
     * One alert.
     *
     * @param state {@code ACTIVE}, {@code PAUSED} or {@code SNOOZED}
     * @param following {@code FOLLOWING} the view, {@code WAITING} for a view that is not registered (a
     *     restart where the query did not come back), or {@code BROKEN} with {@link #problem}
     * @param firing keys firing now
     * @param pending keys in the condition that have not been in it for {@code fire_after} yet
     * @param deliveryError the last notification a channel did not accept, while it is still owed
     */
    public record Summary(
            String name,
            String view,
            String tenant,
            String owner,
            String state,
            String following,
            String condition,
            List<String> channels,
            String severity,
            Map<String, String> options,
            Instant snoozedUntil,
            int firing,
            int pending,
            int keys,
            Instant lastNotificationAt,
            String deliveryError,
            String problem,
            Instant createdAt,
            Instant updatedAt,
            String updatedBy) {}

    /**
     * One key's state.
     *
     * @param state {@code PENDING} (in the condition, waiting out {@code fire_after}), {@code FIRING},
     *     {@code CLEARING} (firing, out of the condition, waiting out {@code clear_after}) or {@code CLEARED}
     * @param episode the current or last firing of this key, from 1
     * @param fired how many times it has fired
     * @param notified what the channels were last told: {@code FIRED}, {@code CLEARED}, or {@code NONE}
     * @param owed what they are owed and not yet told, or null when they are up to date
     */
    public record KeyStatus(
            Map<String, Object> key,
            String state,
            long episode,
            long fired,
            Instant since,
            Instant firingSince,
            Instant clearedAt,
            String notified,
            Instant lastNotifiedAt,
            String owed,
            int reminders,
            String acknowledgedBy,
            Instant acknowledgedAt,
            Map<String, Object> row) {}

    /**
     * One notification the engine sent, or tried to.
     *
     * @param outcome {@code DELIVERED}, or {@code FAILED} with the {@code detail} (it is sent again)
     */
    public record Sent(
            Instant at,
            Map<String, Object> key,
            String kind,
            long episode,
            String idempotencyKey,
            List<String> channels,
            String outcome,
            String detail) {}

    /** An alert with every key it holds and its recent notifications, newest first. */
    public record Detail(Summary alert, List<KeyStatus> keys, List<Sent> notifications) {}
}

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;

/**
 * A subscription ticket, read: the view, whether it starts from a snapshot, whether it follows the
 * answer rather than the changelog, its tap filter and its buffer preference.
 *
 * <p>Out of {@code PravahaFlightSqlProducer} because that file is at its size limit, and because
 * reading a ticket is wire format rather than serving: a test can ask what a ticket means without
 * standing up a server.
 *
 * @param fields the ticket's fields, verbatim, for the audit record
 * @param view the view subscribed to
 * @param fromSnapshot whether the stream starts from the view's state (SUB-1)
 * @param answer whether each commit is how the answer changed rather than what was applied
 *     (SUBANSWERWIRE-1, KEYEDWT-1)
 * @param equals the tap filter, column to value
 * @param preference the buffer this subscriber asked for, or null for the node's default (STRM-16)
 */
record SubscriptionTicket(
        List<String> fields,
        String view,
        boolean fromSnapshot,
        boolean answer,
        Map<String, Object> equals,
        ControlWire.SubscriberPreference preference) {

    /** Reads a ticket, refusing one that is not a subscription's. */
    static SubscriptionTicket read(byte[] ticket) {
        List<String> fields = ControlWire.decode(ticket);
        if (fields.size() < 2 || !ControlWire.isSubscribeVerb(fields.get(0))) {
            throw new PravahaException(FlightErrors.BAD_HANDLE, "this is not a subscription ticket");
        }
        // An odd tail is this subscriber's buffer preference, riding last (STRM-16). Filter pairs
        // are alternating column and value, so their count is even; one more field makes it odd,
        // which is why a preference can be added without a version bump and with no chance of a
        // filter column being read as a policy.
        int pairsEnd = fields.size();
        ControlWire.SubscriberPreference preference = null;
        if ((fields.size() - 2) % 2 == 1) {
            preference = ControlWire.SubscriberPreference.decode(fields.get(fields.size() - 1));
            pairsEnd = fields.size() - 1;
        }
        Map<String, Object> equals = new LinkedHashMap<>();
        for (int i = 2; i + 1 < pairsEnd; i += 2) {
            equals.put(fields.get(i), fields.get(i + 1));
        }
        String verb = fields.get(0);
        return new SubscriptionTicket(
                fields,
                fields.get(1),
                ControlWire.startsFromSnapshot(verb),
                ControlWire.followsAnswer(verb),
                equals,
                preference);
    }

    /** The plain subscription's options: the buffer asked for, following the answer when asked to. */
    SubscriptionOptions options() {
        SubscriptionOptions chosen = SubscriptionHandover.optionsOf(preference);
        return answer ? chosen.followingTheAnswer() : chosen;
    }

    /**
     * A snapshot subscription's options: never conflated nor dropped within a commit -- the client is
     * keeping a copy -- and following the answer when asked to.
     */
    SubscriptionOptions snapshotOptions() {
        SubscriptionOptions exact = SubscriptionOptions.of(Integer.MAX_VALUE, SubscriptionOptions.Overflow.FAIL);
        return answer ? exact.followingTheAnswer() : exact;
    }

    /** The filter as the audit record shows it. */
    String filterText() {
        return fields.size() > 2 ? String.join("=", fields.subList(2, fields.size())) : "no filter";
    }
}

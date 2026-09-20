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
package com.ash.messaging.pravaha.registry;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Why a subscription ended, when the engine ended it rather than the subscriber (STRM-12).
 *
 * <p>Three events used to be one signal on the wire -- an administrative drop, a node shutting
 * down, and the client's own {@code close()} all arrived as a clean completion -- and only the
 * last of the three is an ending a client should accept. They are written here together, in one
 * short file, because the difference between them <em>is</em> the fix: a reader comparing two
 * sentences three hundred lines apart in two classes cannot see whether they say different things.
 *
 * <p>The fourth ending, a cutover's {@code VIEW_REPLACED}, is ADR-046's and stays with the
 * replacement that raises it.
 */
final class SubscriptionEndings {

    private SubscriptionEndings() {}

    /**
     * The name this subscription was opened under has been dropped.
     *
     * <p>Not coming back, which is why {@code FlightErrors} sends it as {@code NOT_FOUND}: the
     * client should stop, not reconnect. What it received is complete up to the drop.
     */
    static PravahaException dropped(String name) {
        return new PravahaException(
                RegistryErrors.QUERY_DROPPED,
                "'" + name + "' has been dropped and no longer exists on this node, so there are no more "
                        + "changes to it. What you received up to here is complete.");
    }

    /**
     * The node is going down, and the query is not.
     *
     * <p>Sent as {@code UNAVAILABLE}, which every gRPC client already retries: the registration is
     * journalled, comes back {@code RUNNING} when the node does, and a read of the view catches the
     * subscriber up on the commits it missed. A clean completion here read as "this stream is
     * finished", so a client that believed it stopped, kept the rows it had, and never learned the
     * view had moved on without it.
     */
    static PravahaException nodeStopping(String name) {
        return new PravahaException(
                RegistryErrors.NODE_STOPPING,
                "this node is shutting down, so '" + name + "' stops sending here. The query itself is "
                        + "journalled and comes back when the node does; subscribe again then, and read "
                        + "the view to catch up on what happened in between.");
    }
}

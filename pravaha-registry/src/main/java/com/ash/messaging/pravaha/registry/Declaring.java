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

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What the registration in progress declares beyond its SQL: the equality indexes its view keeps
 * (ADR-055) and whether it keeps a lane of its own ({@code lane = 'dedicated'}). Held by {@link
 * QueryRegistry} for the length of one call, so neither has to be threaded through every overload
 * of {@code register}.
 *
 * @param indexes output ordinals an equality index is kept over
 * @param dedicatedLane the query runs on a lane of its own whatever the node's lane-sharing mode
 */
record Declaring(List<Integer> indexes, boolean dedicatedLane, boolean ownLane) {

    /** A registration that declares neither. */
    static final Declaring NOTHING = new Declaring(List.of(), false);

    Declaring {
        indexes = List.copyOf(indexes);
    }

    Declaring(List<Integer> indexes, boolean dedicatedLane) {
        this(indexes, dedicatedLane, false);
    }

    /** Whether the computation this starts must not be placed on a shared lane. */
    boolean ownsALane() {
        return dedicatedLane || ownLane;
    }

    /**
     * Whether a replacement declaring this, over the same computation as {@code existing}, is a move
     * between lanes: {@code existing} is the version serving the name being replaced, and the lane
     * choice differs. Anything else with the same fingerprint would cut over to itself.
     */
    boolean moves(RegisteredQuery existing, RegisteredQuery serving) {
        return existing == serving
                && (existing.dedicatedLane() != dedicatedLane
                        || (ownLane && existing.sharedLane().isPresent()));
    }

    /** A computation just started for this registration, marked dedicated when it asked to be. */
    RegisteredQuery placed(RegisteredQuery started) {
        if (dedicatedLane) {
            started.dedicateLane();
        }
        return started;
    }

    /**
     * A dedicated registration joining a computation that is already running under another name.
     *
     * <p>Registrations that ask the same question share one computation, so the lane is the
     * computation's and not the name's. On a lane of its own already, the computation is marked
     * dedicated from now on -- and every name it answers to is journalled so, because a restart
     * replays the first registrant first, and under lane sharing that one would otherwise land on a
     * shared lane and the dedicated name would then be refused. On a shared lane it is refused:
     * running queries are never moved, and quietly joining would be a setting somebody believes is
     * in force.
     */
    void join(String name, RegisteredQuery existing, boolean onSharedLane, RegistryJournal journal) {
        if (!dedicatedLane || existing.dedicatedLane()) {
            return;
        }
        if (onSharedLane) {
            String running = existing.names().iterator().next();
            throw new PravahaException(
                    RegistryErrors.OPTION_UNKNOWN,
                    "'" + name + "' asks for lane = 'dedicated', and it is the same computation as '" + running
                            + "', which is already running on a shared lane. Registrations that ask the same "
                            + "question share one computation, and a running query is never moved between "
                            + "lanes. Register it with lane = 'shared' to share that computation, or drop '"
                            + running + "' and register again with lane = 'dedicated'.");
        }
        existing.dedicateLane();
        if (journal != null) {
            existing.names().forEach(journal::recordDedicatedLane);
        }
    }
}

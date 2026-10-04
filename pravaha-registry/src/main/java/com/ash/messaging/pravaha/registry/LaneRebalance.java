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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

/**
 * An administrator's lane rebalance: moves queries off shared lanes onto lanes of their own while
 * the node has room under {@code auto-from}, one at a time, and only when asked.
 *
 * <p>Placement is never changed behind anybody's back. Under {@code auto} the first {@code
 * auto-from} computations own a lane and later ones share; drop some of the first and the room they
 * leave is taken only by new registrations. This hands it to queries already sharing, oldest first.
 *
 * <p>Each move is a blue/green replacement with the SQL unchanged and {@code lane = 'own'} (ADR-046):
 * the new version starts on a lane of its own, reads the history, cuts over at a position both
 * versions have consumed exactly, and the old one is released -- so no answer is lost or counted
 * twice. One at a time because each move briefly runs the query twice. A move a replacement refuses
 * (a source that cannot replay its history, say) is reported and the rebalance goes on.
 */
public final class LaneRebalance {

    /** How long one move may take before the rebalance gives up on it and goes on. */
    private static final Duration MOVE_LIMIT = Duration.ofMinutes(30);

    /** One query in a plan or a run. */
    public record Move(String name, int fromSharedLane, String status, String detail) {}

    /** What a rebalance would do, or is doing. */
    public record Plan(
            String mode,
            @Nullable Integer autoFrom,
            int ownLaneQueries,
            int room,
            boolean running,
            @Nullable Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable String startedBy,
            List<Move> moves) {}

    private @Nullable Plan last;
    private @Nullable Thread worker;

    /** What a rebalance would do now; changes nothing. */
    public synchronized Plan plan(QueryRegistry registry) {
        return planned(registry, null, null);
    }

    /** The plan of the rebalance running or last run, or a fresh plan when there has been none. */
    public synchronized Plan status(QueryRegistry registry) {
        return last != null ? last : plan(registry);
    }

    /** Starts the moves on a thread of their own and answers the plan it is working through. */
    public synchronized Plan start(QueryRegistry registry, Principal principal) {
        if (worker != null && worker.isAlive()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "a lane rebalance is already running; follow it with GET /api/v1/lanes/rebalance");
        }
        last = planned(registry, Instant.now(), principal.id());
        List<Move> moves = last.moves();
        worker = Thread.ofVirtual().name("pravaha-lane-rebalance").start(() -> run(registry, principal, moves));
        return last;
    }

    private Plan planned(QueryRegistry registry, @Nullable Instant startedAt, @Nullable String startedBy) {
        int ceiling = registry.maxQueriesPerSharedLane();
        int from = registry.sharingFrom();
        String mode = ceiling == 0 ? "false" : from > 0 ? "auto" : "true";
        int own = registry.queriesOnOwnLanes();
        int room = mode.equals("auto") ? Math.max(0, from - own) : 0;
        List<Move> moves = new ArrayList<>();
        for (RegisteredQuery query : registry.queries()) {
            if (moves.size() >= room) {
                break;
            }
            Optional<Integer> lane = query.sharedLane();
            if (lane.isEmpty() || query.state().isTerminal()) {
                continue;
            }
            String name = query.names().iterator().next();
            if (query.names().size() > 1) {
                moves.add(new Move(
                        name,
                        lane.get(),
                        "skipped",
                        "answers to " + query.names().size() + " names, and a replacement moves one name"));
                room++;
            } else if (registry.replacements().isReplacing(name)) {
                moves.add(new Move(name, lane.get(), "skipped", "a replacement of it is already running"));
                room++;
            } else {
                moves.add(new Move(name, lane.get(), startedAt == null ? "planned" : "waiting", ""));
            }
        }
        return new Plan(
                mode,
                mode.equals("auto") ? from : null,
                own,
                Math.max(0, from - own),
                startedAt != null,
                startedAt,
                null,
                startedBy,
                List.copyOf(moves));
    }

    private void run(QueryRegistry registry, Principal principal, List<Move> moves) {
        for (int index = 0; index < moves.size(); index++) {
            Move move = moves.get(index);
            if (!move.status().equals("waiting")) {
                continue;
            }
            record(index, move, "moving", "");
            try {
                RegisteredQuery query = registry.require(move.name());
                ReplacementOptions options = ReplacementOptions.defaults()
                        .withLane(ReplacementOptions.Lane.OWN)
                        .withCutover(ReplacementOptions.Cutover.AUTO);
                registry.replacements()
                        .replace(move.name(), query.sql(), query.view().keyOrdinals(), principal, options);
                record(index, move, awaitCutover(registry, move.name(), principal), "");
            } catch (RuntimeException e) {
                record(index, move, "failed", e.getMessage());
            }
        }
        synchronized (this) {
            Plan was = java.util.Objects.requireNonNull(last, "recorded once planned");
            last = new Plan(
                    was.mode(),
                    last.autoFrom(),
                    registry.queriesOnOwnLanes(),
                    last.room(),
                    false,
                    last.startedAt(),
                    Instant.now(),
                    last.startedBy(),
                    last.moves());
        }
    }

    /** Waits for the move's cutover, then releases the old version; answers how it ended. */
    private static String awaitCutover(QueryRegistry registry, String name, Principal principal) {
        long deadline = System.nanoTime() + MOVE_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<QueryReplacement.Status> status = registry.replacements().of(name);
            if (status.isEmpty()) {
                return "failed";
            }
            switch (status.get().state()) {
                case CUT_OVER -> {
                    registry.replacements().finish(name, principal);
                    return "moved";
                }
                case FINISHED -> {
                    return "moved";
                }
                case FAILED, ABANDONED, ROLLED_BACK -> {
                    return "failed";
                }
                default -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "failed";
                    }
                }
            }
        }
        registry.replacements().abandon(name, principal);
        return "failed";
    }

    private synchronized void record(int index, Move move, String status, @Nullable String detail) {
        List<Move> moves = new ArrayList<>(
                java.util.Objects.requireNonNull(last, "recorded once planned").moves());
        moves.set(index, new Move(move.name(), move.fromSharedLane(), status, detail == null ? "" : detail));
        last = new Plan(
                last.mode(),
                last.autoFrom(),
                last.ownLaneQueries(),
                last.room(),
                last.running(),
                last.startedAt(),
                last.finishedAt(),
                last.startedBy(),
                List.copyOf(moves));
    }

    /** Waits for a running rebalance; for tests. */
    public void awaitIdle(Duration limit) throws InterruptedException {
        Thread running;
        synchronized (this) {
            running = worker;
        }
        if (running != null) {
            running.join(limit);
        }
    }
}

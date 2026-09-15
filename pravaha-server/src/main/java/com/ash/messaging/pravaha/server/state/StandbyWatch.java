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
package com.ash.messaging.pravaha.server.state;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.common.io.StateOwnership;

/**
 * A process that holds no lanes, waiting for a node's state to become free so it can take over.
 *
 * <p>HA without distribution (ADR-035, item 3). There is no consensus here, no membership protocol
 * and no Ratis: a standby is a second process configured with the <em>same</em> {@code
 * pravaha.node.id} as the primary, and the thing it waits on is the ownership marker the primary
 * refreshes on a lease.
 *
 * <p>That is deliberate rather than convenient. {@link StateOwnership} already distinguishes "our
 * node id, claim expired" -- a crash restart, taken over automatically -- from "our node id, claim
 * live", which is refused. A standby is exactly the first case, asked from outside and waited for
 * rather than discovered at startup. Adding a separate election would mean two mechanisms deciding
 * the same question, which is how they come to disagree.
 *
 * <h2>What a takeover is worth, stated so nobody has to infer it</h2>
 *
 * <p>It buys <strong>recovery time</strong>, not continuity. The standby resumes from the newest
 * checkpoint, so everything the primary processed after that checkpoint is not in the state it
 * starts from — it has to be replayed from the source offsets the checkpoint carries, and anything
 * the source can no longer supply is gone. A handover that says "failover complete" and nothing else
 * invites the reader to assume none of that happened, so {@link Takeover} carries the gap and the
 * node logs it at the moment of promotion.
 *
 * <p>Split-brain is prevented by the lease, not by this class: the standby cannot claim while the
 * primary keeps refreshing, and if the primary is alive but unable to write its marker it will see
 * its own refresh failing and say so. The window where both believe they own the state is the lease,
 * which is why the lease is generous and the refresh frequent.
 */
public final class StandbyWatch implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(StandbyWatch.class.getName());

    /** How often the marker is read while waiting. Cheap: one small file, and only while standing by. */
    public static final Duration DEFAULT_POLL = Duration.ofSeconds(2);

    /** What a promotion inherited, and what it did not. */
    public record Takeover(String previousOwner, Duration since, Optional<String> lostSince) {

        /** A line an operator can act on, rather than a claim of continuity. */
        public String describe() {
            return "promoted from standby: " + previousOwner + " last refreshed its claim " + since.toSeconds()
                    + "s ago. "
                    + lostSince.orElse("Whatever the previous owner processed after its last "
                            + "checkpoint is not in the state this node resumes from; it is replayed from the "
                            + "source offsets that checkpoint carries, and anything the source can no longer "
                            + "supply is lost.");
        }
    }

    private final Path directory;
    private final String nodeId;
    private final Duration lease;
    private final Duration poll;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread watcher;

    public StandbyWatch(Path directory, String nodeId, Duration lease, Duration poll, Consumer<Takeover> promote) {
        this.directory = directory;
        this.nodeId = nodeId;
        this.lease = lease;
        this.poll = poll == null ? DEFAULT_POLL : poll;
        this.watcher =
                Thread.ofPlatform().name("pravaha-standby").daemon(true).unstarted(() -> waitThenPromote(promote));
    }

    /** Begins watching. Returns immediately; promotion happens on the watcher thread. */
    public void start() {
        LOG.log(
                System.Logger.Level.INFO,
                "standing by for node '" + nodeId + "': watching " + directory + " and taking over if its claim "
                        + "goes unrefreshed for " + lease.toSeconds() + "s. This node holds no lanes and serves "
                        + "nothing until then.");
        watcher.start();
    }

    private void waitThenPromote(Consumer<Takeover> promote) {
        while (!stopped.get()) {
            Optional<Takeover> free = readyToTakeOver();
            if (free.isPresent()) {
                if (stopped.get()) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, free.get().describe());
                promote.accept(free.get());
                return;
            }
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Whether the state is free to take, and what taking it would inherit.
     *
     * <p>A marker that cannot be read is <em>not</em> treated as free. It is the one case where
     * guessing is worse than waiting: an unreadable marker may well be a live primary with a
     * transient filesystem problem, and promoting into that is the split brain the lease exists to
     * prevent.
     */
    private Optional<Takeover> readyToTakeOver() {
        try {
            Optional<StateOwnership.Held> held = StateOwnership.heldBy(directory);
            if (held.isEmpty()) {
                return Optional.of(new Takeover("no owner", Duration.ZERO, Optional.empty()));
            }
            StateOwnership.Held owner = held.get();
            if (!owner.owner().nodeId().equals(nodeId)) {
                // Another node's state. A standby for one node must never promote onto another's,
                // and saying so once a minute is better than promoting or than silence.
                LOG.log(
                        System.Logger.Level.WARNING,
                        "standing by for node '" + nodeId + "' but " + directory + " belongs to '"
                                + owner.owner().nodeId() + "'. This standby will never promote; check "
                                + "pravaha.node.id on both processes.");
                return Optional.empty();
            }
            if (owner.hasExpired(lease)) {
                return Optional.of(
                        new Takeover(owner.owner().toString(), Duration.ofMillis(owner.ageMillis()), Optional.empty()));
            }
            return Optional.empty();
        } catch (RuntimeException unreadable) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "cannot read the ownership marker in " + directory + " (" + unreadable + "); continuing to "
                            + "stand by rather than promoting, because an unreadable marker may be a live primary "
                            + "with a disk problem and promoting into that is a split brain.");
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        stopped.set(true);
        watcher.interrupt();
    }
}

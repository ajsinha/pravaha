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
package com.ash.messaging.pravaha.common.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Says who owns a directory of durable state, so two nodes cannot quietly share one.
 *
 * <p>Two nodes pointed at one {@code pravaha.checkpoint.directory} shared a per-query subdirectory
 * with no namespacing, and each {@code prune(keep)} deleted whatever was oldest across both -- so the
 * survivors were an unpredictable mix and a restart restored from the other node's state (CFG-13).
 * Two nodes sharing one {@code pravaha.registry.journal} took no lock, and the interleaved appends
 * replayed <em>cleanly</em>, which is worse than corruption: a node restarted and came up running a
 * query only its neighbour had ever registered (CFG-14). Both are reachable from two lines of YAML
 * and both are silent.
 *
 * <h2>Why the path is the node id and the marker carries the address</h2>
 *
 * <p>Host and port identify a <em>location</em>; a node id identifies a <em>node</em>. Recovery
 * needs the second. Namespacing the directory by host and port would mean a node restarting on a new
 * address -- a new pod IP, a changed port, a moved host -- finding no checkpoint, restoring nothing,
 * and starting from empty with the query reporting RUNNING. That converts "two nodes collide" into
 * "one node silently loses its own state on every restart", in exactly the deployments that restart
 * most often. It is the worse trade.
 *
 * <p>But the address is what answers the question a node id cannot: <em>is the other owner still
 * alive?</em> So the marker records both, plus the pid, and a timestamp refreshed on a lease. The
 * path is stable across restarts; the marker decides whether a claim is a restart or a collision.
 *
 * <h2>What a claim does</h2>
 *
 * <table>
 *   <caption>Claim outcomes</caption>
 *   <tr><td>No marker</td><td>Claimed. The common case, and the first start.</td></tr>
 *   <tr><td>Our own node id, lease expired</td><td>Claimed, and logged. This is a restart after a
 *       crash, which must not require an operator.</td></tr>
 *   <tr><td>Our own node id, lease live</td><td>Refused: a second instance of this node is
 *       running, and the marker says where.</td></tr>
 *   <tr><td>A different node id</td><td>Refused, live or stale. Taking another node's state is the
 *       corruption being prevented, and a stale marker does not make it safe.</td></tr>
 * </table>
 *
 * <p>The override is deliberate and has to be typed: it exists because refusing to start is itself
 * a failure, and an operator who knows better must be able to say so. It follows the shape
 * {@code refuseAccidentalOpenServer} already uses -- refuse, explain, and name the flag.
 *
 * <p>A graceful {@link #close()} deletes the marker, so a clean stop leaves nothing for the next
 * start to reason about.
 */
public final class StateOwnership implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(StateOwnership.class.getName());

    /** The marker's name. Dot-prefixed so it does not look like a checkpoint to anything listing. */
    public static final String MARKER = ".pravaha-owner";

    /**
     * How long a marker stays valid without a refresh.
     *
     * <p>Generous on purpose. It is compared against a timestamp another host may have written, so
     * it absorbs clock skew as well as a slow refresh, and the cost of being too generous is a
     * crashed node waiting longer to reclaim its own state -- while the cost of being too tight is
     * two live nodes both believing they own it.
     */
    public static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    /** Who holds a directory: the node, where it can be found, and which process it is. */
    public record Owner(String nodeId, String host, int port, long pid) {

        public Owner {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("a node id is required to own state");
            }
        }

        /** This process, as an owner of state for {@code nodeId} reachable at {@code host:port}. */
        public static Owner current(String nodeId, String host, int port) {
            return new Owner(
                    nodeId,
                    host == null ? "unknown" : host,
                    port,
                    ProcessHandle.current().pid());
        }

        /** Whether this is the same running process as {@code other}, rather than the same node. */
        boolean isSameProcessAs(Owner other) {
            return other != null && pid == other.pid && host.equals(other.host);
        }

        @Override
        public String toString() {
            return nodeId + " at " + host + ":" + port + " (pid " + pid + ")";
        }
    }

    private final Path directory;
    private final Path marker;
    private final Owner owner;
    private final ScheduledExecutorService refresher;

    private StateOwnership(Path directory, Path marker, Owner owner, Duration lease) {
        this.directory = directory;
        this.marker = marker;
        this.owner = owner;
        this.refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-state-owner");
            // Daemon: holding a claim must never be the reason a JVM will not exit.
            thread.setDaemon(true);
            return thread;
        });
        long everyMillis = Math.max(1_000L, lease.toMillis() / 3);
        refresher.scheduleAtFixedRate(this::refreshQuietly, everyMillis, everyMillis, TimeUnit.MILLISECONDS);
    }

    /** Claims {@code directory} for {@code owner}, with the default lease and no override. */
    public static StateOwnership claim(Path directory, Owner owner) {
        return claim(directory, owner, DEFAULT_LEASE, false);
    }

    /**
     * Claims {@code directory} for {@code owner}, or refuses and says who holds it.
     *
     * @param allowShared skips every check. For an operator who has read the refusal and decided.
     */
    public static StateOwnership claim(Path directory, Owner owner, Duration lease, boolean allowShared) {
        Path marker = directory.resolve(MARKER);
        try {
            Files.createDirectories(directory);
        } catch (IOException cannot) {
            throw new PravahaException(
                    StateOwnershipErrors.OWNERSHIP_UNREADABLE,
                    "cannot create the state directory " + directory + " to claim it: " + cannot,
                    cannot);
        }
        if (!allowShared) {
            readMarker(marker).ifPresent(held -> refuseIfHeldByAnother(directory, marker, owner, held, lease));
        }
        StateOwnership claim = new StateOwnership(directory, marker, owner, lease);
        claim.write();
        return claim;
    }

    private static void refuseIfHeldByAnother(Path directory, Path marker, Owner owner, Held held, Duration lease) {
        boolean expired = held.ageMillis() > lease.toMillis();
        if (!held.owner().nodeId().equals(owner.nodeId())) {
            throw new PravahaException(
                    StateOwnershipErrors.STATE_NOT_OURS,
                    "the state in " + directory + " belongs to node '"
                            + held.owner().nodeId() + "' ("
                            + held.owner() + "), and this node is '" + owner.nodeId() + "'. Two nodes sharing one "
                            + "state directory prune each other's checkpoints and replay each other's "
                            + "registrations, and neither reports anything. Give this node its own directory, or "
                            + "set pravaha.state.allow-shared=true if sharing is what you meant. "
                            + (expired
                                    ? "The other node's claim has expired, which says it is not running -- it does "
                                            + "not say the state is yours."
                                    : "The other node refreshed its claim " + (held.ageMillis() / 1000)
                                            + "s ago, so it is running now."));
        }
        if (held.owner().isSameProcessAs(owner)) {
            return; // our own claim, being re-made
        }
        if (!expired && !claimantIsGone(held.owner(), owner)) {
            throw new PravahaException(
                    StateOwnershipErrors.STATE_NOT_OURS,
                    "another instance of node '" + owner.nodeId() + "' holds the state in " + directory + ": "
                            + held.owner() + ", last seen " + (held.ageMillis() / 1000) + "s ago. Two instances of "
                            + "one node will prune each other's checkpoints. Stop the other one, give this one a "
                            + "different pravaha.node.id, or set pravaha.state.allow-shared=true.");
        }
        LOG.log(
                System.Logger.Level.INFO,
                "reclaiming " + directory + " for node '" + owner.nodeId() + "': the previous claim by "
                        + held.owner()
                        + (expired
                                ? " expired " + (held.ageMillis() - lease.toMillis()) / 1000 + "s ago"
                                : " is live by the lease but that process is gone")
                        + ", which is what a restart after a crash looks like");
    }

    /**
     * Whether the process that wrote this claim is provably gone.
     *
     * <p>A lease cannot tell a crash from a busy node: the marker stops being refreshed either way,
     * and until it expires the honest reading is "might still be running". So a node killed with
     * {@code SIGKILL} was locked out of <em>its own</em> state for the length of the lease, and told
     * that a second instance of itself was running — which was false, and named a remedy ("stop the
     * other one") for a process that no longer existed. Gate P7 asks for "a killed node restarts
     * onto its own state", and {@code NodeCrashRestartTest} is what showed it could not, promptly.
     *
     * <p>The marker already records the pid. On the same host that is not a guess: ask the operating
     * system. This is what the pid was recorded for.
     *
     * <p><strong>Three ways this stays safe.</strong> It is reached only after the node ids have
     * been compared, so it can never take another node's directory. It requires the same host, so a
     * pid from another machine is never interpreted here. And pid reuse fails in the safe direction:
     * a recycled pid belonging to some unrelated process reads as <em>alive</em> and the claim is
     * refused, which is the outcome that was already happening.
     */
    private static boolean claimantIsGone(Owner held, Owner owner) {
        if (!held.host().equals(owner.host()) || "unknown".equals(held.host())) {
            // A pid is only meaningful on the host that issued it.
            return false;
        }
        return ProcessHandle.of(held.pid()).map(ProcessHandle::isAlive).orElse(false) == Boolean.FALSE;
    }

    /** What a marker says, and how long ago it said it. */
    public record Held(Owner owner, long claimedAtMillis) {

        public long ageMillis() {
            return Math.max(0L, System.currentTimeMillis() - claimedAtMillis);
        }

        /** Whether the claim has gone stale, which says the holder is not running. */
        public boolean hasExpired(Duration lease) {
            return ageMillis() > lease.toMillis();
        }
    }

    /**
     * Who holds {@code directory} right now, without claiming it.
     *
     * <p>What a standby watches. It asks repeatedly and takes over when the answer becomes "nobody",
     * which is the same condition {@link #claim} treats as a crash restart -- so a standby is not a
     * second mechanism, it is the same one asked from outside.
     *
     * @return empty when the directory is unowned; a refusal only if a marker exists and cannot be
     *     read, because guessing there is what this class exists to prevent
     */
    public static java.util.Optional<Held> heldBy(Path directory) {
        return readMarker(directory.resolve(MARKER));
    }

    private static java.util.Optional<Held> readMarker(Path marker) {
        if (!Files.exists(marker)) {
            return java.util.Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            properties.load(in);
        } catch (IOException cannot) {
            throw new PravahaException(
                    StateOwnershipErrors.OWNERSHIP_UNREADABLE,
                    "cannot read the ownership marker " + marker + ": " + cannot + ". Refusing rather than "
                            + "assuming the directory is free, because assuming is how two nodes end up sharing it.",
                    cannot);
        }
        String nodeId = properties.getProperty("node.id");
        if (nodeId == null || nodeId.isBlank()) {
            throw new PravahaException(
                    StateOwnershipErrors.OWNERSHIP_UNREADABLE,
                    "the ownership marker " + marker + " names no node. Delete it if the directory is genuinely "
                            + "unowned; it is not treated as unowned automatically, because a truncated marker and "
                            + "an absent one mean different things.");
        }
        return java.util.Optional.of(new Held(
                new Owner(
                        nodeId,
                        properties.getProperty("host", "unknown"),
                        parseInt(properties.getProperty("port")),
                        parseLong(properties.getProperty("pid"))),
                parseLong(properties.getProperty("claimed.at"))));
    }

    private static int parseInt(String text) {
        try {
            return text == null ? -1 : Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static long parseLong(String text) {
        try {
            return text == null ? -1L : Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    private void write() {
        Properties properties = new Properties();
        properties.setProperty("node.id", owner.nodeId());
        properties.setProperty("host", owner.host());
        properties.setProperty("port", Integer.toString(owner.port()));
        properties.setProperty("pid", Long.toString(owner.pid()));
        properties.setProperty("claimed.at", Long.toString(System.currentTimeMillis()));
        SensitiveFiles.createOwnerOnly(marker);
        try (OutputStream out = Files.newOutputStream(marker, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(out, "Written by Pravaha. Says which node owns the state in this directory.");
        } catch (IOException cannot) {
            throw new PravahaException(
                    StateOwnershipErrors.OWNERSHIP_UNREADABLE,
                    "cannot write the ownership marker " + marker + ": " + cannot,
                    cannot);
        }
    }

    private void refreshQuietly() {
        try {
            write();
        } catch (RuntimeException cannot) {
            // A refresh that fails is not a reason to stop serving. The lease expiring lets another
            // instance take over, which is the right outcome if this node really cannot write.
            LOG.log(
                    System.Logger.Level.WARNING,
                    "could not refresh the ownership marker " + marker + " (" + cannot + "). If this continues "
                            + "past the lease, another instance of this node may claim the directory.");
        }
    }

    /** The directory this claim covers. */
    public Path directory() {
        return directory;
    }

    /** Who this claim says owns it. */
    public Owner owner() {
        return owner;
    }

    /** Releases the claim and deletes the marker, so a clean stop leaves nothing to reason about. */
    @Override
    public void close() {
        refresher.shutdownNow();
        try {
            Files.deleteIfExists(marker);
        } catch (IOException cannot) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "could not delete the ownership marker " + marker + " (" + cannot + "). The next start of "
                            + "this node will find an expired claim and reclaim it, which is the same outcome one "
                            + "lease later.");
        }
    }
}

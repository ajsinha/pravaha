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
package com.ash.messaging.pravaha.cluster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Membership and leadership over plain TCP, with a static peer list and heartbeats.
 *
 * <p>No external service to run, which is the entire reason it exists: a developer wanting two nodes
 * on a laptop, or a deployment with nowhere to put ZooKeeper, should not have to stand up a
 * consensus cluster to try clustering.
 *
 * <p><strong>It cannot exclude split-brain, and it says so.</strong> Leadership here is "the lowest
 * id among the peers I can currently reach", which is decided independently on each side of a
 * partition -- so a partition produces two leaders, each correct from where it is standing. That is
 * survivable when leadership only decides who does redundant work, and unacceptable when it decides
 * who owns a partition of the state: two owners means two nodes writing the same aggregate, silently.
 *
 * <p>So {@link Guarantees#excludesSplitBrain()} is false, and {@link CoordinatorFactory} refuses to
 * pair this with {@code PARTITIONED} mode. The refusal is the feature. An operator who picks the
 * option with fewest moving parts finds out at startup rather than during an incident.
 */
public final class SocketCoordinator implements ClusterCoordinator {

    private static final Guarantees GUARANTEES = new Guarantees("socket", false, false, false);

    private static final String PING = "PRAVAHA-PING";
    private static final String PONG = "PRAVAHA-PONG";

    private final List<Member> peers;
    private final Duration heartbeat;
    private final Duration timeout;

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final List<Consumer<Optional<Member>>> leadershipListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<Member>>> membershipListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile @Nullable Member self;
    private volatile Optional<Member> leader = Optional.empty();
    private volatile List<Member> reachable = List.of();
    private @Nullable ServerSocket listener;
    private @Nullable ScheduledExecutorService beats;
    private @Nullable Thread accepting;

    /**
     * @param peers every node in the cluster, including this one. Static: there is no discovery,
     *     because discovery without consensus is another way to disagree about membership
     */
    public SocketCoordinator(List<Member> peers, Duration heartbeat, Duration timeout) {
        if (peers == null || peers.isEmpty()) {
            throw new PravahaException(
                    ClusterErrors.BAD_MEMBERSHIP, "a socket cluster needs its peer list, including this node");
        }
        if (timeout.compareTo(heartbeat) <= 0) {
            // A timeout at or below the heartbeat interval declares a peer dead between beats, so
            // the membership flaps and leadership with it.
            throw new PravahaException(
                    ClusterErrors.BAD_MEMBERSHIP,
                    "the failure timeout (" + timeout + ") must be longer than the heartbeat interval (" + heartbeat
                            + "), or every peer looks dead between beats");
        }
        this.peers = List.copyOf(peers);
        this.heartbeat = heartbeat;
        this.timeout = timeout;
    }

    /** A cluster with conventional timings: beat every second, declare dead after five. */
    public static SocketCoordinator of(List<Member> peers) {
        return new SocketCoordinator(peers, Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    @Override
    public String mechanism() {
        return "socket";
    }

    @Override
    public Guarantees guarantees() {
        return GUARANTEES;
    }

    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    @Override
    public void start(Member self) {
        this.self = self;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(self.host(), self.port()));
        } catch (IOException e) {
            running.set(false);
            throw new PravahaException(
                    ClusterErrors.COORDINATOR_UNAVAILABLE,
                    "cannot listen on " + self.address() + " for cluster heartbeats: " + e.getMessage(),
                    e);
        }

        accepting = Thread.ofVirtual().name("pravaha-cluster-accept").start(this::acceptLoop);
        beats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-cluster-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        lastSeen.put(self.id(), System.nanoTime());
        beats.scheduleAtFixedRate(this::sweep, 0, heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket socket = java.util.Objects.requireNonNull(listener).accept();
                    BufferedReader in =
                            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                String line = in.readLine();
                if (line != null && line.startsWith(PING)) {
                    out.println(
                            PONG + " " + java.util.Objects.requireNonNull(self).id());
                }
            } catch (IOException e) {
                if (running.get()) {
                    // A peer that went away mid-handshake is ordinary. Membership is decided by the
                    // sweep, not by one failed connection.
                    continue;
                }
                return;
            }
        }
    }

    /** One round: ping every peer, expire the silent ones, recompute leadership. */
    private void sweep() {
        Member me = self;
        if (me == null) {
            return;
        }
        lastSeen.put(me.id(), System.nanoTime());
        for (Member peer : peers) {
            if (peer.id().equals(me.id())) {
                continue;
            }
            if (ping(peer)) {
                lastSeen.put(peer.id(), System.nanoTime());
            }
        }

        long deadline = System.nanoTime() - timeout.toNanos();
        List<Member> alive = new ArrayList<>();
        for (Member peer : peers) {
            Long seen = lastSeen.get(peer.id());
            if (seen != null && seen >= deadline) {
                alive.add(peer);
            }
        }
        alive.sort(Comparator.comparing(Member::id));

        if (!alive.equals(reachable)) {
            reachable = List.copyOf(alive);
            membershipListeners.forEach(listener -> listener.accept(reachable));
        }

        // Lowest id among those reachable. Each side of a partition computes this for itself, which
        // is exactly why this coordinator does not claim to exclude split-brain.
        Optional<Member> elected = alive.stream().min(Comparator.comparing(Member::id));
        if (!elected.equals(leader)) {
            leader = elected;
            leadershipListeners.forEach(listener -> listener.accept(elected));
        }
    }

    private boolean ping(Member peer) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.host(), peer.port()), (int) heartbeat.toMillis());
            socket.setSoTimeout((int) heartbeat.toMillis());
            try (PrintWriter out = new PrintWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.println(PING + " " + java.util.Objects.requireNonNull(self).id());
                String reply = in.readLine();
                return reply != null && reply.startsWith(PONG);
            }
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public List<Member> members() {
        return reachable;
    }

    @Override
    public Optional<Member> leader() {
        return leader;
    }

    @Override
    public boolean isLeader() {
        Member me = self;
        return me != null && leader.map(elected -> elected.id().equals(me.id())).orElse(false);
    }

    @Override
    public void onLeadershipChange(Consumer<Optional<Member>> listener) {
        leadershipListeners.add(listener);
        listener.accept(leader);
    }

    @Override
    public void onMembershipChange(Consumer<List<Member>> listener) {
        membershipListeners.add(listener);
        listener.accept(reachable);
    }

    /** For tests and for an operator asking who this node can currently see. */
    public Map<String, Boolean> reachability() {
        Map<String, Boolean> view = new LinkedHashMap<>();
        long deadline = System.nanoTime() - timeout.toNanos();
        for (Member peer : peers) {
            Long seen = lastSeen.get(peer.id());
            view.put(peer.id(), seen != null && seen >= deadline);
        }
        return view;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (beats != null) {
            beats.shutdownNow();
        }
        try {
            if (listener != null) {
                listener.close();
            }
        } catch (IOException e) {
            // Shutting down; a socket that is already gone is not a failure.
        }
        if (accepting != null) {
            accepting.interrupt();
        }
        leader = Optional.empty();
        reachable = List.of();
    }
}

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * Chooses a coordinator from configuration, and refuses the combinations that are not safe.
 *
 * <p>This class is the reason the mechanism is allowed to be pluggable at all. Offering
 * {@code single}, {@code socket} and {@code zookeeper} in a configuration file makes them look like
 * three equivalent ways to do the same job. They are not: two of them can leave a partitioned
 * network with two leaders, and one cannot. If nothing checks, the option with fewest moving parts
 * gets chosen, and the difference surfaces during an incident rather than at startup.
 *
 * <p>So the deployment declares two separate things:
 *
 * <ul>
 *   <li>{@code pravaha.cluster.mode} -- what is being asked of the cluster. A correctness question
 *   <li>{@code pravaha.cluster.mechanism} -- which coordinator to use. An operational one
 * </ul>
 *
 * <p>and this checks the second against the first. {@code PARTITIONED} means partitions are owned by
 * particular nodes, and two owners means two nodes writing the same aggregate -- silently, durably,
 * discovered later by whoever reconciles the numbers. It is therefore refused on any coordinator
 * that does not exclude split-brain, rather than warned about (design section 21.2, ADR-009).
 */
public final class CoordinatorFactory {

    private CoordinatorFactory() {}

    /** Every mechanism available on this classpath, by name. */
    public static Map<String, CoordinatorProvider> available() {
        Map<String, CoordinatorProvider> providers = new LinkedHashMap<>();
        // The built-ins first, so a broken third-party provider cannot displace them.
        providers.put("single", new SingleNodeProvider());
        providers.put("socket", new SocketProvider());
        for (CoordinatorProvider provider : ServiceLoader.load(CoordinatorProvider.class)) {
            providers.putIfAbsent(provider.mechanism(), provider);
        }
        return providers;
    }

    /**
     * The provider written as {@code name}, whatever case it was written in.
     *
     * <p>CFG-18. {@code mode} is upper-cased before it is resolved and {@code mechanism} was a plain
     * map lookup, so two adjacent keys in one YAML block had two case rules: {@code mode:
     * replicated} was accepted and {@code mechanism: SOCKET} was refused with "no cluster
     * coordinator called 'SOCKET' is on the classpath" beside an "Available: [single, socket]" list
     * that appears to contradict it. A mechanism name is an identifier in a configuration file, not
     * data, and identifiers here are matched the way {@code mode} matches them.
     *
     * <p>Exact match first, so a provider that genuinely registers two names differing only in case
     * keeps whichever one was asked for.
     */
    private static CoordinatorProvider providerNamed(Map<String, CoordinatorProvider> providers, String name) {
        CoordinatorProvider exact = providers.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, CoordinatorProvider> candidate : providers.entrySet()) {
            if (candidate.getKey().equalsIgnoreCase(name)) {
                return candidate.getValue();
            }
        }
        return null;
    }

    /**
     * Builds the coordinator this configuration asks for, having checked it can do the job.
     *
     * @throws PravahaException {@link ClusterErrors#UNKNOWN_MECHANISM} if no provider answers to the
     *     configured name, or {@link ClusterErrors#INSUFFICIENT_GUARANTEE} if the mechanism cannot
     *     provide what the mode requires
     */
    public static ClusterCoordinator create(Configuration configuration) {
        ClusterMode mode = modeOf(configuration);
        String mechanism =
                configuration.getString("pravaha.cluster.mechanism", "single").strip();

        Map<String, CoordinatorProvider> providers = available();
        CoordinatorProvider provider = providerNamed(providers, mechanism);
        if (provider == null) {
            throw new PravahaException(
                    ClusterErrors.UNKNOWN_MECHANISM,
                    "no cluster coordinator called '" + mechanism + "' is on the classpath. Available: "
                            + providers.keySet()
                            + ". A mechanism ships in its own artefact so that a deployment using one does "
                            + "not carry the dependencies of the others");
        }

        Guarantees guarantees = provider.guarantees();
        if (mode.needsConsensus() && !guarantees.excludesSplitBrain()) {
            throw new PravahaException(
                    ClusterErrors.INSUFFICIENT_GUARANTEE,
                    "cluster mode " + mode + " assigns partitions to particular nodes, and '" + mechanism
                            + "' cannot exclude split-brain — " + guarantees.describe() + ". Two nodes each "
                            + "believing they own a partition means two nodes writing the same aggregate, and "
                            + "the damage is silent and durable. Use a coordinator with consensus, or run "
                            + "mode REPLICATED, where a split brain costs duplicated work rather than wrong "
                            + "numbers. Refusing now rather than during a partition.");
        }
        // S-3. The factory builds a PARTITIONED coordinator, because as a library it is real: the
        // assignment and the leases work and are tested against a real ZooKeeper. A NODE refuses to
        // serve in the mode (PravahaNode.refusePartitionedServing) until something in it consumes
        // partition ownership -- the refusal moved to where the claim would be made, rather than
        // going away. Removing it here without that node-side refusal reopened S-3 for three days.
        //
        // The original note on removing it from here: PARTITIONED used to be refused unconditionally here, because
        // nothing in the
        // build computed an assignment at all: PartitionAssignment, Rebalancer and PartitionHandoff
        // were never referenced from any running path, and PARTITIONED x single started, reported
        // itself partitioned, and partitioned nothing. ADR-039 item 8's first slice is what changes:
        // PartitionAssigner (pravaha-cluster's own main sources) turns a coordinator's real,
        // continuously-updated membership into a real, continuously-recomputed PartitionAssignment,
        // so a node built on any mechanism that reaches this line -- which the split-brain guard
        // above has already limited to ones that exclude split-brain -- can now genuinely say which
        // partitions are its own. That is this slice's whole claim: membership and assignment are
        // real. Rebalance, handoff and elastic rescale are not, and nothing here wires this
        // assignment into what a node actually serves -- see PartitionAssigner's own javadoc for
        // why rushing that part would be worse than not having it yet.
        //
        // The split-brain guard stays first, unchanged: PARTITIONED on a mechanism that cannot
        // exclude split-brain (socket) is still refused there, for the reason it always was.
        return provider.create(configuration);
    }

    /** What this deployment is asking of the cluster layer. */
    public static ClusterMode modeOf(Configuration configuration) {
        String mode = configuration
                .getString("pravaha.cluster.mode", "SINGLE")
                .strip()
                .toUpperCase(java.util.Locale.ROOT);
        try {
            return ClusterMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (ClusterMode value : ClusterMode.values()) {
                names.add(value.name());
            }
            throw new PravahaException(
                    ClusterErrors.UNKNOWN_MECHANISM, "'" + mode + "' is not a cluster mode; one of " + names, e);
        }
    }

    /** A line for the startup log, where an operator may still read it. */
    public static String describe(Configuration configuration, ClusterCoordinator coordinator) {
        return "cluster mode " + modeOf(configuration) + " on "
                + coordinator.guarantees().describe();
    }

    /**
     * The same line, naming the member this node joined as.
     *
     * <p>CFG-1. {@code pravaha.node.id} decides which checkpoints and which registry journal this
     * node may claim, and which id it advertises to a cluster -- and it reached exactly one surface,
     * {@code GET /api/v1/status}'s {@code instanceId}. The membership line named the mode and the
     * mechanism and never the member, so there was no way to confirm from a running node's log that
     * the id it advertises is the id it was configured with. The address is the one the coordinator
     * was handed, so an id and an unreachable address are distinguishable here rather than during a
     * cluster that forms and cannot work.
     */
    public static String describe(Configuration configuration, ClusterCoordinator coordinator, Member self) {
        return describe(configuration, coordinator) + ", this node " + self + " (pravaha.node.id=" + self.id() + ")";
    }
}

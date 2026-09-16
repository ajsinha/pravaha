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
        CoordinatorProvider provider = providers.get(mechanism);
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
        // S-3. Checked *after* the split-brain guard above, deliberately. PARTITIONED assigns
        // partitions to particular nodes and nothing in this build does that: PartitionAssignment,
        // Rebalancer and PartitionHandoff are never referenced from any running path, and seven
        // pravaha.cluster.* keys have no readers. PARTITIONED x socket was already refused for
        // split-brain, which made that refusal look like the guard -- but PARTITIONED x single
        // STARTED, reported itself partitioned, and partitioned nothing.
        //
        // Putting this first would have been simpler and would have made the split-brain check
        // unreachable, which is how a guard rots: it stops being exercised, and the day PARTITIONED
        // is implemented somebody has to remember it was ever there. This ordering keeps the more
        // specific diagnosis for the case that has one, and catches the rest here.
        //
        // Refused rather than implemented: distribution is deferred by ADR-034, Wave 8 scoped it out
        // (ADR-035), and ADR-038 keeps it out of GA. A mode that reports success and does nothing is
        // worse than one that refuses, because only the second tells the operator what they have.
        if (mode == ClusterMode.PARTITIONED) {
            throw new PravahaException(
                    ClusterErrors.INSUFFICIENT_GUARANTEE,
                    "cluster mode PARTITIONED is not implemented in this build and starting in it would be "
                            + "a claim this node cannot honour: partitions would be assigned to nobody, every "
                            + "node would read every partition, and nothing would say so. Distribution is "
                            + "deferred by ADR-034. Use SINGLE, or REPLICATED if you are running more than "
                            + "one node and can tolerate duplicated work. This refusal replaces a silent "
                            + "start (S-3).");
        }
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
}

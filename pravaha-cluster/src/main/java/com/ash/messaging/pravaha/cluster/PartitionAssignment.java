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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Which node owns which virtual partition (design section 21.1).
 *
 * <p>A query's key space is hashed into a fixed number of virtual partitions at registration, and
 * those are assigned to nodes. Rescaling moves partitions and never rehashes keys, which is what
 * makes elasticity tractable: a key's partition is a property of the key, and only the partition's
 * <em>owner</em> changes.
 *
 * <p>Two properties matter, and they are in tension with naive approaches:
 *
 * <p><strong>Minimal movement.</strong> Moving a partition means moving its state -- a snapshot, a
 * handoff and a pause. Assigning by {@code partition % nodeCount} would reshuffle nearly everything
 * when one node joins, so a three-node cluster growing to four would migrate three quarters of its
 * state to gain a quarter of its capacity. This uses rendezvous hashing (highest random weight):
 * each partition scores every node and picks the highest, so adding a node moves only the partitions
 * that node now wins, and removing one moves only what it held.
 *
 * <p><strong>Determinism.</strong> The assignment is a pure function of the membership, so every
 * node that agrees on who is in the cluster computes the same answer without asking. The leader does
 * not distribute a map; it decides <em>membership</em>, and the assignment follows. That is also why
 * partitioned mode needs consensus: nodes that disagree about membership will confidently compute
 * different owners, each correct from where it is standing.
 */
public record PartitionAssignment(int partitions, Map<Integer, Member> owners) {

    /** The default partition count. Enough to spread across many nodes, small enough to enumerate. */
    public static final int DEFAULT_PARTITIONS = 1024;

    public PartitionAssignment {
        if (partitions < 1) {
            throw new IllegalArgumentException("a query needs at least one virtual partition");
        }
        owners = Map.copyOf(owners);
    }

    /**
     * Computes the assignment for a membership.
     *
     * @throws PravahaException if the membership is empty. An assignment over no nodes is not an
     *     empty assignment -- it is a cluster with nowhere to put the work, and returning something
     *     that looks valid would hide that
     */
    public static PartitionAssignment of(int partitions, List<Member> members) {
        if (members == null || members.isEmpty()) {
            throw new PravahaException(
                    ClusterErrors.BAD_MEMBERSHIP,
                    "cannot assign " + partitions + " partitions across no members. An empty cluster has "
                            + "nowhere to put the work, and an empty assignment would look like a valid one");
        }
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(Member::id));

        Map<Integer, Member> owners = new LinkedHashMap<>();
        for (int partition = 0; partition < partitions; partition++) {
            Member best = null;
            long bestScore = Long.MIN_VALUE;
            for (Member member : sorted) {
                long score = score(partition, member.id());
                // Ties broken by id, so the result does not depend on iteration order.
                if (best == null || score > bestScore) {
                    best = member;
                    bestScore = score;
                }
            }
            owners.put(partition, best);
        }
        return new PartitionAssignment(partitions, owners);
    }

    /** Rendezvous weight of one node for one partition. */
    private static long score(int partition, String memberId) {
        // A cheap, well-mixed 64-bit hash of (partition, member). Not cryptographic: it decides
        // placement, not trust, and the only property needed is that it spreads.
        long hash = 0xcbf29ce484222325L;
        hash = (hash ^ partition) * 0x100000001b3L;
        for (byte b : memberId.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xff)) * 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash;
    }

    /** The node that owns the partition a key hashes to. */
    public Optional<Member> ownerOfKey(long keyHash) {
        int partition = Math.floorMod(keyHash, partitions);
        return Optional.ofNullable(owners.get(partition));
    }

    public Optional<Member> ownerOf(int partition) {
        return Optional.ofNullable(owners.get(partition));
    }

    /** The partitions one node holds, in order. */
    public List<Integer> partitionsOf(Member member) {
        List<Integer> held = new ArrayList<>();
        owners.forEach((partition, owner) -> {
            if (owner.id().equals(member.id())) {
                held.add(partition);
            }
        });
        held.sort(Comparator.naturalOrder());
        return held;
    }

    /** Every node holding at least one partition. */
    public Set<Member> members() {
        return new LinkedHashSet<>(owners.values());
    }

    /**
     * What has to move to get from this assignment to {@code next}.
     *
     * <p>The cost of a rebalance, in the only unit that matters: partitions whose state has to be
     * snapshotted, handed over and resumed somewhere else.
     */
    public List<PartitionMove> movesTo(PartitionAssignment next) {
        if (next.partitions() != partitions) {
            throw new PravahaException(
                    ClusterErrors.BAD_MEMBERSHIP,
                    "a query's partition count is fixed at registration and immutable for its life; "
                            + "cannot move from " + partitions + " to " + next.partitions()
                            + ". Changing it would rehash every key, which is the thing virtual partitions "
                            + "exist to avoid");
        }
        List<PartitionMove> moves = new ArrayList<>();
        for (int partition = 0; partition < partitions; partition++) {
            Member from = owners.get(partition);
            Member to = next.owners().get(partition);
            if (from != null && to != null && !from.id().equals(to.id())) {
                moves.add(new PartitionMove(partition, from, to));
            }
        }
        return moves;
    }

    /** One partition changing hands: snapshot at the source, restore at the target, resume. */
    public record PartitionMove(int partition, Member from, Member to) {
        @Override
        public String toString() {
            return "p" + partition + " " + from.id() + " -> " + to.id();
        }
    }

    @Override
    public String toString() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        owners.values().forEach(member -> counts.merge(member.id(), 1, Integer::sum));
        return "PartitionAssignment[" + partitions + " partitions, " + counts + "]";
    }
}

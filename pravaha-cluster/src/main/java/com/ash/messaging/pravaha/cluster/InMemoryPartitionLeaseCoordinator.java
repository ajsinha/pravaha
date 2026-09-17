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

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link PartitionLeaseCoordinator} for {@link SingleNodeCoordinator}: real mutual exclusion, just
 * scoped to one process rather than an ensemble.
 *
 * <p>"Real" is worth being precise about. There is no second process to disagree with in a
 * single-node deployment, so the property this class provides is narrower than ZooKeeper's -- but
 * what it does provide, it provides genuinely: {@link #acquire} is an atomic
 * compare-and-swap ({@link ConcurrentHashMap#putIfAbsent}), not a check-then-set with a window
 * between them, so two threads racing to acquire the same partition (a rebalance racing a retry, a
 * test written to prove exactly this) still produce exactly one winner. That is the same property
 * {@code ZooKeeperPartitionLeaseCoordinator} provides across an ensemble; this class provides it
 * across the threads of one process, which is the whole of what a single node's own mutual
 * exclusion has to cover.
 */
public final class InMemoryPartitionLeaseCoordinator implements PartitionLeaseCoordinator {

    private final ConcurrentHashMap<Integer, PartitionLease> held = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();

    @Override
    public Optional<PartitionLease> acquire(int partition, Member self) {
        PartitionLease candidate = new PartitionLease(partition, self, nextToken.incrementAndGet());
        PartitionLease existing = held.putIfAbsent(partition, candidate);
        return existing == null ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public Optional<PartitionLease> transfer(PartitionLease from, Member to) {
        PartitionLease next = new PartitionLease(from.partition(), to, nextToken.incrementAndGet());
        // ConcurrentHashMap.replace(key, oldValue, newValue): a genuine compare-and-swap, atomic
        // against every other thread calling acquire, transfer or release concurrently -- the same
        // property twoConcurrentHandoffsForTheSamePartitionOnlyOneEverCompletesUnderRealThreads
        // (PartitionHandoffTest) exists to prove against real threads rather than assume from
        // reading this line.
        boolean replaced = held.replace(from.partition(), from, next);
        return replaced ? Optional.of(next) : Optional.empty();
    }

    @Override
    public boolean isValid(PartitionLease lease) {
        return lease.equals(held.get(lease.partition()));
    }

    @Override
    public void release(PartitionLease lease) {
        // remove(key, value) only removes if the current mapping is still exactly this lease --
        // releasing a lease that is no longer the current grant (already released, or somehow
        // superseded) must not disturb whoever holds the partition now.
        held.remove(lease.partition(), lease);
    }
}

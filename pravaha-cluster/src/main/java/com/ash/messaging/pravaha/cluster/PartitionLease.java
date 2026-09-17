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

/**
 * Proof, not an opinion: a fenced, revocable claim on one virtual partition.
 *
 * <p>ADR-039 item 8, second slice. {@link PartitionAssigner#partitionsOwnedBySelf()} is a node's
 * belief, computed from whatever membership it has locally observed -- and its own javadoc says so.
 * A lease is what a belief has to become before anything may act on it: granted by a {@link
 * PartitionLeaseCoordinator} that can refuse to grant the same partition to two holders at once, and
 * carrying a {@link #fencingToken()} that lets whoever eventually consumes a lease detect a stale one
 * even under a pause long enough to make a node doubt its own clock (a GC pause, a network stall) --
 * the classic reason a lease needs a fencing token and not merely an expiry time: a holder that
 * believes its lease is still valid because no clock has told it otherwise is exactly the failure
 * mode a fencing token exists to catch, by making every actor's write ordered by a globally
 * comparable number rather than trusted on the holder's own say-so.
 *
 * <p>Deliberately opaque about what backs the token. {@code
 * com.ash.messaging.pravaha.cluster.zookeeper.ZooKeeperPartitionLeaseCoordinator} (in the plugin of
 * the same name) uses a dedicated per-partition znode's own data version -- real, monotonic, and
 * checked with a server-side compare-and-swap on every grant, never reconstructed client-side. Its
 * own javadoc records why that had to be a version on a znode that is never deleted, rather than
 * the more obvious choice of the holder znode's own version or creation-transaction id.
 * {@link InMemoryPartitionLeaseCoordinator} uses a per-process counter, sufficient because nothing
 * outside that one process can ever hold a competing lease.
 */
public record PartitionLease(int partition, Member owner, long fencingToken) {

    public PartitionLease {
        if (owner == null) {
            throw new IllegalArgumentException("a lease needs an owner");
        }
    }

    @Override
    public String toString() {
        return "PartitionLease[p" + partition + ", " + owner.id() + ", fence=" + fencingToken + "]";
    }
}

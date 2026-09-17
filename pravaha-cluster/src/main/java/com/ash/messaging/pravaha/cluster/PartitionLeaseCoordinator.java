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

/**
 * Grants, re-verifies and revokes {@link PartitionLease}s.
 *
 * <p>ADR-039 item 8, second slice: the piece that turns {@code partitionsOwnedBySelf()} from a
 * belief into a lease. Every method here is a real operation against whatever this mechanism uses
 * for mutual exclusion -- an ephemeral znode's create-fails-if-exists for ZooKeeper, an atomic
 * compare-and-swap on a process-local map for a single node -- never a cache of a past answer.
 * {@link #isValid} in particular exists specifically because a cached "yes" is exactly the belief
 * this interface exists to replace: it re-checks against the coordinator every time, which is the
 * whole reason a caller that must not serve a partition it does not own would call it just before
 * acting rather than trust a boolean it computed earlier.
 *
 * <p>Only a mechanism that can genuinely refuse to grant the same partition to two holders should
 * implement this at all. {@link SocketCoordinator} does not -- it has no consensus, so a "granted"
 * lease from it would be exactly the false promise {@link ClusterCoordinator}'s own javadoc warns
 * against, and {@link CoordinatorFactory} already refuses to pair it with {@code PARTITIONED} for
 * the identical reason. A caller finds out whether a coordinator can grant leases at all through
 * {@link LeaseGrantingCoordinator}, not by calling these methods and discovering a refusal.
 */
public interface PartitionLeaseCoordinator {

    /**
     * Attempts to become the fenced holder of {@code partition}, when nobody currently is.
     *
     * <p>For a partition's first-ever assignment. A partition already held by someone else is not
     * this method's business to take -- see {@link #transfer}, which is how a held partition
     * changes hands -- so this refuses rather than silently displacing a genuine existing holder.
     *
     * @return the lease, if nobody currently holds one for this partition; empty if somebody does.
     *     Never blocks waiting for a rival to give it up -- a caller that wants to wait, retries
     */
    Optional<PartitionLease> acquire(int partition, Member self);

    /**
     * Atomically replaces {@code from} with a new lease for {@code to} -- a handoff, not a
     * release-then-acquire. That distinction is the whole reason this method exists rather than
     * {@code release(from); acquire(partition, to)}: those two calls have a window between them
     * where nobody holds the partition at all, and a rival could acquire it in that window even
     * though the intended recipient was already decided. This succeeds only if {@code from} is
     * still the current grant at the moment of the swap -- a real compare-and-swap, not a
     * check-then-act -- so a stale or already-superseded {@code from} fails cleanly rather than
     * silently taking a partition out from under whoever now genuinely holds it.
     *
     * <p>Call this through the intended new holder's own {@link PartitionLeaseCoordinator} (its
     * own {@link LeaseGrantingCoordinator#leases()}), not the current holder's: for a mechanism
     * where a lease is tied to its holder's own session (ZooKeeper's ephemeral znodes), the
     * resulting lease has to be created by the session it will actually belong to.
     *
     * @return the new lease for {@code to}, if {@code from} was still current; empty otherwise
     */
    Optional<PartitionLease> transfer(PartitionLease from, Member to);

    /**
     * Whether {@code lease} is still the current, genuine grant for its partition -- checked fresh,
     * against the coordinator, not against anything cached.
     *
     * <p>A lease can stop being valid without its holder doing anything: the holder's own session
     * ending is what actually revokes a ZooKeeper-backed lease (the same mechanism membership
     * itself already relies on), and a holder that has not yet noticed its session is gone is
     * exactly the case this method exists to catch before it acts.
     */
    boolean isValid(PartitionLease lease);

    /**
     * Gives up a lease this node holds, in an orderly way.
     *
     * <p>Not required for correctness on its own -- a lease is revoked the moment its holder's
     * session ends, with or without this call -- but required for a handoff that wants to move a
     * partition without ending its own session, which is every handoff that is not also a failure.
     * A no-op if {@code lease} is not the current grant (already released, already revoked, or
     * never actually held): releasing a lease that is not yours to release must not disturb
     * whoever holds it now.
     */
    void release(PartitionLease lease);
}

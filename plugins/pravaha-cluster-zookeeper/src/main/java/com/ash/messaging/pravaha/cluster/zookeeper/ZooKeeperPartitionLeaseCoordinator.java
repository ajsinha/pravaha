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
package com.ash.messaging.pravaha.cluster.zookeeper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import com.ash.messaging.pravaha.cluster.Member;
import com.ash.messaging.pravaha.cluster.PartitionLease;
import com.ash.messaging.pravaha.cluster.PartitionLeaseCoordinator;

/**
 * {@link PartitionLeaseCoordinator} over real ZooKeeper.
 *
 * <h2>Why not one ephemeral znode per partition, recreated on every transfer</h2>
 *
 * <p>That was the first design, and it does not work, for a reason worth recording so it is not
 * tried again. An ephemeral znode's <em>data version</em> is the only thing ZooKeeper offers for a
 * compare-and-swap, and that version resets to zero every time the znode is deleted and recreated
 * -- which a transfer must do, because ephemeral-ness is fixed to its creating session forever and
 * a lease moving to a new holder needs a znode tied to the *new* holder's session. Two different
 * incarnations of the same path, neither ever touched by {@code setData}, are indistinguishable by
 * version alone: both are "version zero". A transfer built on delete-then-recreate-then-check-version
 * would look correct in every test that does not force a second transfer into the gap between one
 * transfer's delete and its create -- exactly the kind of handoff that looks right because nothing
 * made two nodes disagree.
 *
 * <h2>What is used instead</h2>
 *
 * <p>Two znodes per partition, with two different jobs:
 *
 * <ul>
 *   <li>{@code {root}/leases/{partition}/epoch} -- persistent, never deleted, only ever {@code
 *       setData}. Its version is a real, monotonic counter: every acquire and every transfer bumps
 *       it by exactly one, inside the same atomic transaction that changes the holder, so the
 *       version at the moment a lease was granted is a fencing token that means the same thing for
 *       the life of the partition, not just for one znode's incarnation.
 *   <li>{@code {root}/leases/{partition}/holder} -- ephemeral, tied to whichever node currently
 *       holds the lease. Deleted and recreated on every transfer (any client may delete any znode
 *       in ZooKeeper; ephemeral only governs who *created* it and whose session deletion is tied
 *       to), which is exactly the property membership already relies on: a holder whose session
 *       ends loses the znode -- and therefore the lease -- without anyone having to notice.
 * </ul>
 *
 * <p>{@link #transfer} bundles a version-checked {@code setData} on {@code epoch} with the delete
 * and create on {@code holder} into one ZooKeeper multi-op transaction. Multi-ops are all-or-
 * nothing: if the epoch version has moved since {@code from} was granted -- because a rival
 * transfer or a fresh acquire already happened -- the whole transaction is rejected atomically, the
 * holder znode is untouched, and the caller gets nothing rather than half a handoff.
 */
final class ZooKeeperPartitionLeaseCoordinator implements PartitionLeaseCoordinator {

    private final CuratorFramework curator;
    private final String root;

    ZooKeeperPartitionLeaseCoordinator(CuratorFramework curator, String root) {
        this.curator = curator;
        this.root = root;
    }

    private String epochPath(int partition) {
        return root + "/leases/" + partition + "/epoch";
    }

    private String holderPath(int partition) {
        return root + "/leases/" + partition + "/holder";
    }

    /** Creates the epoch znode if this is the partition's first ever lease operation. Idempotent. */
    private int ensureEpoch(int partition) {
        String path = epochPath(partition);
        try {
            Stat stat = curator.checkExists().forPath(path);
            if (stat != null) {
                return stat.getVersion();
            }
            curator.create().creatingParentsIfNeeded().forPath(path, new byte[0]);
            return 0;
        } catch (KeeperException.NodeExistsException raced) {
            // Another node created it between our check and our create -- fine, it exists now.
            return readEpochVersion(partition);
        } catch (Exception e) {
            throw new IllegalStateException("cannot prepare partition " + partition + " for leasing at " + path, e);
        }
    }

    private int readEpochVersion(int partition) {
        try {
            Stat stat = curator.checkExists().forPath(epochPath(partition));
            if (stat == null) {
                return ensureEpoch(partition);
            }
            return stat.getVersion();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read partition " + partition + "'s epoch", e);
        }
    }

    @Override
    public Optional<PartitionLease> acquire(int partition, Member self) {
        int epochVersion = ensureEpoch(partition);
        try {
            curator.transaction()
                    .forOperations(
                            curator.transactionOp()
                                    .setData()
                                    .withVersion(epochVersion)
                                    .forPath(epochPath(partition), new byte[0]),
                            curator.transactionOp()
                                    .create()
                                    .withMode(CreateMode.EPHEMERAL)
                                    .forPath(holderPath(partition), self.id().getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new PartitionLease(partition, self, epochVersion + 1));
        } catch (KeeperException.BadVersionException | KeeperException.NodeExistsException contended) {
            // Either the epoch moved since we read it (someone else acquired or transferred
            // concurrently) or the holder znode already exists (somebody holds it) -- both mean
            // this partition is not free to acquire right now.
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("cannot acquire partition " + partition + " for " + self.id(), e);
        }
    }

    @Override
    public Optional<PartitionLease> transfer(PartitionLease from, Member to) {
        int partition = from.partition();
        try {
            curator.transaction()
                    .forOperations(
                            curator.transactionOp()
                                    .setData()
                                    .withVersion((int) from.fencingToken())
                                    .forPath(epochPath(partition), new byte[0]),
                            curator.transactionOp().delete().forPath(holderPath(partition)),
                            curator.transactionOp()
                                    .create()
                                    .withMode(CreateMode.EPHEMERAL)
                                    .forPath(holderPath(partition), to.id().getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new PartitionLease(partition, to, from.fencingToken() + 1));
        } catch (KeeperException.BadVersionException
                | KeeperException.NoNodeException
                | KeeperException.NodeExistsException stale) {
            // BadVersion: the epoch moved -- from is no longer the current grant. NoNode: the
            // holder znode is already gone (the source's session ended some other way). NodeExists:
            // should not happen inside one transaction with our own delete first, but a defensive
            // catch is cheaper than a debugging session if it ever does. All three mean the same
            // thing to a caller: the transfer did not happen.
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot transfer partition " + partition + " from "
                            + from.owner().id() + " to " + to.id(),
                    e);
        }
    }

    @Override
    public boolean isValid(PartitionLease lease) {
        try {
            Stat epochStat = curator.checkExists().forPath(epochPath(lease.partition()));
            if (epochStat == null || epochStat.getVersion() != lease.fencingToken()) {
                return false;
            }
            byte[] data = curator.getData().forPath(holderPath(lease.partition()));
            return lease.owner().id().equals(new String(data, StandardCharsets.UTF_8));
        } catch (KeeperException.NoNodeException gone) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("cannot check partition " + lease.partition() + "'s lease", e);
        }
    }

    @Override
    public void release(PartitionLease lease) {
        if (!isValid(lease)) {
            // Not the current grant any more -- releasing it must not disturb whoever holds the
            // partition now, exactly like InMemoryPartitionLeaseCoordinator's own contract.
            return;
        }
        try {
            curator.delete().forPath(holderPath(lease.partition()));
        } catch (KeeperException.NoNodeException alreadyGone) {
            // A concurrent release, or the holder's session already ended. Either way, gone is gone.
        } catch (Exception e) {
            throw new IllegalStateException("cannot release partition " + lease.partition(), e);
        }
    }
}

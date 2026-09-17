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

import com.ash.messaging.pravaha.api.ErrorCode;

/** Cluster error codes, PRV-9nnn: membership, election and assignment. */
public final class ClusterErrors {

    /** No coordinator answers to the configured name. */
    public static final ErrorCode UNKNOWN_MECHANISM = new ErrorCode(9001, "CLUSTER_UNKNOWN_MECHANISM");

    /**
     * The configured coordinator cannot provide a guarantee this deployment needs.
     *
     * <p>The important one. Refusing to start beats starting into a configuration that is correct
     * until the first network partition.
     */
    public static final ErrorCode INSUFFICIENT_GUARANTEE = new ErrorCode(9002, "CLUSTER_INSUFFICIENT_GUARANTEE");

    /** The coordinator could not be reached or lost its session. */
    public static final ErrorCode COORDINATOR_UNAVAILABLE = new ErrorCode(9003, "CLUSTER_COORDINATOR_UNAVAILABLE");

    /**
     * This node is not the leader and the operation is the leader's.
     *
     * <p><strong>Still unreachable, and for a narrower reason than before.</strong> ADR-039 item 8's
     * first slice landed {@code PartitionAssignment} on a running path: {@code PartitionAssigner}
     * wires real membership from a real {@code ClusterCoordinator} into a real, continuously
     * recomputed assignment, and {@code CoordinatorFactory} allows {@code PARTITIONED} on any
     * mechanism that excludes split-brain. What did not land, on purpose, is anything that needs a
     * leader at all: assignment is a pure function every node computes independently from whatever
     * membership it has observed (see {@code PartitionAssigner}'s own javadoc for why), so nothing
     * asks "am I the leader" before doing it. {@code Rebalancer} and {@code PartitionHandoff} remain
     * wired to no running path -- initiating a rebalance is the operation that will actually need
     * this refusal, and that is explicitly the next slice, not this one. Remove this comment, not
     * the declaration, when it lands.
     */
    public static final ErrorCode NOT_LEADER = new ErrorCode(9004, "CLUSTER_NOT_LEADER");

    /** The configuration names peers that cannot form a cluster. */
    public static final ErrorCode BAD_MEMBERSHIP = new ErrorCode(9005, "CLUSTER_BAD_MEMBERSHIP");

    /**
     * A partition handoff did not complete.
     *
     * <p>The message distinguishes the two cases that matter: rolled back (the source still owns it,
     * retry later) and failed past the point of rollback (the partition needs checkpoint recovery
     * and must not be moved back).
     */
    public static final ErrorCode HANDOFF_FAILED = new ErrorCode(9006, "CLUSTER_HANDOFF_FAILED");

    /** A rebalance was asked for while one was running, or too soon after one finished. */
    public static final ErrorCode REBALANCE_REFUSED = new ErrorCode(9007, "CLUSTER_REBALANCE_REFUSED");

    private ClusterErrors() {}
}

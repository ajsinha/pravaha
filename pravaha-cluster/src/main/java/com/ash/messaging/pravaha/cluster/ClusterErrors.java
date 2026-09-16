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
     * <p><strong>Deliberately unreachable for now.</strong> {@code CoordinatorFactory} refuses
     * {@code PARTITIONED} outright (ADR-034: distribution deferred), and {@code PartitionAssignment},
     * {@code Rebalancer} and {@code PartitionHandoff} are wired to no running path, so there is no
     * multi-node deployment in which a "not the leader" refusal could ever fire. This is not owed the
     * "wire or delete" verdict E-1 applies elsewhere: ADR-039 (item 8) puts cluster mode itself back
     * on the roadmap, and the code names the exact refusal that mode will need on day one. Remove
     * this comment, not the declaration, when clustering lands.
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

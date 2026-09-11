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

    /** This node is not the leader and the operation is the leader's. */
    public static final ErrorCode NOT_LEADER = new ErrorCode(9004, "CLUSTER_NOT_LEADER");

    /** The configuration names peers that cannot form a cluster. */
    public static final ErrorCode BAD_MEMBERSHIP = new ErrorCode(9005, "CLUSTER_BAD_MEMBERSHIP");

    private ClusterErrors() {}
}

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
 * A {@link ClusterCoordinator} that can additionally grant {@link PartitionLease}s.
 *
 * <p>Deliberately a separate, optional capability rather than a new {@link ClusterCoordinator}
 * method. That interface is membership and leadership -- {@code
 * pravaha-it}'s {@code StateClusterTest.state106} pins its method count precisely because the first
 * slice's own design commitment was to keep it that size, and a coordinator with no consensus
 * (only {@link SocketCoordinator} ships one) has no honest implementation of {@code leases()} to
 * offer at all: forcing it onto the interface would mean either an
 * {@link UnsupportedOperationException} nobody asked for, or a coordinator quietly pretending it
 * can grant something it cannot.
 *
 * <p>A caller checks for this capability with {@code instanceof} against the interface, never
 * against a concrete coordinator class -- the same reason {@link ClusterCoordinator} itself is an
 * interface with several mechanisms behind it: a fourth mechanism that can grant leases should not
 * need this file to change.
 */
public interface LeaseGrantingCoordinator {

    /** The lease coordinator for this mechanism. Stable for the life of this coordinator. */
    PartitionLeaseCoordinator leases();
}

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

import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * How a coordinator implementation makes itself available to a deployment.
 *
 * <p>Discovered by {@link java.util.ServiceLoader}, so ZooKeeper, etcd, Consul or a house mechanism
 * can be added without the engine knowing about them -- the same arrangement as source plugins, and
 * for the same reason: the set of things an organisation already runs is not a set we can enumerate.
 *
 * <p>An implementation lives in its own artefact with its own dependencies. A deployment that uses
 * sockets should not have a ZooKeeper client on its classpath.
 */
public interface CoordinatorProvider {

    /** The name written in {@code pravaha.cluster.mechanism}. */
    String mechanism();

    /** What the coordinator this provider builds will promise. Read before one is constructed. */
    Guarantees guarantees();

    /**
     * Builds the coordinator.
     *
     * @param configuration the whole configuration; an implementation reads its own keys from
     *     {@code pravaha.cluster.<mechanism>.*}
     */
    ClusterCoordinator create(Configuration configuration);
}

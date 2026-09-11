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

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.ClusterErrors;
import com.ash.messaging.pravaha.cluster.CoordinatorProvider;
import com.ash.messaging.pravaha.cluster.Guarantees;
import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * Makes the ZooKeeper coordinator selectable as {@code mechanism: zookeeper}.
 *
 * <pre>
 * pravaha:
 *   cluster:
 *     mode: PARTITIONED
 *     mechanism: zookeeper
 *     zookeeper:
 *       connect: "zk1:2181,zk2:2181,zk3:2181"
 *       root: /pravaha
 *       session.timeout.millis: 15000
 * </pre>
 *
 * <p>Present only when this artefact is on the classpath, which is the point of it being a separate
 * one: a deployment using sockets or a single node carries no ZooKeeper client at all.
 */
public final class ZooKeeperProvider implements CoordinatorProvider {

    @Override
    public String mechanism() {
        return "zookeeper";
    }

    @Override
    public Guarantees guarantees() {
        return new Guarantees("zookeeper", true, true, true);
    }

    @Override
    public ClusterCoordinator create(Configuration configuration) {
        String connect = configuration
                .getString("pravaha.cluster.zookeeper.connect")
                .orElseThrow(() -> new PravahaException(
                        ClusterErrors.BAD_MEMBERSHIP,
                        "the ZooKeeper coordinator needs pravaha.cluster.zookeeper.connect, as "
                                + "'host:2181,host:2181,host:2181'"));
        String root = configuration.getString("pravaha.cluster.zookeeper.root", "/pravaha");
        int sessionTimeout = (int) configuration.getLong("pravaha.cluster.zookeeper.session.timeout.millis", 15_000);
        int connectTimeout = (int) configuration.getLong("pravaha.cluster.zookeeper.connect.timeout.millis", 10_000);

        CuratorFramework curator = CuratorFrameworkFactory.builder()
                .connectString(connect)
                .sessionTimeoutMs(sessionTimeout)
                .connectionTimeoutMs(connectTimeout)
                // Retry, because a ZooKeeper blip should not take the cluster down with it. The
                // session timeout is what decides when this node is considered gone, and that is
                // ZooKeeper's decision rather than ours -- which is the whole reason for using it.
                .retryPolicy(new ExponentialBackoffRetry(1_000, 5))
                .build();
        return new ZooKeeperCoordinator(curator, root);
    }
}

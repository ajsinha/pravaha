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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.cluster.ClusterMode;
import com.ash.messaging.pravaha.cluster.CoordinatorFactory;
import com.ash.messaging.pravaha.cluster.CoordinatorProvider;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ZooKeeper coordinator's contract, without a ZooKeeper.
 *
 * <p>What is worth asserting here is the part that decides whether a deployment is safe: that this
 * mechanism is discovered when its artefact is present, that it declares consensus, and that
 * declaring consensus is what makes {@code PARTITIONED} mode permissible. Whether Curator can talk to
 * a real ensemble is Curator's test, not ours.
 */
class ZooKeeperProviderTest {

    @Test
    void itIsDiscoveredWhenItsArtefactIsOnTheClasspath() {
        // ServiceLoader, like the source plugins, so a deployment that runs etcd or Consul can add
        // one without the engine knowing about it.
        assertThat(CoordinatorFactory.available()).containsKey("zookeeper");
    }

    @Test
    void itDeclaresConsensusAndSaysWhatItCosts() {
        CoordinatorProvider provider = CoordinatorFactory.available().get("zookeeper");

        assertThat(java.util.Objects.requireNonNull(provider).guarantees().excludesSplitBrain())
                .as("leadership is a LeaderLatch, which is ZooKeeper's consensus rather than ours")
                .isTrue();
        assertThat(provider.guarantees().requiresExternalService())
                .as("an external service to run and keep available is the trade, and it is stated")
                .isTrue();
        assertThat(provider.guarantees().suitableForProduction()).isTrue();
    }

    @Test
    void partitionedModeIsAllowedOnThisMechanism() {
        // The socket coordinator is refused for this mode; this one is not, and the difference is
        // exactly the guarantee above rather than anything about how much code each contains.
        assertThat(ClusterMode.PARTITIONED.needsConsensus()).isTrue();
        assertThat(java.util.Objects.requireNonNull(
                                CoordinatorFactory.available().get("zookeeper"))
                        .guarantees()
                        .excludesSplitBrain())
                .isTrue();
    }

    @Test
    void itNeedsAConnectString() {
        assertThatThrownBy(() -> java.util.Objects.requireNonNull(
                                CoordinatorFactory.available().get("zookeeper"))
                        .create(Configuration.builder()
                                .set("pravaha.cluster.mechanism", "zookeeper")
                                .build()))
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("connect");
    }
}

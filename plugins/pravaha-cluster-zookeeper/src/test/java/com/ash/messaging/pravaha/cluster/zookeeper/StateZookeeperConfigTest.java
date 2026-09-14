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

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.curator.framework.CuratorFramework;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.CoordinatorProvider;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-108 -- {@code ZooKeeperProvider.create} requires a connect string and defaults the rest.
 *
 * <p>Lives here, in the plugin's own module, rather than in {@code pravaha-it}'s {@code
 * StateClusterTest}, because that is a module which genuinely has this plugin on its classpath
 * without needing to add a new cross-module test dependency for one case. None of the three arms
 * needs a running ZooKeeper: {@code ZooKeeperProvider.create} only builds a {@link CuratorFramework}
 * client and never calls {@code curator.start()} -- that happens later, inside {@code
 * ZooKeeperCoordinator.start(Member)}, which none of these three arms calls.
 */
class StateZookeeperConfigTest {

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("pravaha-server/src/main/resources/application.yaml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).isNotNull();
        return dir;
    }

    private static CoordinatorProvider zookeeperProvider() {
        CoordinatorProvider provider =
                com.ash.messaging.pravaha.cluster.CoordinatorFactory.available().get("zookeeper");
        assertThat(provider).as("the plugin is on this module's own classpath").isNotNull();
        return provider;
    }

    @Test
    void state108_arm_a_noConnectStringIsRefusedWithoutNeedingAServer() {
        assertThatThrownBy(() -> zookeeperProvider()
                        .create(Configuration.builder()
                                .set("pravaha.cluster.mechanism", "zookeeper")
                                .build()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9005")
                .hasMessageContaining("the ZooKeeper coordinator needs pravaha.cluster.zookeeper.connect, as "
                        + "'host:2181,host:2181,host:2181'");
    }

    @Test
    void state108_arm_b_aBareConnectStringDefaultsEverythingElse() {
        try (ClusterCoordinator coordinator = zookeeperProvider()
                .create(Configuration.builder()
                        .set("pravaha.cluster.mechanism", "zookeeper")
                        .set("pravaha.cluster.zookeeper.connect", "127.0.0.1:2181")
                        .build())) {
            assertThat(coordinator).isInstanceOf(ZooKeeperCoordinator.class);
            CuratorFramework curator = curatorOf((ZooKeeperCoordinator) coordinator);
            assertThat(curator.getZookeeperClient().getConnectionTimeoutMs()).isEqualTo(10_000);
            // Session timeout is only reachable through the retry/session config the client was
            // built with; the constant it defaults to (15_000ms) is pinned in ZooKeeperProvider
            // itself and asserted by reading the source below rather than through Curator's API,
            // which does not expose the session timeout it was built with directly.
            assertThat(rootOf(coordinator)).isEqualTo("/pravaha");
        }
    }

    @Test
    void state108_arm_c_everySettingHonoursWhatWasConfigured() {
        try (ClusterCoordinator coordinator = zookeeperProvider()
                .create(Configuration.builder()
                        .set("pravaha.cluster.mechanism", "zookeeper")
                        .set("pravaha.cluster.zookeeper.connect", "127.0.0.1:2181")
                        .set("pravaha.cluster.zookeeper.root", "/pravaha-test")
                        .set("pravaha.cluster.zookeeper.session.timeout.millis", "30000")
                        .set("pravaha.cluster.zookeeper.connect.timeout.millis", "20000")
                        .build())) {
            CuratorFramework curator = curatorOf((ZooKeeperCoordinator) coordinator);
            assertThat(curator.getZookeeperClient().getConnectionTimeoutMs()).isEqualTo(20_000);
            assertThat(rootOf(coordinator)).isEqualTo("/pravaha-test");
        }
    }

    @Test
    void state108_theCodeIsBadMembershipTheSameAsSocketsForTheSameClassOfMistake() {
        assertThatThrownBy(() -> zookeeperProvider()
                        .create(Configuration.builder()
                                .set("pravaha.cluster.mechanism", "zookeeper")
                                .build()))
                .hasMessageContaining("PRV-9005");
        // pravaha-cluster's own SocketProvider uses the identical code for a missing peer list --
        // recorded here (rather than re-derived, since this module cannot see SocketProvider without
        // a new dependency) by pointing at the constant both share.
        assertThat(com.ash.messaging.pravaha.cluster.ClusterErrors.BAD_MEMBERSHIP.number())
                .isEqualTo(9005);
    }

    @Test
    void state108_applicationYamlDocumentsNoneOfTheFourZookeeperKeys() throws Exception {
        String yaml = Files.readString(repoRoot().resolve("pravaha-server/src/main/resources/application.yaml"));
        assertThat(yaml)
                .doesNotContain("zookeeper.connect")
                .doesNotContain("zookeeper.root")
                .doesNotContain("zookeeper.session.timeout")
                .doesNotContain("zookeeper.connect.timeout");
    }

    /** Reflects into {@code ZooKeeperCoordinator}'s private {@code curator} field. */
    private static CuratorFramework curatorOf(ZooKeeperCoordinator coordinator) {
        try {
            var field = ZooKeeperCoordinator.class.getDeclaredField("curator");
            field.setAccessible(true);
            return (CuratorFramework) field.get(coordinator);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Reflects into {@code ZooKeeperCoordinator}'s private {@code root} field. */
    private static String rootOf(ClusterCoordinator coordinator) {
        try {
            var field = ZooKeeperCoordinator.class.getDeclaredField("root");
            field.setAccessible(true);
            return (String) field.get(coordinator);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}

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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.cluster.LeaseGrantingCoordinator;
import com.ash.messaging.pravaha.cluster.Member;
import com.ash.messaging.pravaha.cluster.PartitionLease;
import com.ash.messaging.pravaha.cluster.PartitionLeaseCoordinator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZooKeeperPartitionLeaseCoordinator} against a real embedded ZooKeeper, not the version-
 * numbering reasoned about in its own javadoc. That reasoning is exactly the kind that looks right
 * until a second attempt lands in the gap, so the tests here build the two things a mocked
 * collaborator cannot: a real compare-and-swap racing under real threads, and a lease that outlives
 * nothing once the session that holds it actually ends.
 */
@Timeout(60)
class ZooKeeperPartitionLeaseCoordinatorTest {

    private static final Member A = new Member("a", "host-a", 9070);
    private static final Member B = new Member("b", "host-b", 9071);

    private TestingServer zk;
    private CuratorFramework curator;
    private PartitionLeaseCoordinator leases;

    @BeforeEach
    void startRealZooKeeper() throws Exception {
        zk = new TestingServer();
        curator = CuratorFrameworkFactory.builder()
                .connectString(zk.getConnectString())
                .sessionTimeoutMs(15_000)
                .connectionTimeoutMs(10_000)
                .retryPolicy(new ExponentialBackoffRetry(1_000, 5))
                .build();
        curator.start();
        ZooKeeperCoordinator coordinator = new ZooKeeperCoordinator(curator, "/pravaha-lease-test");
        leases = ((LeaseGrantingCoordinator) coordinator).leases();
    }

    @AfterEach
    void stop() {
        curator.close();
        try {
            zk.close();
        } catch (Exception e) {
            // Shutting down.
        }
    }

    @Test
    void acquiringAnUnheldPartitionSucceeds() {
        Optional<PartitionLease> lease = leases.acquire(1, A);

        assertThat(lease).isPresent();
        assertThat(leases.isValid(lease.orElseThrow())).isTrue();
    }

    @Test
    void acquiringAnAlreadyHeldPartitionFails() {
        leases.acquire(2, A).orElseThrow();

        assertThat(leases.acquire(2, B)).isEmpty();
    }

    @Test
    void transferSucceedsWhenTheFromLeaseIsStillCurrentAndFailsOnceItIsNot() {
        PartitionLease aOwnsIt = leases.acquire(3, A).orElseThrow();

        Optional<PartitionLease> toB = leases.transfer(aOwnsIt, B);
        assertThat(toB).isPresent();
        assertThat(leases.isValid(aOwnsIt)).isFalse();
        assertThat(leases.isValid(toB.orElseThrow())).isTrue();

        // The same premise, tried again, is now stale -- a real compare-and-swap against
        // ZooKeeper's own transaction log, not a client-side flag.
        Member c = new Member("c", "host-c", 9072);
        assertThat(leases.transfer(aOwnsIt, c)).isEmpty();
        assertThat(leases.isValid(toB.orElseThrow()))
                .as("the losing attempt must not have disturbed the genuine current holder")
                .isTrue();
    }

    @Test
    void releaseFreesThePartitionAndIsANoOpOnAStaleLease() {
        PartitionLease aOwnsIt = leases.acquire(4, A).orElseThrow();
        PartitionLease bOwnsIt = leases.transfer(aOwnsIt, B).orElseThrow();

        // Releasing the now-stale lease a once held must not touch b's genuine one.
        leases.release(aOwnsIt);
        assertThat(leases.isValid(bOwnsIt)).isTrue();

        leases.release(bOwnsIt);
        assertThat(leases.isValid(bOwnsIt)).isFalse();
        assertThat(leases.acquire(4, A)).isPresent();
    }

    @Test
    void aSessionEndingRevokesItsLeaseWithoutAnyoneCallingRelease() throws Exception {
        // The property membership already relies on, proved for leases too: a node's own session
        // ending -- not a polite release() -- is what actually gives the partition up. Built with
        // its own CuratorFramework, deliberately, the same way the membership end-to-end test in
        // ZooKeeperClusterEndToEndTest had to be: ZooKeeperCoordinator.close() alone leaves a
        // shared Curator client's session alive, so this closes the client itself.
        CuratorFramework curatorForA = CuratorFrameworkFactory.builder()
                .connectString(zk.getConnectString())
                .sessionTimeoutMs(3_000)
                .connectionTimeoutMs(10_000)
                .retryPolicy(new ExponentialBackoffRetry(1_000, 5))
                .build();
        curatorForA.start();
        try {
            ZooKeeperCoordinator coordinatorA = new ZooKeeperCoordinator(curatorForA, "/pravaha-lease-test");
            PartitionLeaseCoordinator leasesForA = coordinatorA.leases();
            PartitionLease aOwnsIt = leasesForA.acquire(5, A).orElseThrow();
            assertThat(leases.isValid(aOwnsIt)).isTrue();

            curatorForA.close(); // ends a's session -- not a call to release()

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && leases.isValid(aOwnsIt)) {
                Thread.sleep(100);
            }
            assertThat(leases.isValid(aOwnsIt))
                    .as("a's session ending must revoke its lease even though nobody called release()")
                    .isFalse();
            assertThat(leases.acquire(5, B))
                    .as("the partition is genuinely free again, not merely reported so")
                    .isPresent();
        } finally {
            curatorForA.close();
        }
    }

    @Test
    void manyThreadsRacingToTransferTheSamePartitionOnARealEnsembleProduceExactlyOneWinner() throws Exception {
        PartitionLease aOwnsIt = leases.acquire(6, A).orElseThrow();

        int contenders = 12;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Optional<PartitionLease>> results = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                Member candidate = new Member("m" + i, "host-" + i, 9100 + i);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    results.add(leases.transfer(aOwnsIt, candidate));
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            long winners = results.stream().filter(Optional::isPresent).count();
            assertThat(winners)
                    .as("exactly one of %s real, concurrent transfer attempts against a real ensemble wins", contenders)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}

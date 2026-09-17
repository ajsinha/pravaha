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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease primitive on its own, directly, before {@link PartitionHandoffTest} exercises it
 * through a whole handoff sequence.
 */
@Timeout(30)
class InMemoryPartitionLeaseCoordinatorTest {

    private static final Member A = new Member("a", "host-a", 9070);
    private static final Member B = new Member("b", "host-b", 9071);

    @Test
    void acquiringAnUnheldPartitionSucceeds() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();

        Optional<PartitionLease> lease = leases.acquire(5, A);

        assertThat(lease).isPresent();
        assertThat(lease.orElseThrow().partition()).isEqualTo(5);
        assertThat(lease.orElseThrow().owner()).isEqualTo(A);
        assertThat(leases.isValid(lease.orElseThrow())).isTrue();
    }

    @Test
    void acquiringAnAlreadyHeldPartitionFails() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        leases.acquire(5, A).orElseThrow();

        // Not this method's job to displace a's genuine hold -- see transfer().
        assertThat(leases.acquire(5, B)).isEmpty();
    }

    @Test
    void differentPartitionsDoNotContendWithEachOther() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();

        assertThat(leases.acquire(5, A)).isPresent();
        assertThat(leases.acquire(6, B)).isPresent();
    }

    @Test
    void transferSucceedsWhenTheFromLeaseIsStillCurrent() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(5, A).orElseThrow();

        Optional<PartitionLease> transferred = leases.transfer(aOwnsIt, B);

        assertThat(transferred).isPresent();
        assertThat(transferred.orElseThrow().owner()).isEqualTo(B);
        assertThat(leases.isValid(transferred.orElseThrow())).isTrue();
        assertThat(leases.isValid(aOwnsIt))
                .as("a's lease is genuinely superseded, not merely believed so")
                .isFalse();
    }

    @Test
    void transferFailsWhenTheFromLeaseIsAlreadyStale() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(5, A).orElseThrow();
        leases.transfer(aOwnsIt, B).orElseThrow(); // b now holds it; aOwnsIt is stale

        Member c = new Member("c", "host-c", 9072);
        // A second transfer attempt built from the same, now-stale premise must not succeed --
        // and must not disturb b, who genuinely holds it.
        assertThat(leases.transfer(aOwnsIt, c)).isEmpty();
        assertThat(leases.isValid(aOwnsIt)).isFalse();
    }

    @Test
    void transferFailsWhenNobodyHasEverHeldThePartition() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        // A lease this coordinator never granted -- fabricated, the way a stale premise from an
        // entirely different lease coordinator would look.
        PartitionLease neverGranted = new PartitionLease(5, A, 999);

        assertThat(leases.transfer(neverGranted, B)).isEmpty();
    }

    @Test
    void releaseIsANoOpWhenTheLeaseIsNotTheCurrentGrant() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(5, A).orElseThrow();
        PartitionLease bOwnsIt = leases.transfer(aOwnsIt, B).orElseThrow();

        // Releasing the stale lease must not disturb the genuine current one.
        leases.release(aOwnsIt);
        assertThat(leases.isValid(bOwnsIt)).isTrue();
    }

    @Test
    void releaseActuallyFreesThePartitionForAFreshAcquire() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(5, A).orElseThrow();

        leases.release(aOwnsIt);

        assertThat(leases.isValid(aOwnsIt)).isFalse();
        assertThat(leases.acquire(5, B)).isPresent();
    }

    @Test
    void fencingTokensAreMonotonicAcrossTransfers() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease first = leases.acquire(5, A).orElseThrow();
        PartitionLease second = leases.transfer(first, B).orElseThrow();
        Member c = new Member("c", "host-c", 9072);
        PartitionLease third = leases.transfer(second, c).orElseThrow();

        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(third.fencingToken()).isGreaterThan(second.fencingToken());
    }

    @Test
    void manyThreadsRacingToAcquireTheSamePartitionProduceExactlyOneWinner() throws Exception {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        int contenders = 32;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Optional<PartitionLease>> results = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                Member candidate = new Member("m" + i, "host-" + i, 9000 + i);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    results.add(leases.acquire(7, candidate));
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            long winners = results.stream().filter(Optional::isPresent).count();
            assertThat(winners)
                    .as("exactly one of %s real, concurrent contenders wins", contenders)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyThreadsRacingToTransferTheSamePartitionProduceExactlyOneWinner() throws Exception {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(7, A).orElseThrow();

        int contenders = 32;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                Member candidate = new Member("m" + i, "host-" + i, 9000 + i);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (leases.transfer(aOwnsIt, candidate).isPresent()) {
                        wins.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(wins.get())
                    .as("exactly one of %s real, concurrent transfer attempts from the same premise wins", contenders)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}

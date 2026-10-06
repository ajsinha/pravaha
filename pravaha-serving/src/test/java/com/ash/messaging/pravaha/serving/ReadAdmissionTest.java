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
package com.ash.messaging.pravaha.serving;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How many reads run at once, and what happens to the rest (ADR-030).
 *
 * <p>Once one engine answers both continuous queries and request/response, an unbounded read path
 * is how a client with a loop stops a continuous query from keeping up with its input. The
 * continuous query is the thing with a service level; the read is the thing that can be told to
 * come back.
 */
// 120s, not 30. These cases hold real permits and wait on real threads, so the deadline is a
// wall-clock one -- and a wall-clock deadline measures the machine as much as the code. At 30s this
// failed a full gate while three builds shared the box (14 JVMs), then passed alone in seconds. The
// point of the timeout is to stop a deadlock hanging the suite for ever, and 120s does that just as
// well without turning a busy machine into a red build.
@Timeout(120)
class ReadAdmissionTest {

    private static final Principal ACME = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal OTHER = new Principal("sam", "globex", Set.of("analyst"), Map.of());

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aReadThatFitsIsAdmitted() {
        ReadAdmission admission = new ReadAdmission(2, 0, 1.0, Duration.ZERO);

        try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
            assertThat(admission.inFlight()).isEqualTo(1);
        }

        assertThat(admission.inFlight()).isZero();
        assertThat(admission.admittedCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aFullNodeWithNoQueueRefusesRatherThanWaits() {
        ReadAdmission admission = new ReadAdmission(1, 0, 1.0, Duration.ZERO);

        try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
            assertThatThrownBy(() -> admission.acquire(OTHER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4026")
                    // The message says why the design refuses rather than queues, because the
                    // operator reading it is deciding whether to raise a limit.
                    .hasMessageContaining("nobody is waiting for");
        }

        assertThat(admission.rejectedCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aReadThatWaitsTooLongIsToldApartFromOneThatNeverWaited() {
        ReadAdmission admission = new ReadAdmission(1, 4, 1.0, Duration.ofMillis(50));

        try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
            assertThatThrownBy(() -> admission.acquire(OTHER))
                    .isInstanceOf(PravahaException.class)
                    // PRV-4027, not PRV-4026. One says "the node is full right now" and the other
                    // says "you waited your turn and it did not come"; the fixes are different.
                    .hasMessageContaining("PRV-4027");
        }

        assertThat(admission.queueTimedOutCount()).isEqualTo(1);
        assertThat(admission.rejectedCount()).isZero();
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aQueuedReadGetsThePermitWhenOneIsReleased() throws Exception {
        ReadAdmission admission = new ReadAdmission(1, 4, 1.0, Duration.ofSeconds(5));
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ReadAdmission.Lease held = admission.acquire(ACME);
        Thread second = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            try (ReadAdmission.Lease ignored = admission.acquire(OTHER)) {
                finished.countDown();
            } catch (Throwable t) {
                failure.set(t);
                finished.countDown();
            }
        });

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        held.close();

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        second.join();
        assertThat(failure.get()).isNull();
        assertThat(admission.admittedCount()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void oneTenantCannotHoldEveryPermit() {
        // Four permits, half of them any one tenant's share.
        ReadAdmission admission = new ReadAdmission(4, 0, 0.5, Duration.ZERO);

        try (ReadAdmission.Lease ignored1 = admission.acquire(ACME);
                ReadAdmission.Lease ignored2 = admission.acquire(ACME)) {

            assertThatThrownBy(() -> admission.acquire(ACME))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4028");

            // And the capacity acme could not have is still there for somebody else. Without this,
            // the symptom the other tenants report is "Pravaha is down".
            try (ReadAdmission.Lease ignored = admission.acquire(OTHER)) {
                assertThat(admission.inFlight()).isEqualTo(3);
            }
        }

        assertThat(admission.tenantRejectedCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aTenantsPermitsComeBackWhenItsReadsFinish() {
        ReadAdmission admission = new ReadAdmission(4, 0, 0.5, Duration.ZERO);

        for (int i = 0; i < 10; i++) {
            try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
                assertThat(admission.inFlight()).isEqualTo(1);
            }
        }

        assertThat(admission.tenantRejectedCount()).isZero();
    }

    @Test
    void aBurstOfArrivalsNeverQueuesPastMaxQueued() throws Exception {
        // J21-4. The queue depth was read and then incremented, so arrivals landing together could
        // each see room and together queue past the limit. One permit, held; a latch-gated burst of
        // arrivals, each from its own tenant so the per-tenant share does not refuse them first.
        int maxQueued = 3;
        int arrivals = 32;
        for (int round = 0; round < 10; round++) {
            ReadAdmission admission = new ReadAdmission(1, maxQueued, 1.0, Duration.ofSeconds(60));
            ReadAdmission.Lease held = admission.acquire(ACME);
            CountDownLatch go = new CountDownLatch(1);
            Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < arrivals; i++) {
                Principal caller = new Principal("u" + i, "tenant-" + i, Set.of("analyst"), Map.of());
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        admission.acquire(caller).close();
                        outcomes.computeIfAbsent("admitted", k -> new AtomicInteger())
                                .incrementAndGet();
                    } catch (PravahaException e) {
                        outcomes.computeIfAbsent(e.errorCode().code(), k -> new AtomicInteger())
                                .incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            go.countDown();

            // Every arrival has decided -- queued, or refused -- before the permit comes back.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (admission.rejectedCount() + admission.waiting() < arrivals && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(admission.waiting()).isEqualTo(maxQueued);
            assertThat(admission.rejectedCount()).isEqualTo(arrivals - maxQueued);

            held.close();
            for (Thread t : threads) {
                t.join();
            }
            assertThat(outcomes).containsOnlyKeys("admitted", "PRV-4026");
            assertThat(outcomes.get("admitted")).hasValue(maxQueued);
            assertThat(outcomes.get("PRV-4026")).hasValue(arrivals - maxQueued);
            assertThat(admission.waiting()).isZero();
            assertThat(admission.inFlight()).isZero();
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void queueSlotsComeBackAfterTimeoutsAndInterrupts() throws Exception {
        // J21-4. A reserved slot is returned on every way out of the wait; a leak would turn the
        // second batch's PRV-4027 (waited, gave up) into PRV-4026 (refused, never waited).
        int maxQueued = 4;
        ReadAdmission admission = new ReadAdmission(1, maxQueued, 1.0, Duration.ofMillis(50));
        try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
            for (int batch = 0; batch < 2; batch++) {
                List<Thread> threads = new ArrayList<>();
                List<String> codes = Collections.synchronizedList(new ArrayList<>());
                for (int i = 0; i < maxQueued; i++) {
                    // A tenant each, so the per-tenant share (one, of one permit) does not refuse first.
                    Principal caller = new Principal("u" + i, "tenant-" + i, Set.of("analyst"), Map.of());
                    threads.add(Thread.ofPlatform().start(() -> {
                        try {
                            admission.acquire(caller).close();
                            codes.add("admitted");
                        } catch (PravahaException e) {
                            codes.add(e.errorCode().code());
                        }
                    }));
                }
                for (Thread t : threads) {
                    t.join();
                }
                assertThat(codes).hasSize(maxQueued).containsOnly("PRV-4027");
                assertThat(admission.waiting()).isZero();
            }
        }

        // Interrupted while waiting: the slot comes back too.
        ReadAdmission patient = new ReadAdmission(1, 1, 1.0, Duration.ofSeconds(60));
        try (ReadAdmission.Lease ignored = patient.acquire(ACME)) {
            AtomicReference<String> code = new AtomicReference<>();
            Thread waiter = Thread.ofPlatform().start(() -> {
                try {
                    patient.acquire(OTHER).close();
                } catch (PravahaException e) {
                    code.set(e.errorCode().code());
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (patient.waiting() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            waiter.interrupt();
            waiter.join();
            assertThat(code.get()).isEqualTo("PRV-4027");
            assertThat(patient.waiting()).isZero();
            assertThat(patient.rejectedCount()).isZero();
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void closingTwiceDoesNotHandBackAPermitTwice() {
        ReadAdmission admission = new ReadAdmission(1, 0, 1.0, Duration.ZERO);

        ReadAdmission.Lease lease = admission.acquire(ACME);
        lease.close();
        lease.close();

        // If close were not idempotent, the node would now believe it has two permits and would run
        // two reads where it was configured for one -- quietly, and only under load.
        assertThat(admission.inFlight()).isZero();
        try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
            assertThatThrownBy(() -> admission.acquire(OTHER)).isInstanceOf(PravahaException.class);
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aShareThatRoundsToZeroDoesNotRefuseEveryRead() {
        // 1% of four permits is 0.04. Rounding that down would refuse every read from every tenant,
        // which is a configuration mistake that must not become an outage.
        ReadAdmission admission = new ReadAdmission(4, 0, 0.01, Duration.ZERO);

        try (ReadAdmission.Lease ignored = admission.acquire(ACME)) {
            assertThat(admission.inFlight()).isEqualTo(1);
        }
    }

    @Test
    void unlimitedAdmitsWhateverAnEmbeddedCallerAsksFor() {
        List<ReadAdmission.Lease> leases = new java.util.ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            leases.add(ReadAdmission.UNLIMITED.acquire(ACME));
        }
        leases.forEach(ReadAdmission.Lease::close);

        assertThat(ReadAdmission.UNLIMITED.inFlight()).isZero();
    }

    @Test
    void nonsenseConfigurationIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new ReadAdmission(0, 0, 1.0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadAdmission(1, -1, 1.0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadAdmission(1, 0, 0.0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadAdmission(1, 0, 1.5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aQueryPathUnderAdmissionRefusesWhenTheNodeIsFull() {
        StreamSchema schema = StreamSchema.builder("user_volume")
                .field("user_id", Types.string())
                .field("total", Types.int64())
                .build();
        ServedView view = new ServedView("user_volume", schema, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.commit(10);

        ReadAdmission admission = new ReadAdmission(1, 0, 1.0, Duration.ZERO);
        ViewQuery queries = new ViewQuery(
                // acme's view, by its engine name (ADR-060): acme reads it as user_volume.
                new ViewCatalog().registerAs("acme.default.user_volume", view),
                com.ash.messaging.pravaha.security.SecurityPolicy.PERMISSIVE,
                com.ash.messaging.pravaha.security.AuditSink.NONE,
                admission,
                Duration.ZERO);

        try (ReadAdmission.Lease ignored = admission.acquire(OTHER)) {
            assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", ACME))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4026");
        }

        // And it answers again the moment the permit comes back.
        assertThat(queries.execute("SELECT user_id FROM user_volume", ACME).size())
                .isEqualTo(1);
    }
}

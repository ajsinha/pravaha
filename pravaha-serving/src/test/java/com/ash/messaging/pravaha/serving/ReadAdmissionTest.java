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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
@Timeout(30)
class ReadAdmissionTest {

    private static final Principal ACME = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal OTHER = new Principal("sam", "globex", Set.of("analyst"), Map.of());

    @Test
    void aReadThatFitsIsAdmitted() {
        ReadAdmission admission = new ReadAdmission(2, 0, 1.0, Duration.ZERO);

        try (ReadAdmission.Lease lease = admission.acquire(ACME)) {
            assertThat(admission.inFlight()).isEqualTo(1);
        }

        assertThat(admission.inFlight()).isZero();
        assertThat(admission.admittedCount()).isEqualTo(1);
    }

    @Test
    void aFullNodeWithNoQueueRefusesRatherThanWaits() {
        ReadAdmission admission = new ReadAdmission(1, 0, 1.0, Duration.ZERO);

        try (ReadAdmission.Lease held = admission.acquire(ACME)) {
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
    void aReadThatWaitsTooLongIsToldApartFromOneThatNeverWaited() {
        ReadAdmission admission = new ReadAdmission(1, 4, 1.0, Duration.ofMillis(50));

        try (ReadAdmission.Lease held = admission.acquire(ACME)) {
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
    void aQueuedReadGetsThePermitWhenOneIsReleased() throws Exception {
        ReadAdmission admission = new ReadAdmission(1, 4, 1.0, Duration.ofSeconds(5));
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ReadAdmission.Lease held = admission.acquire(ACME);
        Thread second = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            try (ReadAdmission.Lease lease = admission.acquire(OTHER)) {
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
    void oneTenantCannotHoldEveryPermit() {
        // Four permits, half of them any one tenant's share.
        ReadAdmission admission = new ReadAdmission(4, 0, 0.5, Duration.ZERO);

        try (ReadAdmission.Lease first = admission.acquire(ACME);
                ReadAdmission.Lease second = admission.acquire(ACME)) {

            assertThatThrownBy(() -> admission.acquire(ACME))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4028");

            // And the capacity acme could not have is still there for somebody else. Without this,
            // the symptom the other tenants report is "Pravaha is down".
            try (ReadAdmission.Lease other = admission.acquire(OTHER)) {
                assertThat(admission.inFlight()).isEqualTo(3);
            }
        }

        assertThat(admission.tenantRejectedCount()).isEqualTo(1);
    }

    @Test
    void aTenantsPermitsComeBackWhenItsReadsFinish() {
        ReadAdmission admission = new ReadAdmission(4, 0, 0.5, Duration.ZERO);

        for (int i = 0; i < 10; i++) {
            try (ReadAdmission.Lease lease = admission.acquire(ACME)) {
                assertThat(admission.inFlight()).isEqualTo(1);
            }
        }

        assertThat(admission.tenantRejectedCount()).isZero();
    }

    @Test
    void closingTwiceDoesNotHandBackAPermitTwice() {
        ReadAdmission admission = new ReadAdmission(1, 0, 1.0, Duration.ZERO);

        ReadAdmission.Lease lease = admission.acquire(ACME);
        lease.close();
        lease.close();

        // If close were not idempotent, the node would now believe it has two permits and would run
        // two reads where it was configured for one -- quietly, and only under load.
        assertThat(admission.inFlight()).isZero();
        try (ReadAdmission.Lease one = admission.acquire(ACME)) {
            assertThatThrownBy(() -> admission.acquire(OTHER)).isInstanceOf(PravahaException.class);
        }
    }

    @Test
    void aShareThatRoundsToZeroDoesNotRefuseEveryRead() {
        // 1% of four permits is 0.04. Rounding that down would refuse every read from every tenant,
        // which is a configuration mistake that must not become an outage.
        ReadAdmission admission = new ReadAdmission(4, 0, 0.01, Duration.ZERO);

        try (ReadAdmission.Lease lease = admission.acquire(ACME)) {
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
                new ViewCatalog().register(view),
                com.ash.messaging.pravaha.security.SecurityPolicy.PERMISSIVE,
                com.ash.messaging.pravaha.security.AuditSink.NONE,
                admission,
                Duration.ZERO);

        try (ReadAdmission.Lease held = admission.acquire(OTHER)) {
            assertThatThrownBy(() -> queries.execute("SELECT user_id FROM user_volume", ACME))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4026");
        }

        // And it answers again the moment the permit comes back.
        assertThat(queries.execute("SELECT user_id FROM user_volume", ACME).size())
                .isEqualTo(1);
    }
}

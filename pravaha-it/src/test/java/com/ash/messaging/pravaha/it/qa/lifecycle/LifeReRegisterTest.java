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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/** LIFE-076..082 -- re-registration after a drop: state is never inherited, except by sharing. */
@Tag("qa")
class LifeReRegisterTest extends LifecycleTestSupport {

    @Test
    void life076_theSameSqlUnderTheSameNameAfterADropStartsWithEmptyState() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        assertThat(rows("SELECT usr FROM v1")).as("V-before: non-empty").isNotEmpty();
        String fingerprintBefore = registry.require("v1").fingerprint().toString();

        registry.drop("v1");
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);

        assertThat(rows("SELECT usr FROM v1"))
                .as("a fresh computation starts empty -- state is not inherited across a drop")
                .isEmpty();
        assertThat(registry.require("v1").rowsIn()).as("ROWS IN restarts at 0").isEqualTo(0);
        assertThat(registry.require("v1").fingerprint().toString())
                .as("the fingerprint is a hash of the plan, stable across drops -- not an instance identity")
                .isEqualTo(fingerprintBefore);
    }

    @Test
    void life078_reRegisteringUnderADifferentNameAfterADrop() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        registry.drop("v1");
        assertThat(registry.names())
                .as("V-before: empty between the drop and the re-register")
                .isEmpty();

        registry.register("v2", S1, List.of(0), Principal.ANONYMOUS);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(rows("SELECT usr FROM v2"))
                .as("a fresh computation, starting empty")
                .isEmpty();
    }

    @Test
    void life079_reRegisteringWhileAnotherNameStillHoldsTheComputationSharesIt() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        push("a", 1, "ann", 100, 1);
        List<String> warmRows = readSorted("SELECT usr, amount FROM b");
        assertThat(warmRows).isNotEmpty();

        registry.drop("a");
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);

        assertThat(readSorted("SELECT usr, amount FROM a"))
                .as("a inherits b's warm state instantly -- the opposite of LIFE-076 for the same command, "
                        + "because this time a holder of the fingerprint survived the drop")
                .isEqualTo(warmRows);
    }

    @Test
    void life080_reRegisteringTheSameSqlWithDifferentKeysAfterADropUsesTheNewKeys() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "u1", 10, 1);
        push("v1", 2, "u1", 20, 1);
        push("v1", 3, "u2", 10, 1);
        assertThat(rows("SELECT usr FROM v1"))
                .as("keyed on usr: 2 distinct users")
                .hasSize(2);

        registry.drop("v1");
        registry.register("v1", S1, List.of(0, 1), Principal.ANONYMOUS);
        push("v1", 1, "u1", 10, 1);
        push("v1", 2, "u1", 20, 1);
        push("v1", 3, "u2", 10, 1);

        assertThat(rows("SELECT usr, amount FROM v1"))
                .as("the fresh path takes the new keys: 3 distinct (usr, amount) pairs, not 2 distinct users")
                .hasSize(3);
    }

    @Test
    @org.junit.jupiter.api.Disabled(
            "LIFE-081 defect (FINDINGS: Lifecycle L-3): a read racing the gap between a drop and its "
                    + "re-register can surface PRV-2002 ('Object 'v1' not found. Known streams: []') instead "
                    + "of the serving layer's PRV-4023 (SERVING_NO_SUCH_VIEW). ViewQuery re-plans on a cache "
                    + "miss, and while v1 is momentarily in neither the stream nor the view catalogue, the SQL "
                    + "planner treats it as an unrecognised identifier and reports zero known streams -- which "
                    + "is also misleading in its own right, since txn is bound the whole time.")
    void life081_aDropAndReRegisterUnderLoadNeverServesStaleRowsOrAnUnexpectedError() throws Exception {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);

        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong unexpected = new java.util.concurrent.atomic.AtomicLong();
        java.util.List<Throwable> unexpectedSamples = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            while (running.get()) {
                try {
                    rows("SELECT usr, amount FROM v1");
                } catch (com.ash.messaging.pravaha.api.PravahaException e) {
                    if (e.errorCode().number() != 4023) {
                        unexpected.incrementAndGet();
                        unexpectedSamples.add(e);
                    }
                } catch (RuntimeException e) {
                    unexpected.incrementAndGet();
                    unexpectedSamples.add(e);
                }
                reads.incrementAndGet();
            }
        });
        reader.start();
        for (int i = 0; i < 20; i++) {
            registry.drop("v1");
            registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        }
        running.set(false);
        reader.join(java.time.Duration.ofSeconds(5).toMillis());

        assertThat(unexpected.get())
                .as(
                        "every reader response is either a successful read or PRV-4023 -- nothing else. Samples: %s",
                        unexpectedSamples.stream()
                                .limit(3)
                                .map(Object::toString)
                                .toList())
                .isEqualTo(0);
        assertThat(reads.get())
                .as("the reader must have made enough calls for a race to have had a chance to show up")
                .isGreaterThan(100);
    }

    @Test
    void life082_registeringAfterCloseOfTheWholeRegistryStillSucceeds() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        registry.close();
        assertThat(registry.names()).as("V-before: close() clears both maps").isEmpty();

        // No closed flag exists on QueryRegistry -- close() clears state but leaves the object
        // usable, so a third registration is expected to succeed, starting a lane on a registry the
        // caller believes is shut down.
        registry.register("c", S1 + " WHERE id > 1", List.of(0), Principal.ANONYMOUS);
        assertThat(registry.names()).containsExactly("c");
        push("c", 1, "ann", 100, 1);
        assertThat(registry.require("c").rowsIn()).isEqualTo(1);
    }
}

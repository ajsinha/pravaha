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
package com.ash.messaging.pravaha.spring.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PravahaTester} waiting for rows that arrive on a source's schedule rather than the test's:
 * a filesystem source bound to the stream, read after the engine starts.
 */
@PravahaTest(
        properties = {
            "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
            "pravaha.sources.txn.plugin=filesystem",
            "pravaha.sources.txn.options.schema=user_id:STRING,amount:INT64",
            "pravaha.queries.big_txn.sql=SELECT user_id, amount FROM txn WHERE amount > 100",
            "pravaha.queries.big_txn.keys=user_id"
        })
class PravahaTesterTest {

    /** Nested, so the slice uses it rather than searching the package for an application. */
    @Configuration(proxyBeanMethods = false)
    static class NoApplication {}

    record Big(String userId, long amount) {}

    @DynamicPropertySource
    static void sourceFile(DynamicPropertyRegistry registry) {
        try {
            Path file = Files.createTempDirectory("pravaha-tester-").resolve("txn.csv");
            Files.writeString(file, "u1,300\nu2,20\nu3,700\n");
            file.toFile().deleteOnExit();
            registry.add("pravaha.sources.txn.options.path", file::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    PravahaTester pravaha;

    @Test
    void aViewFedByASourceIsAwaitedOnItsCommitsNotOnAClock() {
        List<Big> big = pravaha.awaitView("big_txn", Big.class, rows -> rows.size() == 2);

        assertThat(big).containsExactlyInAnyOrder(new Big("u1", 300), new Big("u3", 700));
        assertThat(pravaha.awaitView("big_txn", rows -> rows.contains(Map.of("user_id", "u1", "amount", 300L))))
                .hasSize(2);
    }

    @Test
    void aWaitThatRunsOutSaysWhatItWasWaitingForAndWhatItSaw() {
        assertThatThrownBy(() ->
                        pravaha.withTimeout(Duration.ofMillis(200)).awaitView("big_txn", rows -> rows.size() == 99))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("view 'big_txn'")
                .hasMessageContaining("RUNNING")
                .hasMessageContaining("its answer was");
    }

    @Test
    void anUnknownQueryIsRefusedRatherThanWaitedFor() {
        assertThatThrownBy(() -> pravaha.awaitView("nope", rows -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'nope'")
                .hasMessageContaining("big_txn");
        assertThatThrownBy(() -> pravaha.awaitListeners("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pravaha.withTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withNoListenersThereIsNothingToWaitFor() {
        assertThat(pravaha.awaitListeners().awaitListeners("big_txn")).isNotNull();
    }
}

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
package com.ash.messaging.pravaha.server.api;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

import static org.assertj.core.api.Assertions.assertThat;

/** Testing a named connection: through the plugin's own lifecycle, in bounded time, never showing a secret. */
class ConnectionTesterTest {

    /** A plugin whose open takes {@code openMillis} and fails, when asked to, quoting its password. */
    private static PravahaPlugin plugin(long openMillis, boolean fail) {
        return new PravahaPlugin() {
            private String password = "";

            @Override
            public String name() {
                return "stub";
            }

            @Override
            public Version version() {
                return Version.apiVersion();
            }

            @Override
            public void configure(PluginContext context) {
                password = context.get("sasl.password", "");
            }

            @Override
            public void open() {
                try {
                    Thread.sleep(openMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (fail) {
                    throw new IllegalStateException("login refused for password " + password);
                }
            }

            @Override
            public void close() {}

            @Override
            public HealthStatus health() {
                return HealthStatus.healthy();
            }
        };
    }

    @Test
    void aHealthyConnectionIsOkWithItsLatency() {
        ApiDtos.ConnectionTest outcome =
                ConnectionTester.test("c", "stub", new ConnectionTester.Found(plugin(0, false), "source"), Map.of(), 5);
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.state()).isEqualTo("HEALTHY");
        assertThat(outcome.role()).isEqualTo("source");
        assertThat(outcome.code()).isNull();
    }

    @Test
    void aFailureIsReportedWithoutTheSecret() {
        ApiDtos.ConnectionTest outcome = ConnectionTester.test(
                "c",
                "stub",
                new ConnectionTester.Found(plugin(0, true), "source"),
                Map.of("sasl.password", "hunter22"),
                5);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(outcome.code()).isEqualTo("PRV-1073");
        assertThat(outcome.detail()).contains("login refused").doesNotContain("hunter22");
    }

    @Test
    void aConnectionThatDoesNotAnswerIsCutOffAtTheBound() {
        long started = System.nanoTime();
        ApiDtos.ConnectionTest outcome = ConnectionTester.test(
                "c", "stub", new ConnectionTester.Found(plugin(30_000, false), "source"), Map.of(), 1);
        assertThat(outcome.state()).isEqualTo("TIMED_OUT");
        assertThat(outcome.ok()).isFalse();
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }

    @Test
    void aPluginTheNodeCannotLoadIsSaidSo() {
        ApiDtos.ConnectionTest outcome =
                ConnectionTester.test("c", "nonesuch", (ConnectionTester.Found) null, Map.of(), 5);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("nonesuch");
    }
}

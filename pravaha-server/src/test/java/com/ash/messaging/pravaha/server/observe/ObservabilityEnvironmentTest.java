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
package com.ash.messaging.pravaha.server.observe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What {@code pravaha.logging.format} and {@code pravaha.tracing.*} become in Spring Boot's settings. */
class ObservabilityEnvironmentTest {

    private static MockEnvironment decide(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        new ObservabilityEnvironment(new org.springframework.boot.logging.DeferredLogs())
                .postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void offByDefaultExcludesTheTracerAndKeepsWhatWasExcludedAlready() {
        MockEnvironment environment = decide("spring.autoconfigure.exclude", "com.example.Other");
        assertThat(environment.getProperty("spring.autoconfigure.exclude"))
                .startsWith("com.example.Other,")
                .contains("OpenTelemetryTracingAutoConfiguration", "OtlpTracingAutoConfiguration");
        assertThat(environment.getProperty("management.tracing.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("logging.structured.format.console")).isNull();
    }

    @Test
    void onWithAnEndpointSamplesEverythingAndExportsThere() {
        MockEnvironment environment = decide(
                "pravaha.tracing.enabled", "true", "pravaha.tracing.endpoint", "http://collector:4318/v1/traces");
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
        assertThat(environment.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://collector:4318/v1/traces");
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }

    @Test
    void theStandardOtelVariablesAreTheFallbackAndBootsOwnSettingsWin() {
        assertThat(decide("pravaha.tracing.enabled", "true", "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318/")
                        .getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://otel:4318/v1/traces");
        assertThat(decide(
                                "pravaha.tracing.enabled", "true",
                                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://traces:4318/v1/traces")
                        .getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://traces:4318/v1/traces");
        assertThat(decide(
                                "pravaha.tracing.enabled", "true",
                                "pravaha.tracing.sampling-probability", "0.25",
                                "management.tracing.sampling.probability", "0.5")
                        .getProperty("management.tracing.sampling.probability"))
                .isEqualTo("0.5");
    }

    @Test
    void jsonLogsAreLogstashShapedUnlessAFormatWasChosen() {
        assertThat(decide("pravaha.logging.format", "json").getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
        assertThat(decide("pravaha.logging.format", "JSON", "logging.structured.format.console", "ecs")
                        .getProperty("logging.structured.format.console"))
                .isEqualTo("ecs");
    }

    @Test
    void whatCannotMeanWhatItSaysIsRefused() {
        assertThatThrownBy(() -> decide("pravaha.tracing.enabled", "yes"))
                .hasMessageContaining("pravaha.tracing.enabled is 'yes'");
        assertThatThrownBy(() -> decide("pravaha.tracing.enabled", "true", "pravaha.tracing.sampling-probability", "2"))
                .hasMessageContaining("from 0 (trace nothing) to 1");
        assertThatThrownBy(
                        () -> decide("pravaha.tracing.enabled", "true", "pravaha.tracing.endpoint", "collector:4318"))
                .hasMessageContaining("http:// or https:// URL");
    }
}

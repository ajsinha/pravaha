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
package com.ash.messaging.pravaha.common.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The engine's span facade: nothing until installed, every span closed, a failure marked, a broken tracer harmless. */
class EngineSpansTest {

    private final List<String> events = new ArrayList<>();

    private final EngineSpans.Backend recording = (name, attributes) -> {
        events.add("start " + name + " " + attributes);
        return new EngineSpans.Span() {
            @Override
            public void attribute(String key, String value) {
                events.add("attribute " + key + "=" + value);
            }

            @Override
            public void failed(Throwable failure) {
                events.add("failed " + failure.getMessage());
            }

            @Override
            public void close() {
                events.add("end " + name);
            }
        };
    };

    @AfterEach
    void uninstall() {
        EngineSpans.uninstall(recording);
    }

    @Test
    void nothingIsTracedUntilABackendIsInstalled() {
        assertThat(EngineSpans.tracing()).isFalse();
        assertThat(EngineSpans.start("pravaha.checkpoint")).isSameAs(EngineSpans.NO_SPAN);
        assertThat(EngineSpans.traced("x", "k", "v", () -> 7)).isEqualTo(7);
        assertThat(events).isEmpty();
    }

    @Test
    void aTracedBodyIsOneSpanWithItsAttributeAndAFailureIsMarkedThenRethrown() {
        EngineSpans.install(recording);
        assertThat(EngineSpans.tracing()).isTrue();
        assertThat(EngineSpans.traced("pravaha.query.register", "pravaha.query", "orders", () -> "ok"))
                .isEqualTo("ok");
        assertThatThrownBy(() -> EngineSpans.run("pravaha.checkpoint", "id", "3", () -> {
                    throw new IllegalStateException("disk full");
                }))
                .hasMessage("disk full");
        assertThat(events)
                .containsExactly(
                        "start pravaha.query.register " + Map.of("pravaha.query", "orders"),
                        "end pravaha.query.register",
                        "start pravaha.checkpoint " + Map.of("id", "3"),
                        "failed disk full",
                        "end pravaha.checkpoint");
    }

    @Test
    void aNullAttributeIsLeftOutAndPairsAreRequired() {
        EngineSpans.install(recording);
        EngineSpans.start("s", "a", null, "b", "2").close();
        assertThat(events).first().isEqualTo("start s " + Map.of("b", "2"));
        assertThatThrownBy(() -> EngineSpans.start("s", "odd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTracerThatThrowsDoesNotFailTheWorkAndUninstallLeavesAnothersBackendAlone() {
        EngineSpans.Backend broken = (name, attributes) -> {
            throw new IllegalStateException("tracer down");
        };
        EngineSpans.install(broken);
        try {
            assertThat(EngineSpans.traced("s", "k", "v", () -> 1)).isEqualTo(1);
            EngineSpans.uninstall(recording); // not the installed one: nothing happens
            assertThat(EngineSpans.tracing()).isTrue();
        } finally {
            EngineSpans.uninstall(broken);
        }
        assertThat(EngineSpans.tracing()).isFalse();
    }
}

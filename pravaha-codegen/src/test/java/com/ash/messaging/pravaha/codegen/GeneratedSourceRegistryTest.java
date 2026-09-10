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
package com.ash.messaging.pravaha.codegen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention of generated source.
 *
 * <p>Two properties matter and they pull against each other: the source has to be there when a
 * generated plan misbehaves, and it must not be a way to run a node out of memory. At the query
 * density this design targets, "keep everything" is tens of thousands of sources -- so retention is
 * bounded and off unless asked for, and both halves are asserted here rather than assumed.
 */
class GeneratedSourceRegistryTest {

    private static GeneratedStage stage(String name, String source) {
        return new GeneratedStage(name, source, new Object(), 12L);
    }

    @Test
    void aRetainedSourceCanBeFoundAgain() {
        GeneratedSourceRegistry registry = new GeneratedSourceRegistry(true, 10);
        registry.retain("q-1", stage("Stage$1", "class Stage$1 {}\n"));

        assertThat(registry.find("q-1")).hasValueSatisfying(entry -> {
            assertThat(entry.className()).isEqualTo("Stage$1");
            assertThat(entry.source()).contains("class Stage$1");
            assertThat(entry.compileMillis()).isEqualTo(12L);
            assertThat(entry.sourceLines()).isEqualTo(1);
        });
    }

    @Test
    void retentionIsOffUnlessAskedFor() {
        // Generated source can carry literals from the query, which are data. That is a second
        // reason for opt-in beyond memory, and it is why disabled is the default a node starts with.
        GeneratedSourceRegistry registry = GeneratedSourceRegistry.disabled();
        registry.retain("q-1", stage("Stage$1", "class Stage$1 {}"));

        assertThat(registry.isEnabled()).isFalse();
        assertThat(registry.find("q-1")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void theOldestSourcesAreEvictedRatherThanGrowingWithoutLimit() {
        // A debug facility that grows without limit becomes the incident it was meant to diagnose.
        GeneratedSourceRegistry registry = new GeneratedSourceRegistry(true, 3);
        for (int i = 0; i < 10; i++) {
            registry.retain("q-" + i, stage("Stage$" + i, "class Stage$" + i + " {}"));
        }

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.find("q-9")).isPresent();
        assertThat(registry.find("q-0")).as("the oldest goes first").isEmpty();
    }

    @Test
    void aQueryThatIsStillBeingReadIsNotTheOneEvicted() {
        // Access order, not insertion order. The source somebody is actively looking at during an
        // incident is exactly the one a simple FIFO would drop.
        GeneratedSourceRegistry registry = new GeneratedSourceRegistry(true, 3);
        registry.retain("old", stage("Old", "class Old {}"));
        registry.retain("b", stage("B", "class B {}"));
        registry.retain("c", stage("C", "class C {}"));

        assertThat(registry.find("old")).isPresent(); // touched
        registry.retain("d", stage("D", "class D {}"));

        assertThat(registry.find("old"))
                .as("recently read, so not the eviction candidate")
                .isPresent();
        assertThat(registry.find("b")).isEmpty();
    }

    @Test
    void droppingAQueryForgetsItsSource() {
        GeneratedSourceRegistry registry = new GeneratedSourceRegistry(true, 10);
        registry.retain("q-1", stage("Stage$1", "class Stage$1 {}"));
        registry.forget("q-1");

        assertThat(registry.find("q-1")).isEmpty();
    }

    @Test
    void aRetentionLimitBelowOneIsRefused() {
        assertThatThrownBy(() -> new GeneratedSourceRegistry(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}

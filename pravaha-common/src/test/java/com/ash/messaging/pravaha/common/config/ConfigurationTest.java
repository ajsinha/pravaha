/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationTest {

    private static Configuration sample() {
        return Configuration.builder()
                .set("pravaha.runtime.lanes", "14")
                .set("pravaha.runtime.batch.max.linger", "200us")
                .set("pravaha.state.offheap.max", "1GB")
                .set("pravaha.gateways.grpc.tls", "true")
                .set("pravaha.cluster.peers", "a:9070, b:9070")
                .set("pravaha.security.oidc.secret", "hunter2")
                .build();
    }

    @Test
    void typedAccessorsReadTheirTypes() {
        Configuration c = sample();
        assertThat(c.requireInt("pravaha.runtime.lanes")).isEqualTo(14);
        assertThat(c.getDuration("pravaha.runtime.batch.max.linger")).hasValue(Duration.ofNanos(200_000));
        assertThat(c.getDataSize("pravaha.state.offheap.max")).hasValue(1024L * 1024 * 1024);
        assertThat(c.getBoolean("pravaha.gateways.grpc.tls", false)).isTrue();
        assertThat(c.getList("pravaha.cluster.peers")).containsExactly("a:9070", "b:9070");
    }

    @Test
    void fallbacksApplyOnlyWhenAKeyIsAbsent() {
        Configuration c = sample();
        assertThat(c.getInt("pravaha.runtime.lanes", 4)).isEqualTo(14);
        assertThat(c.getInt("absent", 4)).isEqualTo(4);
        assertThat(c.getString("absent", "x")).isEqualTo("x");
        assertThat(c.getDuration("absent", Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
        assertThat(c.getDataSize("absent", 99L)).isEqualTo(99L);
        assertThat(c.getDouble("absent", 0.5)).isEqualTo(0.5);
        assertThat(c.getLong("absent", 7L)).isEqualTo(7L);
    }

    @Test
    void aMissingRequiredKeyNamesItself() {
        assertThatThrownBy(() -> sample().requireString("pravaha.absent"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1020")
                .hasMessageContaining("pravaha.absent");
    }

    @Test
    void rangeValidationNamesTheBoundsAndTheOrigin() {
        Configuration c = sample();
        int lanes = c.requireInt("pravaha.runtime.lanes");
        assertThat(c.requireInRange("pravaha.runtime.lanes", lanes, 1, 64)).isEqualTo(14);
        assertThatThrownBy(() -> c.requireInRange("pravaha.runtime.lanes", lanes, 1, 8))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("between 1 and 8")
                .hasMessageContaining("programmatic");
    }

    @Test
    void subsetStripsThePrefixAndKeepsProvenance() {
        // Lets a plugin be handed only its own configuration, so a connector cannot read -- or log
        // -- something that is none of its business.
        Configuration runtime = sample().subset("pravaha.runtime");
        assertThat(runtime.requireInt("lanes")).isEqualTo(14);
        assertThat(runtime.has("batch.max.linger")).isTrue();
        assertThat(runtime.has("pravaha.state.offheap.max")).isFalse();
        assertThat(runtime.sourceOf("lanes")).hasValue(ConfigSource.PROGRAMMATIC);
        assertThat(sample().subset("pravaha.runtime.")).hasToString(runtime.toString());
    }

    @Test
    void subsetOfAnUnknownPrefixIsEmpty() {
        assertThat(sample().subset("nothing.here").size()).isZero();
    }

    @Test
    void describeMasksSecrets() {
        // This is the startup dump and the console view. It is deliberately the only bulk
        // rendering available, so there is no convenient way to print the config unmasked.
        assertThat(sample().describe())
                .anyMatch(line -> line.contains("pravaha.runtime.lanes=14"))
                .anyMatch(line -> line.contains("oidc.secret=" + Redaction.MASK))
                .noneMatch(line -> line.contains("hunter2"));
    }

    @Test
    void provenanceIsAvailablePerKey() {
        Configuration c = sample();
        assertThat(c.sourceOf("pravaha.runtime.lanes")).hasValue(ConfigSource.PROGRAMMATIC);
        assertThat(c.sourceOf("absent")).isEmpty();
        assertThat(c.entry("pravaha.runtime.lanes"))
                .get()
                .extracting(ConfigValue::origin)
                .isEqualTo("set in code");
    }

    @Test
    void keysAreSortedSoTheDumpIsReadable() {
        assertThat(sample().keys())
                .containsExactlyElementsOf(sample().keys().stream().sorted().toList());
    }

    @Test
    void anEmptyConfigurationIsUsable() {
        assertThat(Configuration.empty().size()).isZero();
        assertThat(Configuration.empty().getString("k")).isEmpty();
        assertThat(Configuration.empty().describe()).isEmpty();
    }

    @Test
    void toStringDoesNotDumpTheContents() {
        // A configuration reaching a log line by accident must not spill every value.
        assertThat(sample().toString()).isEqualTo("Configuration[6 keys]").doesNotContain("hunter2");
    }

    @Test
    void isNotASingletonSoEnginesInOneJvmDoNotShareIt() {
        // Embedded mode runs several engines per JVM and @PravahaTest gives each test its own.
        // A process-wide instance would make both impossible and tests order-dependent.
        Configuration a = Configuration.builder().set("k", "a").build();
        Configuration b = Configuration.builder().set("k", "b").build();
        assertThat(a.requireString("k")).isEqualTo("a");
        assertThat(b.requireString("k")).isEqualTo("b");
        assertThat(a).isNotSameAs(b);
    }
}

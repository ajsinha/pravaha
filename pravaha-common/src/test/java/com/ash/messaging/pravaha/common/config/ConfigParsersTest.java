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
package com.ash.messaging.pravaha.common.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigParsersTest {

    private static Configuration with(String value) {
        return Configuration.builder().set("k", value).build();
    }

    @ParameterizedTest
    @CsvSource({
        "true,true",
        "TRUE,true",
        "yes,true",
        "on,true",
        "1,true",
        "false,false",
        "no,false",
        "off,false",
        "0,false"
    })
    void parsesBooleanSpellings(String text, boolean expected) {
        assertThat(with(text).getBoolean("k")).hasValue(expected);
    }

    @Test
    void rejectsANonBoolean() {
        assertThatThrownBy(() -> with("maybe").getBoolean("k"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1022")
                .hasMessageContaining("true/false");
    }

    @ParameterizedTest
    @CsvSource({
        "1ns,1",
        "500ns,500",
        "1us,1000",
        "200us,200000",
        "1ms,1000000",
        "1s,1000000000",
        "30s,30000000000",
        "2min,120000000000",
        "1h,3600000000000"
    })
    void parsesDurationUnits(String text, long expectedNanos) {
        assertThat(with(text).getDuration("k")).hasValue(Duration.ofNanos(expectedNanos));
    }

    @Test
    void acceptsFractionalAndVerboseDurations() {
        assertThat(with("1.5s").getDuration("k")).hasValue(Duration.ofMillis(1500));
        assertThat(with("5 minutes").getDuration("k")).hasValue(Duration.ofMinutes(5));
        assertThat(with("2 days").getDuration("k")).hasValue(Duration.ofDays(2));
    }

    @Test
    void rejectsADurationWithNoUnit() {
        // Guessing the unit is how a 30-second timeout silently becomes 30 milliseconds.
        assertThatThrownBy(() -> with("30").getDuration("k"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1023")
                .hasMessageContaining("ns, us, ms, s, min, h or d");
    }

    @ParameterizedTest
    @ValueSource(strings = {"30x", "abc", "s", ""})
    void rejectsMalformedDurations(String text) {
        assertThatThrownBy(() -> with(text).getDuration("k")).isInstanceOf(ConfigurationException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "1024,1024", "1KB,1024", "4MB,4194304", "1GB,1073741824",
        "256MB,268435456", "1TB,1099511627776", "512B,512", "2.5MB,2621440"
    })
    void parsesDataSizesInBinaryUnits(String text, long expectedBytes) {
        // Binary, not decimal: every setting this parses is a memory or buffer size, where binary
        // is what the underlying allocation actually does.
        assertThat(with(text).getDataSize("k")).hasValue(expectedBytes);
    }

    @Test
    void rejectsAnUnknownDataSizeUnit() {
        assertThatThrownBy(() -> with("4PB").getDataSize("k"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1024");
    }

    @Test
    void parsesNumbersWithUnderscoreSeparators() {
        assertThat(with("1_000_000").getLong("k")).hasValue(1_000_000L);
        assertThat(with("0.8").getDouble("k")).hasValue(0.8);
    }

    @Test
    void rejectsANonNumber() {
        assertThatThrownBy(() -> with("sixteen").getInt("k"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1021")
                .hasMessageContaining("sixteen");
    }

    @Test
    void rejectsAnIntegerThatDoesNotFit() {
        assertThatThrownBy(() -> with("99999999999").getInt("k"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1026");
    }

    enum WaitStrategy {
        BUSY_SPIN,
        SPIN_THEN_YIELD,
        BACKOFF_PARK,
        BLOCKING
    }

    @Test
    void parsesEnumsCaseAndSeparatorInsensitively() {
        assertThat(with("busy_spin").getEnum("k", WaitStrategy.class)).hasValue(WaitStrategy.BUSY_SPIN);
        assertThat(with("spin-then-yield").getEnum("k", WaitStrategy.class)).hasValue(WaitStrategy.SPIN_THEN_YIELD);
    }

    @Test
    void anUnknownEnumValueListsTheAllowedOnes() {
        assertThatThrownBy(() -> with("SPIN").getEnum("k", WaitStrategy.class))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-1025")
                .hasMessageContaining("BUSY_SPIN")
                .hasMessageContaining("BLOCKING");
    }

    @Test
    void parsesCommaSeparatedLists() {
        assertThat(with("a, b ,c").getList("k")).containsExactly("a", "b", "c");
        assertThat(with("").getList("k")).isEmpty();
        assertThat(with("a,,b,").getList("k")).containsExactly("a", "b");
        assertThat(Configuration.empty().getList("absent")).isEmpty();
    }

    @Test
    void secretValuesAreMaskedInParseErrors() {
        Configuration c = Configuration.builder().set("db.password", "hunter2").build();
        assertThatThrownBy(() -> c.getInt("db.password"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(Redaction.MASK)
                .hasMessageNotContaining("hunter2");
    }
}

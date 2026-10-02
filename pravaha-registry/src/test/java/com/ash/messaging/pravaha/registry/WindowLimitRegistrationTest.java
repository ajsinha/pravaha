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
package com.ash.messaging.pravaha.registry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.window.WindowLimits;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FINEHOP-1: a window whose rows each land in more windows, or whose windows each combine more
 * slices, than {@code pravaha.lane.max-windows-per-row} allows is refused at registration, {@code
 * PRV-3026}, naming its size, its slide, both counts and the bound -- before it can hold a lane.
 */
class WindowLimitRegistrationTest {

    private static final StreamSchema W = StreamSchema.builder("w")
            .field("k", Types.string())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), W);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private static String hop(String slide, String size) {
        return "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(HOP(TABLE w, DESCRIPTOR(ts), INTERVAL "
                + slide + ", INTERVAL " + size + ")) GROUP BY window_start, window_end";
    }

    private RegisteredQuery register(String name, String sql) {
        return registry.register(name, sql, List.of(0, 1), DANA);
    }

    @Test
    void aMillisecondHopOverADayIsRefusedNamingTheArithmeticAndTheBound() {
        assertThatThrownBy(() -> register("fine", hop("'0.001' SECOND", "'1' DAY")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3026")
                .hasMessageContaining("PT24H")
                .hasMessageContaining("PT0.001S")
                .hasMessageContaining("86400000 windows")
                .hasMessageContaining("100000")
                .hasMessageContaining(WindowLimits.SETTING);
        assertThat(registry.find("fine")).isEmpty();
        // An hour of milliseconds too: 3.6 million.
        assertThatThrownBy(() -> register("fine_hour", hop("'0.001' SECOND", "'1' HOUR")))
                .hasMessageContaining("PRV-3026");
    }

    @Test
    void ordinaryHopsAndTumblesRegister() {
        // A day of one-second hops is 86,400 windows per row: inside the default.
        assertThat(register("per_second_day", hop("'1' SECOND", "'1' DAY")).state())
                .isEqualTo(QueryState.RUNNING);
        assertThat(register(
                                "tumble",
                                "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(TUMBLE(TABLE w, "
                                        + "DESCRIPTOR(ts), INTERVAL '0.001' SECOND)) GROUP BY window_start, window_end")
                        .state())
                .isEqualTo(QueryState.RUNNING);
    }

    @Test
    void theBoundIsConfigurableAndCountsSlicesAsWellAsWindows() {
        registry.limitingWindowsPerRow(50_000);
        // HOP(7 s, 1 day): 12,343 windows per row, but slices of gcd(86400, 7) = 1 s -- 86,400 a window.
        assertThatThrownBy(() -> register("seven", hop("'7' SECOND", "'1' DAY")))
                .hasMessageContaining("PRV-3026")
                .hasMessageContaining("12343 windows of 86400 slices");
        registry.limitingWindowsPerRow(1_000);
        assertThatThrownBy(() -> register("per_second", hop("'1' SECOND", "'1' HOUR")))
                .hasMessageContaining("3600 windows");
        assertThat(register("per_minute", hop("'1' MINUTE", "'1' HOUR")).state())
                .isEqualTo(QueryState.RUNNING);
    }

    @Test
    void theArithmeticAndTheSetting() {
        WindowSpec seven = WindowSpec.hopping(86_400_000_000_000L, 7_000_000_000L);
        assertThat(WindowLimits.windowsPerRow(seven)).isEqualTo(12_343);
        assertThat(WindowLimits.slicesPerWindow(seven)).isEqualTo(86_400);
        assertThat(WindowLimits.windowsPerRow(WindowSpec.tumbling(5))).isEqualTo(1);
        assertThat(WindowLimits.parse(null)).isEqualTo(WindowLimits.DEFAULT_MAX_WINDOWS_PER_ROW);
        assertThat(WindowLimits.parse("250_000")).isEqualTo(250_000);
        assertThatThrownBy(() -> WindowLimits.parse("0")).hasMessageContaining(WindowLimits.SETTING);
        assertThatThrownBy(() -> WindowLimits.parse("lots")).hasMessageContaining(WindowLimits.SETTING);
    }
}

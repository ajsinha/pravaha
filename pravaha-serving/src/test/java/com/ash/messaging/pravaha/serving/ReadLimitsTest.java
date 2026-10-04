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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** READADMIT-1: the settings that give a node's gateways their read admission and deadline. */
class ReadLimitsTest {

    @Test
    void theDefaultsAdmitEveryReadWithNoDeadlineAsBefore() {
        ReadLimits limits = ReadLimits.from(Configuration.empty());

        assertThat(limits).isEqualTo(ReadLimits.NONE);
        assertThat(limits.admission()).isSameAs(ReadAdmission.UNLIMITED);
        assertThat(limits.deadline()).isZero();
        assertThat(limits.describe()).contains("every read admitted").contains("no read deadline");
    }

    @Test
    void theSettingsAreRead() {
        ReadLimits limits = ReadLimits.from(Configuration.builder()
                .set("pravaha.serving.read.max-concurrent", "4")
                .set("pravaha.serving.read.max-queued", "8")
                .set("pravaha.serving.read.tenant-share", "0.5")
                .set("pravaha.serving.read.queue-timeout", "500ms")
                .set("pravaha.serving.read.deadline", "30s")
                .build());

        assertThat(limits).isEqualTo(new ReadLimits(4, 8, 0.5, Duration.ofMillis(500), Duration.ofSeconds(30)));
        assertThat(limits.admission()).isNotSameAs(ReadAdmission.UNLIMITED);
        assertThat(limits.describe())
                .contains("at most 4 at once")
                .contains("2 per tenant")
                .contains("30000 ms");
    }

    @Test
    void aFullNodeRefusesWithTheDocumentedCode() {
        ReadAdmission admission = new ReadLimits(1, 0, 1.0, Duration.ZERO, Duration.ZERO).admission();
        try (ReadAdmission.Lease _ = admission.acquire(new com.ash.messaging.pravaha.security.Principal(
                "a", "other", java.util.Set.of(), java.util.Map.of()))) {
            assertThatThrownBy(() -> admission.acquire(com.ash.messaging.pravaha.security.Principal.ANONYMOUS))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4026");
        }
    }

    @ParameterizedTest
    @CsvSource({"max-concurrent, -1", "max-queued, -1", "tenant-share, 0", "tenant-share, 1.5"})
    void aValueOutOfRangeIsRefusedNamingTheSetting(String setting, String value) {
        Configuration configuration = Configuration.builder()
                .set("pravaha.serving.read." + setting, value)
                .build();

        assertThatThrownBy(() -> ReadLimits.from(configuration))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("pravaha.serving.read." + setting);
    }

    @Test
    void aNegativeDurationIsRefusedNamingTheSetting() {
        assertThatThrownBy(() -> new ReadLimits(1, 0, 1.0, Duration.ofSeconds(-1), Duration.ZERO))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("pravaha.serving.read.queue-timeout");
        assertThatThrownBy(() -> new ReadLimits(1, 0, 1.0, Duration.ZERO, Duration.ofSeconds(-5)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("pravaha.serving.read.deadline");
    }
}

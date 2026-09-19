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
package com.ash.messaging.pravaha.runtime.exec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one place "is spilling on, and where" is decided down to a single, already-resolved answer. */
class SpillSettingsTest {

    @Test
    void disabledNeedsNoDirectoryOrCeiling() {
        assertThat(SpillSettings.DISABLED.enabled()).isFalse();
        assertThat(new SpillSettings(false, "", 0)).isEqualTo(SpillSettings.DISABLED);
    }

    @Test
    void enabledWithNoDirectoryIsRefused() {
        assertThatThrownBy(() -> new SpillSettings(true, "", 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directory");
        assertThatThrownBy(() -> new SpillSettings(true, null, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directory");
    }

    @Test
    void enabledWithNoOverflowCeilingIsRefused() {
        assertThatThrownBy(() -> new SpillSettings(true, "/tmp/spill", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-overflow-slabs");
        assertThatThrownBy(() -> new SpillSettings(true, "/tmp/spill", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-overflow-slabs");
    }

    @Test
    void enabledWithBothIsAccepted() {
        SpillSettings settings = new SpillSettings(true, "/tmp/spill", 512);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.directory()).isEqualTo("/tmp/spill");
        assertThat(settings.maxOverflowSlabs()).isEqualTo(512);
        assertThat(settings.compactionThreshold()).isEqualTo(0.5);
    }

    @Test
    void aCompactionThresholdOutsideZeroToOneIsRefusedByName() {
        for (double threshold : new double[] {0, -0.1, 1.5, Double.NaN}) {
            assertThatThrownBy(() -> new SpillSettings(true, "/tmp/spill", 512, threshold))
                    .as("threshold %s", threshold)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pravaha.state.spill.compaction-threshold");
        }
        assertThat(new SpillSettings(true, "/tmp/spill", 512, 1.0).compactionThreshold())
                .isEqualTo(1.0);
    }
}

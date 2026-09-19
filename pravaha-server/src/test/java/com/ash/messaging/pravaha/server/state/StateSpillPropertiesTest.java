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
package com.ash.messaging.pravaha.server.state;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.runtime.exec.SpillSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resolution rule an operator's {@code enabled} flag gets, and the one place it could be
 * gotten backwards -- as it was, once, in this project's own connector TLS loader.
 */
class StateSpillPropertiesTest {

    @Test
    void anUnconfiguredNodeHasSpillingOff() {
        StateSpillProperties properties = new StateSpillProperties();
        assertThat(properties.resolvedEnabled()).isFalse();
        assertThat(properties.toSpillSettings()).isEqualTo(SpillSettings.DISABLED);
    }

    @Test
    void aDirectoryWithNoExplicitFlagInfersEnabled() {
        StateSpillProperties properties = new StateSpillProperties();
        properties.setDirectory("/var/lib/pravaha/spill");

        assertThat(properties.resolvedEnabled()).isTrue();
        assertThat(properties.toSpillSettings().enabled()).isTrue();
        assertThat(properties.toSpillSettings().directory()).isEqualTo("/var/lib/pravaha/spill");
    }

    @Test
    void anExplicitFalseWinsOverADirectoryBeingSet() {
        // The mistake named in this class's javadoc, the other way round: a directory left over
        // from an earlier configuration must not turn spilling back on once an operator has
        // written enabled: false over it.
        StateSpillProperties properties = new StateSpillProperties();
        properties.setDirectory("/var/lib/pravaha/spill");
        properties.setEnabled(false);

        assertThat(properties.resolvedEnabled()).isFalse();
        assertThat(properties.toSpillSettings()).isEqualTo(SpillSettings.DISABLED);
    }

    @Test
    void anExplicitTrueWinsEvenWithoutInferenceNeeded() {
        StateSpillProperties properties = new StateSpillProperties();
        properties.setDirectory("/var/lib/pravaha/spill");
        properties.setEnabled(true);

        assertThat(properties.resolvedEnabled()).isTrue();
        assertThat(properties.toSpillSettings().enabled()).isTrue();
    }

    @Test
    void anExplicitTrueWithNoDirectoryFailsLoudlyRatherThanSilentlyStayingOff() {
        StateSpillProperties properties = new StateSpillProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::toSpillSettings)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directory");
    }

    @Test
    void theCeilingDefaultsToSomethingGenerousButIsSettable() {
        StateSpillProperties properties = new StateSpillProperties();
        assertThat(properties.getMaxOverflowSlabs()).isEqualTo(512);

        properties.setMaxOverflowSlabs(4096);
        assertThat(properties.getMaxOverflowSlabs()).isEqualTo(4096);
    }

    @Test
    void theCompactionThresholdDefaultsToHalfAndReachesTheRuntime() {
        StateSpillProperties properties = new StateSpillProperties();
        properties.setDirectory("/var/lib/pravaha/spill");
        assertThat(properties.toSpillSettings().compactionThreshold()).isEqualTo(0.5);

        properties.setCompactionThreshold(0.8);
        assertThat(properties.toSpillSettings().compactionThreshold()).isEqualTo(0.8);

        properties.setCompactionThreshold(0);
        assertThatThrownBy(properties::toSpillSettings)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compaction-threshold");
    }
}

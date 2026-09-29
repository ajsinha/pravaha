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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PERF-1: the one check every measurement harness makes before it reports a number. */
class CoverageAgentTest {

    @Test
    void aJacocoJavaAgentIsDetectedAndNothingElseIs() {
        assertThat(CoverageAgent.detect(List.of(
                        "-Xss4m",
                        "-javaagent:/home/me/.m2/repository/org/jacoco/org.jacoco.agent/0.8.15/"
                                + "org.jacoco.agent-0.8.15-runtime.jar=destfile=/w/target/jacoco.exec")))
                .isTrue();
        assertThat(CoverageAgent.detect(List.of("-Xss4m", "-Djacoco.skip=true", "-javaagent:/opt/otel.jar")))
                .as("a property naming jacoco is not an agent, and another agent is not JaCoCo")
                .isFalse();
    }

    @Test
    void underTheAgentAHarnessIsRefusedByNameAndATimingIsMarked() {
        if (CoverageAgent.attached()) {
            assertThatThrownBy(() -> CoverageAgent.refuseToMeasure("SomeBenchmark"))
                    .hasMessageContaining("SomeBenchmark declines to measure")
                    .hasMessageContaining("-Djacoco.skip=true");
            assertThat(CoverageAgent.caveat()).contains("NOT A FIGURE");
        } else {
            CoverageAgent.refuseToMeasure("SomeBenchmark");
            assertThat(CoverageAgent.caveat()).isEmpty();
        }
    }
}

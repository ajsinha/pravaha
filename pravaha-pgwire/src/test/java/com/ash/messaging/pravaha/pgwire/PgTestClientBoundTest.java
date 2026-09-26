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
package com.ash.messaging.pravaha.pgwire;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PGW-1: the test client's read bound follows the machine's load rather than an idle machine's. */
class PgTestClientBoundTest {

    @Test
    void theReadBoundGrowsWithRunnableTasksPerProcessorAndNeverShrinks() {
        assertThat(PgTestClient.readBoundMillis(1.2, 24))
                .as("an idle machine keeps the idle bound")
                .isEqualTo(PgTestClient.IDLE_READ_BOUND_MILLIS);
        assertThat(PgTestClient.readBoundMillis(58, 24))
                .as("the load PGW-1 failed at: 58 runnable tasks on 24 processors")
                .isEqualTo(36_250);
        assertThat(PgTestClient.readBoundMillis(-1, 24))
                .as("a platform that reports no load average keeps the idle bound")
                .isEqualTo(PgTestClient.IDLE_READ_BOUND_MILLIS);
    }
}

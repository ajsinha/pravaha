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
package com.ash.messaging.pravaha.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client half of SX-18's wire change.
 *
 * <p>The server sends {@code -1} in the {@code ROWS IN} field when the calling principal is entitled
 * only to a row-filtered slice of the view, because a real count there is cardinality they are not
 * authorized for. {@code -1} is the right thing on the wire -- a decimal long that both shipped SDKs
 * already parse, and one no counter can ever equal -- and the wrong thing on a terminal, where a
 * column of counts makes it read as a count.
 */
class WithheldRowCountTest {

    @Test
    void aWithheldCountPrintsAsADashRatherThanAsMinusOne() {
        assertThat(ServerCommand.rowsInText(-1)).isEqualTo("-");
    }

    @Test
    void anOrdinaryCountIsUnchanged() {
        assertThat(ServerCommand.rowsInText(0)).isEqualTo("0");
        assertThat(ServerCommand.rowsInText(4)).isEqualTo("4");
    }
}

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
package com.ash.messaging.pravaha.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ash.messaging.pravaha.registry.RegistryErrors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CDCREPL-2: a registration over a single-consumer binding another query holds is a conflict, not a
 * malformed request -- the body is fine, and the fix is a second binding or a drop.
 */
class SourceHeldStatusTest {

    @Test
    void aHeldSingleConsumerBindingIsAConflict() {
        assertThat(ApiExceptionHandler.statusFor(RegistryErrors.SOURCE_HELD)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RegistryErrors.SOURCE_HELD.code()).isEqualTo("PRV-8028");
    }
}

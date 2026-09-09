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
package com.ash.messaging.pravaha.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorCodeTest {

    @Test
    void rendersTheDocumentedForm() {
        ErrorCode c = new ErrorCode(2041, "EMIT_MODE_MISMATCH");
        assertThat(c.code()).isEqualTo("PRV-2041");
        assertThat(c.helpUrl()).isEqualTo("https://docs.pravaha.io/errors/PRV-2041");
        assertThat(c.toString()).isEqualTo("PRV-2041 (EMIT_MODE_MISMATCH)");
    }

    @Test
    void numberDeterminesTheSubsystem() {
        assertThat(new ErrorCode(1001, "x").category()).isEqualTo(ErrorCode.Category.CONFIGURATION);
        assertThat(new ErrorCode(2041, "x").category()).isEqualTo(ErrorCode.Category.PLANNING);
        assertThat(new ErrorCode(3999, "x").category()).isEqualTo(ErrorCode.Category.RUNTIME);
        assertThat(new ErrorCode(4000, "x").category()).isEqualTo(ErrorCode.Category.STATE);
        assertThat(new ErrorCode(5500, "x").category()).isEqualTo(ErrorCode.Category.PLUGIN);
        assertThat(new ErrorCode(6001, "x").category()).isEqualTo(ErrorCode.Category.CLUSTER);
        assertThat(new ErrorCode(7001, "x").category()).isEqualTo(ErrorCode.Category.SECURITY);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.Category.class)
    void categoryRangesDoNotOverlap(ErrorCode.Category c) {
        for (ErrorCode.Category other : ErrorCode.Category.values()) {
            if (other == c) {
                continue;
            }
            for (int n = 1000; n <= 9999; n += 500) {
                assertThat(c.contains(n) && other.contains(n)).isFalse();
            }
        }
    }

    @Test
    void codeOutsideEveryRangeHasNoCategory() {
        assertThatThrownBy(() -> new ErrorCode(8500, "x").category()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedCodes() {
        assertThatThrownBy(() -> new ErrorCode(999, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorCode(10000, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorCode(2000, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorCode(2000, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

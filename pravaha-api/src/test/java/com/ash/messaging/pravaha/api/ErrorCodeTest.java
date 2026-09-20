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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorCodeTest {

    @org.junit.jupiter.api.AfterEach
    void clearTheConfiguredBase() {
        HelpUrls.configure(null);
    }

    @Test
    void rendersTheDocumentedForm() {
        ErrorCode c = new ErrorCode(2041, "EMIT_MODE_MISMATCH");
        assertThat(c.code()).isEqualTo("PRV-2041");
        assertThat(c.toString()).isEqualTo("PRV-2041 (EMIT_MODE_MISMATCH)");
    }

    /**
     * DOCX-21. The help URL is a deployment's setting, not a constant, and there is no default:
     * the constant this used to be named a host that has never resolved.
     */
    @Test
    void theHelpUrlIsEmptyUntilADeploymentPublishesOne() {
        ErrorCode c = new ErrorCode(2041, "EMIT_MODE_MISMATCH");
        assertThat(c.helpUrl()).isEmpty();
        HelpUrls.configure("http://localhost:8088/help/errors/");
        assertThat(c.helpUrl()).isEqualTo("http://localhost:8088/help/errors/PRV-2041");
    }

    @Test
    void numberDeterminesTheSubsystem() {
        assertThat(new ErrorCode(1001, "x").category()).isEqualTo(ErrorCode.Category.CONFIGURATION);
        assertThat(new ErrorCode(2041, "x").category()).isEqualTo(ErrorCode.Category.PLANNING);
        assertThat(new ErrorCode(3999, "x").category()).isEqualTo(ErrorCode.Category.RUNTIME);
        assertThat(new ErrorCode(4000, "x").category()).isEqualTo(ErrorCode.Category.STATE);
        assertThat(new ErrorCode(5500, "x").category()).isEqualTo(ErrorCode.Category.PLUGIN);
        assertThat(new ErrorCode(6001, "x").category()).isEqualTo(ErrorCode.Category.FLIGHT);
        assertThat(new ErrorCode(7001, "x").category()).isEqualTo(ErrorCode.Category.SECURITY);
        assertThat(new ErrorCode(8500, "x").category()).isEqualTo(ErrorCode.Category.REGISTRY);
        assertThat(new ErrorCode(9001, "x").category()).isEqualTo(ErrorCode.Category.CLUSTER);
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
    void everyCodeTheConstructorAcceptsHasACategory() {
        // This test used to assert the opposite for 8500, which is how the defect was encoded: the
        // enum claimed CLUSTER for the Flight range and covered neither 8xxx nor 9xxx, so
        // ErrorCode.category() threw IllegalStateException for every registry and cluster code --
        // from inside ApiExceptionHandler, while it was building an error response.
        for (int number = 1000; number <= 9999; number++) {
            int code = number;
            assertThatCode(() -> new ErrorCode(code, "x").category())
                    .as("PRV-%d is constructible and must belong to a subsystem", code)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsMalformedCodes() {
        assertThatThrownBy(() -> new ErrorCode(999, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorCode(10000, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorCode(2000, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorCode(2000, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

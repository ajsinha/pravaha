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

import static org.assertj.core.api.Assertions.assertThat;

class PravahaExceptionTest {

    private static final ErrorCode CODE = new ErrorCode(2041, "EMIT_MODE_MISMATCH");

    @Test
    void prefixesTheMessageWithTheStableCode() {
        // Operators search on the code, so it has to be in the message itself, not only the field.
        PravahaException e = new PravahaException(CODE, "sink is append-only");
        assertThat(e).hasMessage("PRV-2041  sink is append-only");
        assertThat(e.errorCode()).isEqualTo(CODE);
        // DOCX-21: the help URL is the deployment's, and there is none here. The code is in the
        // message either way, which is the claim this case makes.
        assertThat(e.helpUrl()).isEmpty();
        HelpUrls.configure("https://help.example.test/errors/");
        try {
            assertThat(e.helpUrl()).endsWith("PRV-2041");
        } finally {
            HelpUrls.configure(null);
        }
    }

    @Test
    void preservesTheCause() {
        Throwable cause = new IllegalStateException("underlying");
        assertThat(new PravahaException(CODE, "wrapped", cause)).hasCause(cause);
    }

    @Test
    void configurationExceptionIsAPravahaException() {
        ConfigurationException e = new ConfigurationException(CODE, "bad config");
        assertThat(e).isInstanceOf(PravahaException.class);
        assertThat(e.errorCode()).isEqualTo(CODE);
        assertThat(new ConfigurationException(CODE, "bad", new RuntimeException()))
                .hasCauseInstanceOf(RuntimeException.class);
    }
}

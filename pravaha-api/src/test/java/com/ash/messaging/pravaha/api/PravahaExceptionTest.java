/*
 * Copyright the Pravaha authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
        assertThat(e.helpUrl()).endsWith("PRV-2041");
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

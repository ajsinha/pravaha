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

/** ANSICONSOLE-1: when the CLI colours its output. */
class AnsiTest {

    @Test
    void colourNeedsATerminalThatIsOne() {
        assertThat(Ansi.detect("xterm-256color", true)).isTrue();
        assertThat(Ansi.detect("xterm-256color", false))
                .as("redirected: a console exists on JDK 22+ but is not a terminal")
                .isFalse();
        assertThat(Ansi.detect("dumb", true)).isFalse();
        assertThat(Ansi.detect(null, true)).isFalse();
    }

    @Test
    void underATestRunnerOutputIsNotATerminal() {
        // Surefire captures stdout; before the fix System.console() was non-null here on JDK 22 and later.
        assertThat(Ansi.enabled()).isFalse();
    }

    @Test
    void noConsoleIsNoTerminalOnEveryJdk() {
        // JDK 21 has no Console.isTerminal(); there, and on 22+, a JVM without a console has no terminal.
        assertThat(Ansi.isTerminal(null)).isFalse();
    }
}

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
package com.ash.messaging.pravaha.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIX-3, the half a node sees: with no formatter on the classpath -- as in this module, and in
 * every node -- the fixture says it needs {@code spotless:apply}, above its {@code package} line,
 * where the licence-header step replaces it. {@code DebugFixtureExportTest} covers the half with
 * the formatter present.
 */
class FixtureFormatterTest {

    @Test
    void withoutAFormatterTheFixtureSaysItNeedsFormattingWhereSpotlessWillRemoveIt() {
        assertThatThrownBy(() -> Class.forName("com.palantir.javaformat.java.Formatter"))
                .as("the premise: no formatter here")
                .isInstanceOf(ClassNotFoundException.class);
        String source = "/*\n * header\n */\npackage a.b;\n\nclass C {}\n";
        String written = FixtureFormatter.format(source);
        assertThat(FixtureFormatter.isUnformatted(written)).isTrue();
        assertThat(written)
                .isEqualTo("/*\n * header\n */\n" + FixtureFormatter.UNFORMATTED_NOTE + "package a.b;\n\nclass C {}\n");
    }
}

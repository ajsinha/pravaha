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
package com.ash.messaging.pravaha.codegen;

/**
 * Accumulates generated Java source, keeping it indented and readable.
 *
 * <p>Readability is not vanity here. Generated source is retained under a debug flag and is
 * downloadable from the console (design section 12.4), and it is the first thing anyone looks at when a
 * plan produces the wrong answer. Source that a human cannot follow makes the highest-risk component
 * in the design (R2) considerably harder to debug, so the few lines this costs are worth it.
 */
final class SourceBuilder {

    private final StringBuilder text = new StringBuilder(2048);
    private int indent;

    SourceBuilder line(String content) {
        text.append("    ".repeat(indent)).append(content).append('\n');
        return this;
    }

    SourceBuilder blank() {
        text.append('\n');
        return this;
    }

    /** A comment explaining what a generated fragment came from. */
    SourceBuilder comment(String content) {
        return line("// " + content);
    }

    SourceBuilder open(String content) {
        line(content);
        indent++;
        return this;
    }

    SourceBuilder close() {
        indent--;
        return line("}");
    }

    /** Closes an {@code if} block and opens its {@code else}. */
    SourceBuilder orElse() {
        indent--;
        line("} else {");
        indent++;
        return this;
    }

    /** Starts at {@code level}, for a fragment built apart and then appended inside a class. */
    SourceBuilder at(int level) {
        indent = level;
        return this;
    }

    /** Appends another builder's text as it stands. */
    SourceBuilder append(SourceBuilder fragment) {
        text.append(fragment.text);
        return this;
    }

    SourceBuilder closeWith(String suffix) {
        indent--;
        return line("}" + suffix);
    }

    int lineCount() {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    @Override
    public String toString() {
        return text.toString();
    }
}

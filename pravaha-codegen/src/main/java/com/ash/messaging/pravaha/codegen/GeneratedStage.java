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
 * A compiled, fused operator chain, with the source it came from.
 *
 * <p>The source is kept deliberately. When a generated plan produces a wrong answer, the source is
 * the first thing anyone needs, and regenerating it later is not the same artefact -- the plan may
 * have been replanned since. Holding a few kilobytes of text per query is a cheap price for being
 * able to answer "what did it actually run?" (design section 12.4).
 *
 * @param className the generated class's name, which appears in stack traces
 * @param source the Java source that was compiled
 * @param processor the compiled instance, a {@link FusedStage}
 * @param compileMillis how long Janino took, recorded because registration latency is a stated
 *     product claim (W2) and this is the part of it that can quietly grow
 */
public record GeneratedStage(String className, String source, Object processor, long compileMillis) {

    public int sourceLines() {
        return (int) source.chars().filter(c -> c == '\n').count();
    }
}

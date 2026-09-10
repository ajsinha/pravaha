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

import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * Generation attempted, and what to do when it did not work.
 *
 * <p>The design's guarantee is that <strong>correctness never depends on code generation
 * succeeding</strong> (section 12.4), which means every caller needs an answer rather than an
 * exception: a plan that cannot be generated runs on the interpreted path and the query is fine,
 * only slower. Making that an exception pushes the decision to every call site, and one of them
 * eventually gets it wrong by letting the failure escape to a user as an error for a query that
 * would have run.
 *
 * <p>So this never throws. It returns a stage or a reason, and the reason is written for whoever
 * asks why their query is slow rather than for a log parser.
 *
 * @param stage the compiled stage, if generation and compilation both succeeded
 * @param reason why there is no stage, or how the one there was produced
 */
public record StageCompilation(Optional<GeneratedStage> stage, String reason) {

    /** Whether the generated path is available for this plan. */
    public boolean isGenerated() {
        return stage.isPresent();
    }

    /**
     * Generates and compiles a plan, falling back rather than failing.
     *
     * <p>Three things can go wrong and all three end the same way -- the interpreter runs it -- but
     * they are distinguished in the reason, because they need different responses. An operator the
     * generator does not cover is a gap in the generator. A stage too large is a query shape worth
     * knowing about. A compilation failure is a bug in the generator and should be reported.
     */
    public static StageCompilation attempt(PhysicalOperator plan, String className, StageCompiler compiler) {
        FilterProjectGenerator.Fused fused;
        try {
            fused = new FilterProjectGenerator().generate(plan, className);
        } catch (PravahaException e) {
            return new StageCompilation(
                    Optional.empty(),
                    "not generated: " + e.getMessage() + " The interpreted path runs this correctly and more slowly.");
        }

        try {
            GeneratedStage compiled = compiler.compileFused(className, fused.source());
            return new StageCompilation(
                    Optional.of(compiled),
                    "generated: " + compiled.sourceLines() + " lines, compiled in " + compiled.compileMillis() + " ms");
        } catch (PravahaException e) {
            if (e.errorCode().equals(CodegenErrors.STAGE_TOO_LARGE)) {
                return new StageCompilation(
                        Optional.empty(),
                        "not generated: the stage is too large even after splitting the projection into "
                                + FilterProjectGenerator.COLUMNS_PER_METHOD
                                + "-column methods. This is a very wide or very deeply filtered query; the "
                                + "interpreted path runs it correctly and more slowly.");
            }
            // A compilation failure is a defect in the generator rather than a property of the
            // query, and it must not take the query down with it -- but it must be visible, so the
            // reason says where to look and the source travels with the exception.
            return new StageCompilation(
                    Optional.empty(),
                    "not generated: the generated source did not compile, which is a bug in the code "
                            + "generator rather than in this query. Please report it. " + e.getMessage());
        }
    }
}

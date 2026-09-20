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
package com.ash.messaging.pravaha.it.qa.perf;

import java.util.Arrays;

/**
 * One gate criterion, measured: what was measured, the number, the target, and whether it was
 * reached.
 *
 * <p>The shape exists so that a harness cannot print a number without printing the target beside
 * it, and cannot print a target without printing a verdict. A gate whose output is a bare number
 * invites the reader to supply the verdict themselves, and the verdict people supply is the one
 * they wanted.
 *
 * <p><strong>The verdict is taken against the best sample, deliberately.</strong> Best-of-N is the
 * most generous reading a set of repeats admits: it discards every pass the machine's other work
 * spoiled. Judging against it means a NOT REACHED cannot be answered with "the machine was busy" --
 * the busiest passes have already been thrown away and the number still did not get there. The
 * median and the spread are printed as well, because the best sample alone hides how repeatable
 * the figure is, and on a shared machine that is most of what a reader needs to know.
 */
record GateReading(String what, String unit, double target, String targetSource, double[] samples) {

    /** Spread wider than this fraction of the median means the figure should not be stated alone. */
    private static final double TOO_NOISY = 0.35;

    GateReading {
        if (samples.length == 0) {
            throw new IllegalArgumentException("a reading with no samples measures nothing: " + what);
        }
        samples = samples.clone();
    }

    double best() {
        return Arrays.stream(samples).max().orElseThrow();
    }

    double worst() {
        return Arrays.stream(samples).min().orElseThrow();
    }

    double median() {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    /** Reached when the most generous sample meets the target. Nothing softer counts. */
    boolean reached() {
        return best() >= target;
    }

    /** The spread as a fraction of the median: how much of this figure is the machine. */
    double spread() {
        double median = median();
        return median == 0 ? Double.POSITIVE_INFINITY : (best() - worst()) / median;
    }

    boolean tooNoisyToState() {
        return spread() > TOO_NOISY;
    }

    /**
     * The whole reading, target and verdict included, with the machine it was taken on.
     *
     * <p>The wording of a miss is fixed here rather than left to each harness: <em>NOT REACHED</em>
     * followed by the number that was measured. A gate this machine cannot reach is recorded as not
     * reached, with the figure, and is never reworded into a target the figure clears.
     */
    String report(MachineState state) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%n  %s%n", what));
        out.append(String.format("    measured: %,.0f %s  (best of %d)%n", best(), unit, samples.length));
        out.append(String.format(
                "    samples : %s%n",
                Arrays.stream(samples)
                        .mapToObj(sample -> String.format("%,.0f", sample))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("")));
        out.append(String.format(
                "    median  : %,.0f %s, worst %,.0f, spread %.0f %% of median%s%n",
                median(),
                unit,
                worst(),
                spread() * 100,
                tooNoisyToState() ? "  <-- TOO NOISY TO STATE AS A FIGURE; the spread is the result" : ""));
        out.append(String.format("    target  : %,.0f %s  (%s)%n", target, unit, targetSource));
        out.append(String.format(
                "    verdict : %s%n",
                reached()
                        ? String.format("REACHED -- %,.0f >= %,.0f %s", best(), target, unit)
                        : String.format(
                                "NOT REACHED -- measured %,.0f %s against a target of %,.0f, which is %.2fx the target",
                                best(), unit, target, best() / target)));
        out.append(state.describe());
        out.append(String.format("    NOTE    : taken on a developer machine under whatever else was running. This is"
                + " a floor for this machine on this day, not a benchmark-quality figure and"
                + " not the engine's capability.%n"));
        return out.toString();
    }
}

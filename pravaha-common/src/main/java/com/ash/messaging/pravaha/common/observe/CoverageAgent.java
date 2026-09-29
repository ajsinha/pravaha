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
package com.ash.messaging.pravaha.common.observe;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Whether a coverage agent is attached to this JVM, which every measurement harness asks before it
 * reports a number (PERF-1).
 *
 * <p>The root POM attaches JaCoCo to every test JVM by default. Its probes are a shared {@code
 * boolean[]} per class, written on every executed branch by every thread, so lanes that share
 * nothing else contend for the same cache lines: under it, eight lanes measured 1 % of linear where
 * the same code measured 49 % without it. A figure taken under the agent is a figure about the
 * agent. So a harness that measures time declines under it -- a JUnit harness skips with {@link
 * #DECLINED}, a JMH benchmark refuses with {@link #refuseToMeasure} -- and one that asserts a count
 * (threads, descriptors, bytes) and prints a time alongside marks the time with {@link #caveat}.
 *
 * <p>To measure: {@code ./mvnw ... -Djacoco.skip=true}. Nothing in a running node calls this.
 */
public final class CoverageAgent {

    /** The reason a harness gives for declining: what is attached, why it matters, what to run. */
    public static final String DECLINED = "a JaCoCo agent is attached to this JVM, and its per-class probe arrays "
            + "are written by every thread on every branch: a timing taken here would be of the agent "
            + "(PERF-1). Run with -Djacoco.skip=true to measure.";

    private static final boolean ATTACHED =
            detect(ManagementFactory.getRuntimeMXBean().getInputArguments());

    private CoverageAgent() {}

    /** Whether a JaCoCo agent is on this JVM's command line (or in JAVA_TOOL_OPTIONS). */
    public static boolean attached() {
        return ATTACHED;
    }

    /** Whether {@code jvmArguments} attach a JaCoCo agent. */
    static boolean detect(List<String> jvmArguments) {
        return jvmArguments.stream()
                .anyMatch(argument -> argument.startsWith("-javaagent:")
                        && argument.toLowerCase(java.util.Locale.ROOT).contains("jacoco"));
    }

    /**
     * Throws, naming the agent, when one is attached: for a harness with no test framework to skip
     * it, such as a JMH benchmark's setup.
     */
    public static void refuseToMeasure(String harness) {
        if (ATTACHED) {
            throw new IllegalStateException(harness + " declines to measure: " + DECLINED);
        }
    }

    /**
     * A suffix for a printed timing: empty without the agent, and saying the figure is the agent's
     * with it.
     */
    public static String caveat() {
        return ATTACHED ? "  [NOT A FIGURE: JaCoCo attached; -Djacoco.skip=true to measure]" : "";
    }
}

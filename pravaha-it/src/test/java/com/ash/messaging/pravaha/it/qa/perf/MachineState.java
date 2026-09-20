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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The machine a number was taken on, recorded beside the number.
 *
 * <p>Every performance figure in this package is taken on the owner's development machine, because
 * there is no reference hardware and the owner's instruction on 2026-09-19 was to measure on the
 * machine that exists. A figure taken that way is only usable if the reader can see what it was
 * taken on and what else was happening at the time, so every harness here prints one of these
 * immediately beside its result rather than in a separate log nobody reads.
 *
 * <p>The load average is the important field. This machine routinely runs other build agents and
 * containers; a throughput figure taken at load 30 on 24 logical processors is a figure about the
 * queue, not about the engine. {@link #loaded()} says when that is the case, and the harnesses say
 * so in their output rather than leaving a reader to work it out.
 */
record MachineState(
        Instant at,
        String cpu,
        int logicalProcessors,
        double loadAverage,
        long maxHeapBytes,
        long usedHeapBytes,
        String jdk,
        String vm,
        String collectors) {

    /** Above this much load per logical processor, a throughput figure is a figure about the load. */
    private static final double BUSY = 0.5;

    static MachineState now() {
        Runtime runtime = Runtime.getRuntime();
        return new MachineState(
                Instant.now(),
                cpuModel(),
                runtime.availableProcessors(),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage(),
                runtime.maxMemory(),
                runtime.totalMemory() - runtime.freeMemory(),
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"),
                collectorNames());
    }

    /**
     * Whether the machine was busy enough that the number beside this state is about the machine.
     *
     * <p>Half a runnable task per logical processor is the threshold {@code
     * OperatorMetricsOverheadIT} already prints its results against, and it is kept here so the two
     * read the same way.
     */
    boolean loaded() {
        return loadAverage > BUSY * logicalProcessors;
    }

    String describe() {
        return String.format(
                "    machine : %s, %d logical processors%n"
                        + "    load    : %.2f (%.2f per processor)%s%n"
                        + "    heap    : %,d MiB used of %,d MiB max%n"
                        + "    jdk     : %s%n"
                        + "    vm      : %s, collectors %s%n"
                        + "    at      : %s%n",
                cpu,
                logicalProcessors,
                loadAverage,
                loadAverage / logicalProcessors,
                loaded() ? "  <-- LOADED: this number is about the machine as much as the engine" : "",
                usedHeapBytes / (1024 * 1024),
                maxHeapBytes / (1024 * 1024),
                jdk,
                vm,
                collectors,
                at);
    }

    private static String cpuModel() {
        try {
            Path info = Path.of("/proc/cpuinfo");
            if (Files.isReadable(info)) {
                for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // A missing model name is not a reason to lose a measurement; say so and carry on.
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
        return "unknown";
    }

    private static String collectorNames() {
        List<String> names = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName)
                .collect(Collectors.toList());
        return names.isEmpty() ? "unknown" : String.join(" + ", names);
    }
}

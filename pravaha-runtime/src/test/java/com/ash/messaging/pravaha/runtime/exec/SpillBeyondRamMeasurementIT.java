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
package com.ash.messaging.pravaha.runtime.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-044's second measurement: the spill tier with state <em>larger than the memory the process may
 * use</em>, page cache included -- the case the first one ({@link SpillTierMeasurementIT}) could not
 * reach on a machine with more free RAM than its disk budget.
 *
 * <p><strong>How the memory is capped, and why it is honest.</strong> Each run is a separate JVM
 * started with {@code systemd-run --user --scope -p MemoryMax=<cap> -p MemorySwapMax=0}: a cgroup v2
 * whose {@code memory.max} the kernel enforces on everything charged to it -- the heap, the off-heap
 * RAM tier, and the page cache of the files it reads and writes, mapped pages included. With swap
 * forbidden, anonymous memory cannot leave, so when mapped state exceeds what is left the kernel must
 * evict file pages (writing dirty ones back first) and fault them in again from the device. Nothing in
 * the process is changed to simulate this: no {@code madvise}, no remapping, no dropped caches. The
 * run reads its cgroup's {@code memory.max}, {@code memory.peak} and {@code memory.events}, and its own
 * major faults and block-device bytes, and reports them beside the timings, so a run whose state
 * stayed resident would show it.
 *
 * <p><strong>Not part of the default build</strong> ({@code *IT}), and skipped, with its reason, where
 * {@code systemd-run --user} cannot put a memory limit on a scope -- no systemd user instance, or no
 * memory controller delegated to it. Run it on purpose:
 *
 * <pre>
 * ./mvnw -o -pl pravaha-runtime -am test -Dtest=SpillBeyondRamMeasurementIT \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dpravaha.spill.beyond.dir=/a/disk/not/tmpfs
 * </pre>
 *
 * <p>Knobs, all system properties under {@code pravaha.spill.beyond.}: {@code capMiB} (default 512),
 * {@code multiples} of the cap to size state at (default {@code 1,4,16}), {@code kinds} (default
 * {@code join,aggregate}), {@code ceilingMiB} (the RAM tier, default 64 -- a join's real one),
 * {@code heapMiB} (default 160), {@code randomOps} (default 500000) and {@code phaseSeconds} (default
 * 300) bounding each random phase, {@code uncapped} (default {@code true}: run every size once more
 * without the cap, where the page cache holds it, for the comparison), {@code timeoutMinutes} per run
 * (default 120), and {@code dir}, which must be on the disk being measured -- not {@code /tmp} where
 * that is a tmpfs.
 */
class SpillBeyondRamMeasurementIT {

    private static final int MIB = 1 << 20;

    private static final String P = "pravaha.spill.beyond.";
    private static final int CAP_MIB = Integer.getInteger(P + "capMiB", 512);
    private static final String MULTIPLES = System.getProperty(P + "multiples", "1,4,16");
    private static final String KINDS = System.getProperty(P + "kinds", "join,aggregate");
    private static final int CEILING_MIB = Integer.getInteger(P + "ceilingMiB", 64);
    private static final int HEAP_MIB = Integer.getInteger(P + "heapMiB", 160);
    private static final long RANDOM_OPS = Long.getLong(P + "randomOps", 500_000);
    private static final long PHASE_SECONDS = Long.getLong(P + "phaseSeconds", 300);
    private static final boolean UNCAPPED = Boolean.parseBoolean(System.getProperty(P + "uncapped", "true"));
    private static final long TIMEOUT_MINUTES = Long.getLong(P + "timeoutMinutes", 120);
    private static final Path DIR = Path.of(System.getProperty(
            P + "dir", Path.of("target", "spill-beyond-ram").toAbsolutePath().toString()));

    @Test
    void joinAndWindowedAggregateWithStateLargerThanTheMemoryCap() throws Exception {
        String unavailable = memoryCapUnavailable();
        assumeTrue(unavailable == null, () -> "cannot cap a process's memory here: " + unavailable);

        List<String> report = new ArrayList<>();
        report.add(machine());
        report.add(String.format(
                "cap %d MiB (MemoryMax, MemorySwapMax=0), heap %d MiB, RAM tier %d MiB per store, spill directory %s",
                CAP_MIB, HEAP_MIB, CEILING_MIB, DIR));
        for (String kind : KINDS.split(",")) {
            for (String multiple : MULTIPLES.split(",")) {
                long stateBytes = (long) (Double.parseDouble(multiple.trim()) * CAP_MIB * MIB);
                Map<String, String> capped = run(kind.trim(), stateBytes, true);
                report.add(kind + " " + multiple.trim() + "x capped: " + capped);
                if (UNCAPPED) {
                    Map<String, String> free = run(kind.trim(), stateBytes, false);
                    report.add(kind + " " + multiple.trim() + "x uncapped: " + free);
                }
                Files.writeString(DIR.resolve("report.txt"), String.join("\n", report) + "\n", StandardCharsets.UTF_8);
            }
        }
        System.out.println("ADR-044 beyond-RAM measurement:\n" + String.join("\n", report));
    }

    /** One run in a JVM of its own, capped or not; its RESULT line as a map. */
    private static Map<String, String> run(String kind, long stateBytes, boolean capped) throws Exception {
        Path spill = DIR.resolve(kind + "-" + (capped ? "capped" : "uncapped"));
        deleteTree(spill);
        List<String> command = new ArrayList<>();
        if (capped) {
            command.addAll(List.of(
                    "systemd-run",
                    "--user",
                    "--scope",
                    "--quiet",
                    "-p",
                    "MemoryMax=" + CAP_MIB + "M",
                    "-p",
                    "MemorySwapMax=0"));
        }
        command.addAll(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m",
                "-Xmx" + HEAP_MIB + "m",
                "-XX:MaxDirectMemorySize=4g",
                "-cp",
                System.getProperty("java.class.path"),
                SpillBeyondRamWorkload.class.getName(),
                kind,
                Long.toString(stateBytes),
                spill.toString(),
                Integer.toString(CEILING_MIB),
                Long.toString(RANDOM_OPS),
                Long.toString(PHASE_SECONDS)));
        Files.createDirectories(DIR);
        Path log = DIR.resolve(kind + "-" + stateBytes / MIB + "MiB-" + (capped ? "capped" : "uncapped") + ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        long started = System.nanoTime();
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor();
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("wall_s", String.format("%.0f", (System.nanoTime() - started) / 1e9));
        if (!finished) {
            result.put("outcome", "did not finish in " + TIMEOUT_MINUTES + " min");
        } else if (process.exitValue() != 0) {
            result.put("outcome", "exit " + process.exitValue() + " (see " + log.getFileName() + ")");
        }
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.startsWith("PROGRESS ")) {
                result.put("last_progress", "'" + line.substring(9) + "'");
            }
            if (line.startsWith("RESULT ")) {
                result.remove("last_progress");
                for (String pair : line.substring(7).split(" ")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        result.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
            }
        }
        deleteTree(spill);
        return result;
    }

    /**
     * Why a memory cap cannot be imposed here, or {@code null} if it can: a scope is started with
     * a 64 MiB {@code MemoryMax} and must report that limit from inside.
     */
    private static String memoryCapUnavailable() {
        if (!System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("linux")) {
            return "not Linux";
        }
        try {
            Process probe = new ProcessBuilder(
                            "systemd-run",
                            "--user",
                            "--scope",
                            "--quiet",
                            "-p",
                            "MemoryMax=64M",
                            "-p",
                            "MemorySwapMax=0",
                            "sh",
                            "-c",
                            "cat /sys/fs/cgroup$(sed -n 's/^0:://p' /proc/self/cgroup)/memory.max")
                    .redirectErrorStream(true)
                    .start();
            String text = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!probe.waitFor(30, TimeUnit.SECONDS) || probe.exitValue() != 0) {
                return "systemd-run --user --scope failed: " + text;
            }
            return text.equals(Long.toString(64L * MIB))
                    ? null
                    : "a scope's memory.max reads '" + text + "', not 64 MiB: the memory controller is not "
                            + "delegated to the systemd user instance";
        } catch (IOException e) {
            return "systemd-run is not available: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    /** CPU, RAM and the device under the spill directory, as the numbers need them. */
    private static String machine() throws IOException {
        String cpu = "?";
        for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
            if (line.startsWith("model name")) {
                cpu = line.substring(line.indexOf(':') + 1).trim();
                break;
            }
        }
        String memTotal = "?";
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
            if (line.startsWith("MemTotal:")) {
                memTotal = Long.parseLong(line.replaceAll("\\D", "")) / 1024 / 1024 + " GiB";
            }
        }
        Files.createDirectories(DIR);
        String device = "?";
        try {
            Process df = new ProcessBuilder("df", "--output=source", DIR.toString()).start();
            String[] lines = new String(df.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .trim()
                    .split("\n");
            df.waitFor();
            String source = lines[lines.length - 1].trim().replace("/dev/", "");
            String disk = source.replaceAll("p?\\d+$", "");
            Path model = Path.of("/sys/block", disk, "device", "model");
            device = source
                    + (Files.exists(model) ? " (" + Files.readString(model).trim() + ")" : "");
        } catch (IOException | InterruptedException | RuntimeException e) {
            // Best effort: the report says "?" rather than failing the measurement.
        }
        assertThat(cpu).isNotEmpty();
        return String.format(
                "%s, %d logical CPUs, %s RAM, spill device %s, kernel %s, JDK %s",
                cpu,
                Runtime.getRuntime().availableProcessors(),
                memTotal,
                device,
                System.getProperty("os.version"),
                Runtime.version());
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

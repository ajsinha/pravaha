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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

/**
 * One run of {@link SpillBeyondRamMeasurementIT}: a join or a windowed aggregate driven to a target
 * state size with the overflow tier on, in a process of its own so that a cgroup can cap its memory --
 * heap, off-heap RAM tier and page cache together.
 *
 * <p>{@code main(kind, stateBytes, spillDir, ceilingMiB, randomOps, phaseSeconds)} prints one {@code
 * RESULT key=value ...} line. Each phase is single-threaded, the way a lane runs it. Latency is per
 * operation, sampled one in sixteen for the bulk insert and every operation for the random phases,
 * into a log-linear histogram (about 9 % resolution). A random phase stops at {@code randomOps}
 * operations or {@code phaseSeconds}, whichever is first, and says how many it did. The process's
 * own major faults and block-device reads and writes ({@code /proc/self/stat}, {@code /proc/self/io})
 * are recorded per phase -- they are what shows whether state came from the device.
 */
public final class SpillBeyondRamWorkload {

    private static final long SECOND = 1_000_000_000L;
    private static final int MIB = 1 << 20;

    private final Map<String, String> out = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String kind = args[0];
        long stateBytes = Long.parseLong(args[1]);
        Path dir = Path.of(args[2]);
        int ceilingMiB = Integer.parseInt(args[3]);
        long randomOps = Long.parseLong(args[4]);
        long phaseNanos = Long.parseLong(args[5]) * SECOND;
        SpillBeyondRamWorkload workload = new SpillBeyondRamWorkload();
        Files.createDirectories(dir);
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir)) {
            if (kind.equals("join")) {
                workload.join(stateBytes, ceilingMiB, overflow, dir, randomOps, phaseNanos);
            } else {
                workload.aggregate(stateBytes, ceilingMiB, overflow, dir, randomOps, phaseNanos);
            }
        }
        workload.cgroup();
        StringBuilder line = new StringBuilder("RESULT");
        workload.out.forEach((k, v) -> line.append(' ').append(k).append('=').append(v));
        System.out.println(line);
    }

    // ------------------------------------------------------------------------------------ the join

    private static StreamSchema left() {
        return StreamSchema.builder("left")
                .field("id", Types.int64())
                .field("key", Types.string())
                .build();
    }

    private static StreamSchema right() {
        return StreamSchema.builder("right")
                .field("key", Types.string())
                .field("value", Types.int64())
                .build();
    }

    private static JoinOperator joinPlan() {
        StreamSchema merged = StreamSchema.builder("joined")
                .field("l_id", Types.int64())
                .field("l_key", Types.string())
                .field("r_key", Types.string().withNullable(true))
                .field("r_value", Types.int64().withNullable(true))
                .build();
        return new JoinOperator(
                ScanOperator.of("left", left()),
                ScanOperator.of("right", right()),
                List.of(1),
                List.of(0),
                merged,
                Long.MAX_VALUE,
                Long.MAX_VALUE / 4);
    }

    private static final class Feed {
        final RowArena arena = new RowArena(MemoryAccess.best(), 4 << 20, 8);
        final RowLayout leftLayout = RowLayout.of(left());
        final RowLayout rightLayout = RowLayout.of(right());
        final BinaryRowWriter leftWriter = new BinaryRowWriter(leftLayout);
        final BinaryRowWriter rightWriter = new BinaryRowWriter(rightLayout);
        final BinaryRowView leftView = new BinaryRowView(leftLayout);
        final BinaryRowView rightView = new BinaryRowView(rightLayout);
        int sinceReset;

        void leftRow(SymmetricHashJoin join, long id, long weight) {
            long handle = arena.allocate(leftLayout.rowSize(64));
            leftWriter.begin(arena.regionOf(handle), arena.offsetOf(handle));
            leftWriter.setLong(0, id).setString(1, "key-" + id);
            leftWriter.weight(weight).eventTimestampNanos(0).sequence(id).commit();
            arena.trimTo(handle, leftWriter.sizeSoFar());
            join.leftInput().process(leftView.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            maybeReset();
        }

        void rightRow(SymmetricHashJoin join, long id, long weight) {
            long handle = arena.allocate(rightLayout.rowSize(64));
            rightWriter.begin(arena.regionOf(handle), arena.offsetOf(handle));
            rightWriter.setString(0, "key-" + id).setLong(1, id);
            rightWriter.weight(weight).eventTimestampNanos(0).sequence(id).commit();
            arena.trimTo(handle, rightWriter.sizeSoFar());
            join.rightInput().process(rightView.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            maybeReset();
        }

        private void maybeReset() {
            if (++sinceReset == 512) {
                arena.reset();
                sinceReset = 0;
            }
        }
    }

    /** Bytes of state one left row costs -- rows and key index together -- measured all in RAM. */
    private static double joinBytesPerRow() {
        long rows = 100_000;
        Feed feed = new Feed();
        try (SymmetricHashJoin join = new SymmetricHashJoin(joinPlan(), feed.arena, row -> {}, 4096)) {
            for (long id = 0; id < rows; id++) {
                feed.leftRow(join, id, 1);
            }
            return (double) (join.stateBytes() + join.indexRamBytes()) / rows;
        } finally {
            feed.arena.close();
        }
    }

    private void join(
            long stateBytes, int ceilingMiB, MappedFileMemoryAccess overflow, Path dir, long randomOps, long phaseNanos)
            throws IOException {
        double perRow = joinBytesPerRow();
        long rows = (long) (stateBytes / perRow);
        out.put("bytes_per_row", String.format("%.0f", perRow));
        out.put("rows", Long.toString(rows));
        long[] pairs = {0};
        Feed feed = new Feed();
        try (SymmetricHashJoin join = new SymmetricHashJoin(
                joinPlan(), feed.arena, row -> pairs[0]++, ceilingMiB, overflow, Integer.MAX_VALUE / 2)) {
            Phase insert = new Phase("insert");
            for (long id = 0; id < rows; id++) {
                if ((id & 15) == 0) {
                    long t = System.nanoTime();
                    feed.leftRow(join, id, 1);
                    insert.latency.record(System.nanoTime() - t);
                    insert.progress(id);
                } else {
                    feed.leftRow(join, id, 1);
                }
            }
            insert.end(rows);

            // A probe is a right row in and out again at a uniformly random key: the pattern that
            // finds cold pages, and it leaves the state as it was.
            Random random = new Random(42);
            Phase probe = new Phase("probe");
            long probes = 0;
            while (probes < randomOps && System.nanoTime() - probe.start < phaseNanos) {
                long id = (random.nextLong() & Long.MAX_VALUE) % rows;
                long t = System.nanoTime();
                feed.rightRow(join, id, 1);
                feed.rightRow(join, id, -1);
                probe.latency.record(System.nanoTime() - t);
                probes++;
                probe.progress(probes);
            }
            probe.end(probes);
            if (pairs[0] != 2 * probes) {
                throw new IllegalStateException(pairs[0] + " pairs for " + probes + " probes");
            }

            // Retractions of distinct random rows: a stride coprime to the row count visits each
            // once, with no permutation array on a heap that is part of the budget.
            long stride = coprimeStride(rows);
            Phase retract = new Phase("retract");
            long retracted = 0;
            while (retracted < randomOps && retracted < rows && System.nanoTime() - retract.start < phaseNanos) {
                long id = (retracted * stride + 12_345) % rows;
                long t = System.nanoTime();
                feed.leftRow(join, id, -1);
                retract.latency.record(System.nanoTime() - t);
                retracted++;
                retract.progress(retracted);
            }
            retract.end(retracted);
            if (join.rowsHeldLeft() != rows - retracted) {
                throw new IllegalStateException(join.rowsHeldLeft() + " rows held, expected " + (rows - retracted));
            }
            out.put("index_ram_mib", mib(join.indexRamBytes()));
            out.put("index_table_mapped_mib", mib(join.indexTableBytesMapped()));
            out.put("mapped_mib", mib(overflow.bytesMapped()));
            out.put("on_disk_mib", mib(du(dir)));
            // RAM tier (rows and index) plus every mapped byte: the state, wherever it lives.
            out.put(
                    "state_mib",
                    mib(join.stateBytes()
                            - join.rowSpillStatistics().overflowBytesReserved()
                            + join.indexRamBytes()
                            + overflow.bytesMapped()));
        } finally {
            feed.arena.close();
        }
    }

    private static long coprimeStride(long n) {
        long stride = (long) (n * 0.6180339887) | 1;
        while (gcd(stride, n) != 1) {
            stride += 2;
        }
        return stride;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // ------------------------------------------------------------------------ the windowed aggregate

    private static final SlicedAggregateState.Kind[] KINDS = {
        SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM, SlicedAggregateState.Kind.COUNT_DISTINCT
    };
    private static final int SLICES = 4;

    private static double aggregateBytesPerAccumulator() {
        long accumulators = 100_000;
        try (SlicedAggregateState probe =
                new SlicedAggregateState(new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), KINDS, 1_000_000)) {
            for (long g = 0; g < accumulators; g++) {
                probe.update(
                        g,
                        g,
                        new Object[] {g},
                        SECOND,
                        new long[] {0, g, g % 5},
                        new boolean[] {true, true, true},
                        new Object[] {null, null, g % 5},
                        1);
            }
            return (double) probe.offHeapBytesAllocated() / accumulators;
        }
    }

    private void aggregate(
            long stateBytes, int ceilingMiB, MappedFileMemoryAccess overflow, Path dir, long randomOps, long phaseNanos)
            throws IOException {
        double perAccumulator = aggregateBytesPerAccumulator();
        long groups = (long) (stateBytes / perAccumulator / SLICES);
        out.put("bytes_per_accumulator", String.format("%.0f", perAccumulator));
        out.put("accumulators", Long.toString(groups * SLICES));
        int maxSlices = ceilingMiB * MIB / 256;
        long[] values = new long[3];
        boolean[] present = {true, true, true};
        Object[] distinct = new Object[3];
        try (SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                KINDS,
                maxSlices,
                overflow,
                Integer.MAX_VALUE / 2)) {
            Phase insert = new Phase("insert");
            long n = 0;
            for (int slice = 0; slice < SLICES; slice++) {
                long time = slice * 10 * SECOND + SECOND;
                for (long group = 0; group < groups; group++) {
                    values[1] = group;
                    distinct[2] = group % 5;
                    if ((n++ & 15) == 0) {
                        insert.progress(n);
                        long t = System.nanoTime();
                        state.update(
                                group,
                                group * 0x9E3779B97F4A7C15L,
                                new Object[] {group},
                                time,
                                values,
                                present,
                                distinct,
                                1);
                        insert.latency.record(System.nanoTime() - t);
                    } else {
                        state.update(
                                group,
                                group * 0x9E3779B97F4A7C15L,
                                new Object[] {group},
                                time,
                                values,
                                present,
                                distinct,
                                1);
                    }
                }
            }
            insert.end(n);

            Random random = new Random(11);
            Phase update = new Phase("update");
            long updates = 0;
            while (updates < randomOps && System.nanoTime() - update.start < phaseNanos) {
                long group = (random.nextLong() & Long.MAX_VALUE) % groups;
                int slice = random.nextInt(SLICES);
                values[1] = 1;
                distinct[2] = group % 5;
                long t = System.nanoTime();
                state.update(
                        group,
                        group * 0x9E3779B97F4A7C15L,
                        new Object[] {group},
                        slice * 10 * SECOND + SECOND,
                        values,
                        present,
                        distinct,
                        1);
                update.latency.record(System.nanoTime() - t);
                updates++;
                update.progress(updates);
            }
            update.end(updates);

            // Firing one window of four: what a watermark advance costs. fire() walks every
            // accumulator in place and streams each group out as it is combined (SPILL-3), so its
            // reads are the whole state's and its heap is one result's. An OutOfMemoryError here
            // would be a regression, and is recorded rather than hidden.
            Phase fire = new Phase("fire");
            try {
                long fired = state.fire(10 * SECOND, result -> {});
                fire.end(fired);
                if (fired != groups) {
                    throw new IllegalStateException(fired + " results for " + groups + " groups");
                }
            } catch (OutOfMemoryError e) {
                // Before SPILL-3, fire() built the window on the heap -- a boxed handle per
                // accumulator, a map entry and a result per group -- and 1.6 M accumulators threw
                // here against a 160 MiB heap. Recorded, not hidden, should it come back.
                out.put(
                        "fire",
                        "OutOfMemoryError-at-heap-" + Runtime.getRuntime().maxMemory() / MIB + "MiB");
            }
            out.put("mapped_mib", mib(overflow.bytesMapped()));
            out.put("on_disk_mib", mib(du(dir)));
            out.put("state_mib", mib(state.offHeapBytesAllocated()));
        }
    }

    // ------------------------------------------------------------------------------------- plumbing

    /** One timed phase: throughput, latency percentiles, and what the kernel did for it. */
    private final class Phase {
        final String name;
        final long start;
        final long majorFaults;
        final long readBytes;
        final long writeBytes;
        final LatencyHistogram latency = new LatencyHistogram();

        Phase(String name) {
            this.name = name;
            this.majorFaults = majorFaults();
            this.readBytes = io("read_bytes");
            this.writeBytes = io("write_bytes");
            this.start = System.nanoTime();
        }

        private long lastProgress = System.nanoTime();

        /** Every ten seconds, how far the phase has got -- all a run that times out leaves behind. */
        void progress(long operations) {
            long now = System.nanoTime();
            if (now - lastProgress > 10 * SECOND) {
                lastProgress = now;
                System.out.printf(
                        "PROGRESS %s ops=%d s=%.0f majflt=%d read_mib=%s%n",
                        name,
                        operations,
                        (now - start) / 1e9,
                        majorFaults() - majorFaults,
                        mib(io("read_bytes") - readBytes));
            }
        }

        void end(long operations) {
            double seconds = (System.nanoTime() - start) / 1e9;
            out.put(name + "_ops", Long.toString(operations));
            out.put(name + "_s", String.format("%.1f", seconds));
            out.put(name + "_per_s", String.format("%.0f", operations / Math.max(seconds, 1e-9)));
            if (latency.count() > 0) {
                out.put(name + "_p50_us", us(latency.percentile(0.50)));
                out.put(name + "_p99_us", us(latency.percentile(0.99)));
                out.put(name + "_p999_us", us(latency.percentile(0.999)));
                out.put(name + "_max_us", us(latency.max()));
            }
            out.put(name + "_majflt", Long.toString(majorFaults() - majorFaults));
            out.put(name + "_read_mib", mib(io("read_bytes") - readBytes));
            out.put(name + "_write_mib", mib(io("write_bytes") - writeBytes));
        }
    }

    /** Log-linear: eight sub-buckets per power of two, so a percentile is within about 9 %. */
    static final class LatencyHistogram {
        private final long[] counts = new long[64 * 8];
        private long total;
        private long max;

        void record(long nanos) {
            long v = Math.max(1, nanos);
            int exponent = 63 - Long.numberOfLeadingZeros(v);
            int sub = exponent < 3 ? 0 : (int) ((v >>> (exponent - 3)) & 7);
            counts[exponent * 8 + sub]++;
            total++;
            max = Math.max(max, nanos);
        }

        long count() {
            return total;
        }

        long max() {
            return max;
        }

        /** The upper edge of the bucket holding the {@code q}-th value. */
        long percentile(double q) {
            long rank = (long) Math.ceil(q * total);
            long seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen >= rank && counts[i] > 0) {
                    int exponent = i / 8;
                    int sub = i % 8;
                    return exponent < 3
                            ? (1L << (exponent + 1))
                            : (1L << exponent) + ((long) (sub + 1) << (exponent - 3));
                }
            }
            return max;
        }
    }

    private void cgroup() {
        Path group = cgroupPath();
        if (group == null) {
            return;
        }
        out.put("cgroup_memory_max", read(group.resolve("memory.max")).trim());
        String peak = read(group.resolve("memory.peak")).trim();
        out.put("cgroup_memory_peak_mib", peak.isEmpty() ? "?" : mib(Long.parseLong(peak)));
        for (String line : read(group.resolve("memory.events")).split("\n")) {
            String[] parts = line.split(" ");
            if (parts.length == 2 && (parts[0].equals("oom_kill") || parts[0].equals("max"))) {
                out.put("cgroup_events_" + parts[0], parts[1]);
            }
        }
        for (String line : read(group.resolve("memory.stat")).split("\n")) {
            String[] parts = line.split(" ");
            if (parts.length == 2 && (parts[0].equals("anon") || parts[0].equals("file"))) {
                out.put("cgroup_" + parts[0] + "_mib", mib(Long.parseLong(parts[1])));
            }
        }
    }

    static Path cgroupPath() {
        for (String line : read(Path.of("/proc/self/cgroup")).split("\n")) {
            if (line.startsWith("0::")) {
                return Path.of("/sys/fs/cgroup" + line.substring(3).trim());
            }
        }
        return null;
    }

    private static long majorFaults() {
        String stat = read(Path.of("/proc/self/stat"));
        if (stat.isEmpty()) {
            return 0;
        }
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return Long.parseLong(fields[9]);
    }

    private static long io(String field) {
        for (String line : read(Path.of("/proc/self/io")).split("\n")) {
            if (line.startsWith(field + ":")) {
                return Long.parseLong(line.substring(field.length() + 1).trim());
            }
        }
        return 0;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String mib(long bytes) {
        return String.format("%.1f", bytes / (double) MIB);
    }

    private static String us(long nanos) {
        return String.format("%.1f", nanos / 1e3);
    }

    static long du(Path dir) {
        try {
            Process process = new ProcessBuilder("du", "-sk", dir.toString())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return Long.parseLong(text.split("\\s+")[0]) * 1024;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            return -1;
        }
    }
}

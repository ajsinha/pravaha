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
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

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
import com.ash.messaging.pravaha.state.SpillStatistics;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-044's measurement: join and windowed-aggregate state driven to several multiples of a memory
 * ceiling, with the overflow tier taking the excess, against the same load held entirely in RAM.
 *
 * <p><strong>Not part of the default build.</strong> Named {@code *IT}, which the root POM's surefire
 * configuration excludes, because it moves gigabytes and takes minutes. Run it on purpose:
 *
 * <pre>
 * ./mvnw -o -pl pravaha-runtime -am test -Dtest=SpillTierMeasurementIT \
 *     -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Knobs, all system properties: {@code pravaha.spill.measurement.ceilingMiB} (default 64 -- the RAM
 * tier a join is given by {@code InterpretedPipeline}), {@code .multiples} (default {@code 1,2,4,8}),
 * and {@code .dir} (default {@code target/spill-measurement} under this module -- deliberately
 * <em>not</em> {@code java.io.tmpdir}, which on many Linux machines is a tmpfs, i.e. RAM, and would
 * measure nothing about a disk).
 *
 * <p><strong>What it measures and what it cannot.</strong> Each phase is timed with the wall clock
 * around a single-threaded loop, the way a lane runs it. "On disk" is what {@code du} reports for the
 * spill directory (sparse files: blocks actually written), "mapped" is slab bytes held, "live" is bytes
 * in live blocks. The machine's page cache is not dropped between phases -- this process cannot, and on
 * a machine whose free RAM exceeds the spilled state the mapped files mostly stay resident -- so the
 * spill numbers are the cost of the mapped tier's indirection and write-back, not of reading cold
 * state from the device. A state much larger than free RAM would cost more, and this test cannot
 * produce one on a machine with more RAM than disk budget; the ADR says so beside the numbers.
 */
class SpillTierMeasurementIT {

    private static final long SECOND = 1_000_000_000L;
    private static final int MIB = 1 << 20;

    private static final int CEILING_MIB = Integer.getInteger("pravaha.spill.measurement.ceilingMiB", 64);
    private static final int[] MULTIPLES = java.util.Arrays.stream(
                    System.getProperty("pravaha.spill.measurement.multiples", "1,2,4,8")
                            .split(","))
            .mapToInt(s -> Integer.parseInt(s.trim()))
            .toArray();
    private static final Path DIR = Path.of(System.getProperty(
            "pravaha.spill.measurement.dir",
            Path.of("target", "spill-measurement").toAbsolutePath().toString()));

    private final List<String> report = new ArrayList<>();

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

    /** Feeds rows into a join through a lane-sized arena, reset as a lane resets it between batches. */
    private static final class JoinFeeder {
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

    private record JoinRun(
            long rows,
            double insertPerSecond,
            double probePerSecond,
            double retractPerSecond,
            long liveBytes,
            long indexBytes,
            long mappedBeforeCompaction,
            long diskBeforeCompaction,
            long mappedAfter,
            long diskAfter,
            long liveAfter,
            double compactionMillis,
            int slabsReleased) {}

    private JoinRun runJoin(long rows, int ramSlabs, MappedFileMemoryAccess overflow, Path spillDir)
            throws IOException {
        long[] pairs = {0};
        RowProcessor sink = row -> pairs[0]++;
        JoinFeeder feed = new JoinFeeder();
        try (SymmetricHashJoin join = overflow == null
                ? new SymmetricHashJoin(joinPlan(), feed.arena, sink, ramSlabs)
                : new SymmetricHashJoin(joinPlan(), feed.arena, sink, ramSlabs, overflow, Integer.MAX_VALUE / 2)) {
            long start = System.nanoTime();
            for (long id = 0; id < rows; id++) {
                feed.leftRow(join, id, 1);
            }
            double insert = rows / seconds(start);
            long liveBytes = join.stateBytes();
            long indexBytes = join.indexRamBytes();

            // Probes at random across everything held -- in the spilled run, mostly the overflow tier.
            // Each is a right row in and out again, so the state being probed does not grow.
            Random random = new Random(42);
            long probes = Math.min(rows, 500_000);
            start = System.nanoTime();
            for (long i = 0; i < probes; i++) {
                long id = (random.nextLong() & Long.MAX_VALUE) % rows;
                feed.rightRow(join, id, 1);
                feed.rightRow(join, id, -1);
            }
            double probe = probes / seconds(start);
            assertThat(pairs[0]).isEqualTo(2 * probes);

            // Churn: three rows in four retracted, at random, as expiry or updates would leave a join.
            long[] order = shuffled(rows, new Random(7));
            long retracted = rows * 3 / 4;
            start = System.nanoTime();
            for (long i = 0; i < retracted; i++) {
                feed.leftRow(join, order[(int) i], -1);
            }
            double retract = retracted / seconds(start);
            assertThat(join.rowsHeldLeft()).isEqualTo(rows - retracted);

            SpillStatistics before = join.spillStatistics();
            long diskBefore = overflow == null ? 0 : du(spillDir);
            start = System.nanoTime();
            int released = overflow == null ? 0 : join.compactIfFragmented(0.5);
            double compactionMillis = (System.nanoTime() - start) / 1e6;
            SpillStatistics after = join.spillStatistics();
            long diskAfter = overflow == null ? 0 : du(spillDir);

            // Every row still held still matches, after the compaction moved it.
            pairs[0] = 0;
            for (long i = retracted; i < Math.min(rows, retracted + 10_000); i++) {
                feed.rightRow(join, order[(int) i], 1);
                feed.rightRow(join, order[(int) i], -1);
            }
            assertThat(pairs[0]).isEqualTo(2 * Math.min(rows - retracted, 10_000));

            return new JoinRun(
                    rows,
                    insert,
                    probe,
                    retract,
                    liveBytes,
                    indexBytes,
                    before.overflowBytesReserved(),
                    diskBefore,
                    after.overflowBytesReserved(),
                    diskAfter,
                    after.overflowBytesLive(),
                    compactionMillis,
                    released);
        } finally {
            feed.arena.close();
        }
    }

    @Test
    void joinStateAtSeveralMultiplesOfItsMemoryCeiling() throws IOException {
        header();
        // Bytes one left row costs in the store: measured, so the multiples are of what the rows
        // really take rather than of an estimate.
        long calibrationRows = 50_000;
        JoinRun calibration = runJoin(calibrationRows, 4096, null, null);
        double bytesPerRow = (double) calibration.liveBytes() / calibrationRows;
        runJoin(200_000, 4096, null, null); // warm-up, discarded
        report.add(String.format(
                "join: %.0f bytes of store per row; ceiling %d MiB of RAM slabs", bytesPerRow, CEILING_MIB));
        report.add("| state / ceiling | rows | tier | insert rows/s | probe/s | retract/s | key index (RAM) | on disk"
                + " before compaction | on disk after | live after | compaction |");
        report.add("|---|---|---|---|---|---|---|---|---|---|---|");
        for (int multiple : MULTIPLES) {
            long rows = (long) (multiple * (double) CEILING_MIB * MIB / bytesPerRow * 0.97);
            JoinRun ram = runJoin(rows, (int) (multiple * CEILING_MIB * 1.25) + 8, null, null);
            Path dir = fresh("join-" + multiple);
            JoinRun spill;
            try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir)) {
                spill = runJoin(rows, CEILING_MIB, overflow, dir);
            }
            report.add(row(multiple, rows, "RAM", ram, false));
            report.add(row(multiple, rows, "spill", spill, true));
            deleteTree(dir);
        }
        print("join");
    }

    private static String row(int multiple, long rows, String tier, JoinRun run, boolean spilled) {
        return String.format(
                "| %dx | %,d | %s | %,.0f | %,.0f | %,.0f | %s | %s | %s | %s | %s |",
                multiple,
                rows,
                tier,
                run.insertPerSecond(),
                run.probePerSecond(),
                run.retractPerSecond(),
                mib(run.indexBytes()),
                spilled ? mib(run.diskBeforeCompaction()) + " (" + mib(run.mappedBeforeCompaction()) + " mapped)" : "—",
                spilled ? mib(run.diskAfter()) + " (" + mib(run.mappedAfter()) + " mapped)" : "—",
                spilled ? mib(run.liveAfter()) : "—",
                spilled
                        ? String.format("%.0f ms, %d slabs released", run.compactionMillis(), run.slabsReleased())
                        : "—");
    }

    // ------------------------------------------------------------------------ the windowed aggregate

    private static final SlicedAggregateState.Kind[] KINDS = {
        SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM, SlicedAggregateState.Kind.COUNT_DISTINCT
    };
    private static final int SLICES = 4;

    private record AggregateRun(
            long accumulators,
            double updatePerSecond,
            double randomUpdatePerSecond,
            double firedPerSecond,
            long mappedBefore,
            long diskBefore,
            long mappedAfter,
            long diskAfter,
            long liveAfter,
            double compactionMillis,
            int slabsReleased,
            boolean spilled) {}

    private AggregateRun runAggregate(long accumulators, int maxSlices, MappedFileMemoryAccess overflow, Path dir)
            throws IOException {
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));
        long groups = accumulators / SLICES;
        long[] values = new long[3];
        boolean[] present = {true, true, true};
        Object[] distinct = new Object[3];
        try (SlicedAggregateState state = overflow == null
                ? new SlicedAggregateState(windows, KINDS, maxSlices)
                : new SlicedAggregateState(windows, KINDS, maxSlices, overflow, Integer.MAX_VALUE / 2)) {
            long start = System.nanoTime();
            for (int slice = 0; slice < SLICES; slice++) {
                long time = slice * 10L * SECOND + SECOND;
                for (long group = 0; group < groups; group++) {
                    values[1] = group;
                    distinct[2] = group % 5;
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
            double update = (double) groups * SLICES / seconds(start);

            // Updates to existing accumulators in random order: the access pattern a skewed key space
            // does not have and a uniform one does, and the one that finds cold pages.
            Random random = new Random(11);
            long randomUpdates = Math.min(accumulators, 500_000);
            start = System.nanoTime();
            for (long i = 0; i < randomUpdates; i++) {
                long group = (random.nextLong() & Long.MAX_VALUE) % groups;
                int slice = random.nextInt(SLICES);
                values[1] = 1;
                distinct[2] = group % 5;
                state.update(
                        group,
                        group * 0x9E3779B97F4A7C15L,
                        new Object[] {group},
                        slice * 10L * SECOND + SECOND,
                        values,
                        present,
                        distinct,
                        1);
            }
            double randomUpdate = randomUpdates / seconds(start);

            start = System.nanoTime();
            long fired = 0;
            for (int slice = 0; slice < SLICES; slice++) {
                fired += state.fire((slice + 1) * 10L * SECOND, result -> {});
            }
            double firedPerSecond = fired / seconds(start);
            assertThat(fired).isEqualTo(groups * SLICES);

            // The churn a windowed aggregate always has: the watermark passes three of four slices.
            state.discardSlicesEndingBefore(30 * SECOND, 0);
            SpillStatistics before = state.spillStatistics();
            long diskBefore = overflow == null ? 0 : du(dir);
            start = System.nanoTime();
            int released = overflow == null ? 0 : state.compactIfFragmented(0.5);
            double compactionMillis = (System.nanoTime() - start) / 1e6;
            SpillStatistics after = state.spillStatistics();
            long diskAfter = overflow == null ? 0 : du(dir);
            assertThat(state.fire(40 * SECOND)).hasSize((int) groups);
            return new AggregateRun(
                    accumulators,
                    update,
                    randomUpdate,
                    firedPerSecond,
                    before.overflowBytesReserved(),
                    diskBefore,
                    after.overflowBytesReserved(),
                    diskAfter,
                    after.overflowBytesLive(),
                    compactionMillis,
                    released,
                    state.hasSpilled());
        }
    }

    @Test
    void windowedAggregateStateAtSeveralMultiplesOfItsMemoryCeiling() throws IOException {
        header();
        // The RAM tier a windowed aggregate gets is derived from its slice ceiling: 256 bytes a slice,
        // in 64 KiB slabs, for the accumulators and again for the distinct values.
        int maxSlices = CEILING_MIB * MIB / 256;
        // An accumulator and its one distinct value take a 128-byte block each: measured, like the
        // join's, from a small all-RAM run.
        long probeAccumulators = 40_000;
        long bytesPerAccumulator;
        try (SlicedAggregateState probe =
                new SlicedAggregateState(new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), KINDS, 1_000_000)) {
            for (long g = 0; g < probeAccumulators; g++) {
                probe.update(g, g, new Object[] {g}, SECOND, new long[] {0, g, g % 5}, 1);
            }
            bytesPerAccumulator = probe.offHeapBytesAllocated() / probeAccumulators;
        }
        runAggregate(200_000, 1_000_000, null, null); // warm-up, discarded
        // The ceiling is one store's RAM tier; state is the accumulators' bytes against it.
        long perAccumulator = 128;
        report.add(String.format(
                "windowed aggregate: ~%d bytes off-heap per accumulator incl. index and distinct value (measured,"
                        + " all-RAM); ceiling %d MiB of RAM slabs per store (maxSlices %,d)",
                bytesPerAccumulator, CEILING_MIB, maxSlices));
        report.add("| state / ceiling | accumulators | tier | update/s | random update/s | fired/s | on disk"
                + " before compaction | on disk after | live after | compaction |");
        report.add("|---|---|---|---|---|---|---|---|---|---|");
        for (int multiple : MULTIPLES) {
            long accumulators = (long) multiple * CEILING_MIB * MIB / perAccumulator / SLICES * SLICES;
            AggregateRun ram =
                    runAggregate(accumulators, (int) Math.min(Integer.MAX_VALUE, accumulators * 2), null, null);
            Path dir = fresh("aggregate-" + multiple);
            AggregateRun spill;
            try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir)) {
                spill = runAggregate(accumulators, maxSlices, overflow, dir);
            }
            if (multiple > 1) {
                assertThat(spill.spilled())
                        .as("%dx the ceiling must spill", multiple)
                        .isTrue();
            }
            report.add(aggregateRow(multiple, "RAM", ram, false));
            report.add(aggregateRow(multiple, "spill", spill, true));
            deleteTree(dir);
        }
        print("windowed aggregate");
    }

    private static String aggregateRow(int multiple, String tier, AggregateRun run, boolean spilled) {
        return String.format(
                "| %dx | %,d | %s | %,.0f | %,.0f | %,.0f | %s | %s | %s | %s |",
                multiple,
                run.accumulators(),
                tier,
                run.updatePerSecond(),
                run.randomUpdatePerSecond(),
                run.firedPerSecond(),
                spilled ? mib(run.diskBefore()) + " (" + mib(run.mappedBefore()) + " mapped)" : "—",
                spilled ? mib(run.diskAfter()) + " (" + mib(run.mappedAfter()) + " mapped)" : "—",
                spilled ? mib(run.liveAfter()) : "—",
                spilled
                        ? String.format("%.0f ms, %d slabs released", run.compactionMillis(), run.slabsReleased())
                        : "—");
    }

    // ------------------------------------------------------------------------------------- plumbing

    private void header() {
        report.add(String.format(
                "%s, %d logical CPUs, max heap %,d MiB, JDK %s, spill directory %s",
                System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / MIB,
                Runtime.version(),
                DIR));
    }

    private void print(String what) throws IOException {
        String text = String.join(System.lineSeparator(), report) + System.lineSeparator();
        System.out.println("ADR-044 measurement, " + what + ":" + System.lineSeparator() + text);
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve(what.replace(' ', '-') + ".md"), text, StandardCharsets.UTF_8);
    }

    private static double seconds(long startNanos) {
        return Math.max(1e-9, (System.nanoTime() - startNanos) / 1e9);
    }

    private static long[] shuffled(long n, Random random) {
        long[] order = new long[(int) n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order;
    }

    private static String mib(long bytes) {
        return String.format("%,.1f MiB", bytes / (double) MIB);
    }

    private static Path fresh(String name) throws IOException {
        Path dir = DIR.resolve(name);
        deleteTree(dir);
        Files.createDirectories(dir);
        return dir;
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

    /** Blocks actually allocated under a directory -- sparse files counted by what was written. */
    private static long du(Path dir) {
        try {
            Process process = new ProcessBuilder("du", "-sk", dir.toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return Long.parseLong(out.split("\\s+", -1)[0]) * 1024;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            return -1;
        }
    }
}

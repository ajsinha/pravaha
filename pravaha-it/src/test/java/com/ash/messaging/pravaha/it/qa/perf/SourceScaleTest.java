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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a <em>bound source</em> costs a node, measured rather than reasoned about.
 *
 * <p>{@link NodeScaleTest} answers "what does a registered query cost". It registers queries that
 * are fed by nobody, so every number in it is the cost of the computation and none of it is the cost
 * of reading. That is half of ADR-036's target: the stated goal is thousands of <em>Aerospike-backed
 * continuous queries</em>, and the reading half had no number at all.
 *
 * <p>This is the other half. The per-query cost is held constant and the binding is varied, so what
 * is reported is attributable to the source and not to the lane behind it:
 *
 * <ul>
 *   <li><strong>unbound</strong> -- N queries, no source. {@link
 *       com.ash.messaging.pravaha.registry.SourceFeed#NONE}, no feed thread, no reader. This is
 *       NodeScaleTest's shape and it is the baseline every other row is subtracted from.
 *   <li><strong>bound, not following</strong> -- N queries, one file each, read to the end and then
 *       latched exhausted.
 *   <li><strong>bound, following</strong> -- N queries, one file each, {@code tail -f}. The shape a
 *       continuous query over a file actually has, and the expensive one.
 * </ul>
 *
 * <p>The units are the ones that bind a node: <strong>open file descriptors</strong>, which is the
 * ceiling nobody set; <strong>platform threads</strong>, which W9-2 made the feed stop paying;
 * <strong>heap</strong>; and <strong>process CPU while completely idle</strong>, which is the one
 * that does not appear in any count and is what a polling feed costs at a thousand sources.
 *
 * <p><strong>Distinct SQL per query, on purpose, over distinct streams.</strong> Identical SQL
 * shares one computation by fingerprint and therefore one feed, and measuring that would measure
 * sharing rather than cost. Two queries with <em>different</em> SQL over the <em>same</em> source
 * share nothing at all -- see {@link #twoQueriesOverOneSourceShareNothingButTheSchema()} -- and that
 * is the common case and the one the target runs into.
 */
@Timeout(900)
final class SourceScaleTest {

    /**
     * How many sources to bind.
     *
     * <p>Not thousands: at the cost this measures a thousand bound sources does not fit in a test
     * JVM alongside a thousand lanes and a thousand 4 MiB arenas, which is ADR-036's finding and not
     * this test's problem. A hundred is enough to make a per-source number unambiguous against the
     * JVM's own noise and small enough to run three times in one method.
     */
    private static final int SOURCES = 100;

    /**
     * File descriptors per bound source that this build is allowed to cost.
     *
     * <p>A ratchet, and it may only fall. <strong>Measured at 1.00</strong>: {@code
     * FilesystemSourcePlugin.partitions} returns exactly one partition per binding and {@link
     * com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds} opens one reader per partition,
     * which holds one {@code BufferedReader} for the life of the query.
     *
     * <p>One is small and it is the number that decides how many followed files a node holds,
     * because it is multiplied by a default -- {@code ulimit -n}, commonly 1024 -- that nothing in
     * this repository checks, reports or mentions. See SRC-4.
     */
    private static final double DESCRIPTORS_PER_SOURCE = 1.0;

    /**
     * Platform threads that binding {@link #SOURCES} sources may add, in total.
     *
     * <p>A ratchet, and it may only fall. Deliberately a <strong>total and not a rate</strong>,
     * because the measurement says it is one: a hundred bound sources added eight platform threads,
     * not a hundred. The feed loop is virtual since W9-2, so what those eight are is the virtual
     * scheduler's carrier pool, which is bounded by {@code availableProcessors} however many feeds
     * mount on it. A per-source figure of 0.08 would be an artefact of dividing a constant by a
     * hundred, and it would fall as the source count rose.
     *
     * <p>So the assertion is that binding sources costs a bounded number of platform threads rather
     * than a proportional one. If this fails, the feed has gone back to a platform thread and the
     * number of bound queries a node holds has halved.
     */
    private static final long PLATFORM_THREAD_CEILING = Runtime.getRuntime().availableProcessors() + 4L;

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    /** How long to leave a fully-idle node alone while its CPU time is measured. */
    private static final Duration IDLE_WINDOW = Duration.ofSeconds(5);

    private record Cost(
            String label,
            int sources,
            long descriptors,
            long platformThreads,
            long heapBytes,
            long idleCpuNanosPerSecond) {

        double descriptorsEach(Cost baseline) {
            return (descriptors - baseline.descriptors) / (double) sources;
        }

        long heapKibEach(Cost baseline) {
            return (heapBytes - baseline.heapBytes) / sources / 1024;
        }

        /** Microseconds of CPU, per second of wall clock, per source, with nothing at all to read. */
        double idleCpuMicrosEach(Cost baseline) {
            return (idleCpuNanosPerSecond - baseline.idleCpuNanosPerSecond) / (double) sources / 1000.0;
        }

        String line() {
            return String.format(
                    "  %-24s n=%3d  fds %4d  +threads %3d  heap %8d KiB  idle cpu %8.1f ms/s",
                    label, sources, descriptors, platformThreads, heapBytes / 1024, idleCpuNanosPerSecond / 1e6);
        }
    }

    @TempDir
    Path directory;

    @Test
    void whatOneBoundSourceCosts() throws IOException {
        // Two source counts for the following case, because the whole value of a per-source number
        // is the extrapolation, and an extrapolation from one point is an assumption. Half the
        // sources should cost half the idle CPU; if it does not, the cost is not per-source and
        // nothing here may be multiplied by a thousand.
        Cost unboundHalf = measure("unbound", SOURCES / 2, false, false);
        Cost followingHalf = measure("bound, following", SOURCES / 2, true, true);
        Cost unbound = measure("unbound", SOURCES, false, false);
        Cost bounded = measure("bound, not following", SOURCES, true, false);
        Cost following = measure("bound, following", SOURCES, true, true);

        double halfEach = followingHalf.idleCpuMicrosEach(unboundHalf);
        double fullEach = following.idleCpuMicrosEach(unbound);

        System.out.printf(
                "%nSOURCE SCALE: distinct SQL over one distinct stream each, %d cores%n"
                        + "%s%n%s%n%s%n%s%n%s%n"
                        + "%n  per bound source, over the unbound baseline at the same query count:%n"
                        + "    not following:      %.2f fds, %d KiB heap, %.0f us cpu/s idle%n"
                        + "    following, n=%3d:   %.2f fds, %d KiB heap, %.0f us cpu/s idle%n"
                        + "    following, n=%3d:   %.2f fds, %d KiB heap, %.0f us cpu/s idle%n"
                        + "%n  binding %d sources added %d platform threads in total, not %d:%n"
                        + "  the feed loop is virtual, and those are the scheduler's carriers.%n"
                        + "%n  A followed source is polled every millisecond whether or not anything wrote to%n"
                        + "  it, and each poll of a followed file is a stat() plus a read(). That cost is a%n"
                        + "  constant per source and it is paid with the node completely idle.%n"
                        + "  Heap is reported and not asserted: it is a measurement of the JVM's mood as%n"
                        + "  much as the engine's.%n",
                Runtime.getRuntime().availableProcessors(),
                unboundHalf.line(),
                followingHalf.line(),
                unbound.line(),
                bounded.line(),
                following.line(),
                bounded.descriptorsEach(unbound),
                bounded.heapKibEach(unbound),
                bounded.idleCpuMicrosEach(unbound),
                followingHalf.sources(),
                followingHalf.descriptorsEach(unboundHalf),
                followingHalf.heapKibEach(unboundHalf),
                halfEach,
                following.sources(),
                following.descriptorsEach(unbound),
                following.heapKibEach(unbound),
                fullEach,
                SOURCES,
                following.platformThreads() - unbound.platformThreads(),
                SOURCES);

        assertThat(following.descriptorsEach(unbound))
                .as(
                        "%d followed sources held %d file descriptors against an unbound baseline of %d (%.2f "
                                + "each). This is the number that decides how many followed files one node holds, "
                                + "against a `ulimit -n` nothing in this repository checks -- see SRC-4",
                        SOURCES, following.descriptors(), unbound.descriptors(), following.descriptorsEach(unbound))
                .isLessThanOrEqualTo(DESCRIPTORS_PER_SOURCE);

        assertThat(following.platformThreads() - unbound.platformThreads())
                .as(
                        "binding %d sources added %d platform threads. The feed is virtual since W9-2 and must "
                                + "stay so: a platform thread per feed doubles what a bound query costs and halves "
                                + "how many a node holds",
                        SOURCES, following.platformThreads() - unbound.platformThreads())
                .isLessThanOrEqualTo(PLATFORM_THREAD_CEILING);

        // Reported and deliberately NOT asserted, and the reason is a failure this test already
        // caused. Process CPU is only a measurement of this engine when nothing else is running,
        // and the place this test runs is a full `mvn verify` -- so the other work is the verify
        // itself, compiling and garbage-collecting between the baseline window and the measured
        // one. Run inside one, the hundred-source figure came out *negative*: the followed sources
        // appeared to use less CPU than no sources at all.
        //
        // NodeScaleTest states the rule this breaks -- "a ratchet on a noisy number is a ratchet
        // somebody will delete" -- and a build failing on the machine's mood is worse than no
        // ratchet. The two counts that are counts, descriptors and platform threads, carry the
        // ratchets; the CPU figure is evidence for the extrapolation in ADR-036 and is quotable
        // only from a run on a quiet machine, which is what the line below says out loud.
        double linearity = halfEach == 0 ? Double.NaN : fullEach / halfEach;
        System.out.printf(
                "  idle cpu per source: %.0f us/s at n=%d, %.0f at n=%d -- ratio %.2f"
                        + com.ash.messaging.pravaha.common.observe.CoverageAgent.caveat() + "%n"
                        + "  Quotable only from a quiet machine. Inside a full verify this is noise:%n"
                        + "  the verify's own compilation lands in the same process-CPU counter.%n"
                        + "  A ratio near 1 says the cost is per-source; near 2, a constant of the node.%n",
                halfEach,
                followingHalf.sources(),
                fullEach,
                following.sources(),
                linearity);
    }

    /**
     * What two queries over one source share, and what they do not.
     *
     * <p>The interesting case and the one ADR-036 §3 turns on. Identical SQL shares by fingerprint,
     * which is well covered elsewhere; <em>different</em> SQL over the same stream is the common
     * case, and what it shares is nothing: two registrations, two executions, two feeds, two plugin
     * instances, two readers, two descriptors on one file.
     *
     * <p>Asserted rather than described, because the day it stops being true is the day ADR-036 §3
     * is done and this is the test that should tell somebody so.
     */
    @Test
    void twoQueriesOverOneSourceShareNothingButTheSchema() throws IOException {
        Path file = writeFile(0);
        StreamSchema stream = schema("s0");
        PluginSourceFeeds feeds = new PluginSourceFeeds().bind(binding("s0", file, true));

        long before = descriptorsOn(file);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, stream).feedingFrom(feeds)) {
            registry.register("same_a", "SELECT user_id FROM s0 WHERE amount > 1", List.of(0), DANA);
            long afterFirst = descriptorsOn(file);

            // Same text, so the same plan, so the same fingerprint: one computation, one feed.
            registry.register("same_b", "SELECT user_id FROM s0 WHERE amount > 1", List.of(0), DANA);
            long afterIdentical = descriptorsOn(file);

            // Different text, same stream. A different plan is a different fingerprint, and nothing
            // downstream of the fingerprint is shared -- including the reading.
            registry.register("different", "SELECT user_id FROM s0 WHERE amount > 2", List.of(0), DANA);
            long afterDifferent = descriptorsOn(file);

            System.out.printf(
                    "%nSOURCE SHARING: descriptors on one file%n"
                            + "  first query:            +%d%n"
                            + "  identical SQL again:    +%d  (shared by fingerprint)%n"
                            + "  different SQL, same source: +%d  (shares nothing)%n",
                    afterFirst - before, afterIdentical - afterFirst, afterDifferent - afterIdentical);

            assertThat(afterIdentical - afterFirst)
                    .as("identical SQL shares one computation and therefore one feed and one reader")
                    .isZero();
            assertThat(afterDifferent - afterIdentical)
                    .as("two queries with different SQL over the same source open the source twice. That is "
                            + "one descriptor on a file and one scan on an Aerospike set, and it is what "
                            + "ADR-036 section 3 is about -- when a shared reader exists this assertion is "
                            + "the one that changes")
                    .isEqualTo(1L);

            assertThat(registry.size())
                    .as("two distinct computations behind three names")
                    .isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Measurement
    // -----------------------------------------------------------------------------------------

    private Cost measure(String label, int count, boolean bind, boolean follow) throws IOException {
        StreamSchema[] streams = new StreamSchema[count];
        PluginSourceFeeds feeds = new PluginSourceFeeds();
        for (int i = 0; i < count; i++) {
            streams[i] = schema("s" + i);
            if (bind) {
                feeds.bind(binding("s" + i, writeFile(i), follow));
            }
        }

        long descriptorsBefore = openDescriptors();
        long threadsBefore = platformThreads();
        long heapBefore = usedHeapAfterGc();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, streams).feedingFrom(feeds)) {
            for (int i = 0; i < count; i++) {
                // Distinct SQL over a distinct stream: one computation and one feed each, which is
                // what makes the difference between the rows a per-source cost.
                registry.register(
                        "q" + i, "SELECT user_id, amount FROM s" + i + " WHERE amount > " + i, List.of(0), DANA);
            }

            // Everything the sources hold has been read by now, so what the idle window measures is
            // the polling and nothing else. A non-following reader has latched exhausted; a
            // following one is at end of file with a writer that will never write again.
            settle();

            long cpuBefore = processCpuNanos();
            long wallBefore = System.nanoTime();
            park(IDLE_WINDOW);
            long idleCpuPerSecond =
                    Math.round((processCpuNanos() - cpuBefore) * 1e9 / (double) (System.nanoTime() - wallBefore));

            // Deltas, taken before the registry is closed. An absolute descriptor count includes
            // the JVM's own jars and the test framework's, which is noise that differs between
            // scenarios for reasons nothing here controls.
            return new Cost(
                    label,
                    count,
                    openDescriptors() - descriptorsBefore,
                    platformThreads() - threadsBefore,
                    usedHeapAfterGc() - heapBefore,
                    idleCpuPerSecond);
        }
    }

    /**
     * Lets the feeds drain what the files hold.
     *
     * <p>Not an arbitrary pause: the idle measurement below is only meaningful once nothing has
     * anything left to read, and a feed that is still delivering rows would be measured as the cost
     * of being idle.
     */
    private static void settle() {
        park(Duration.ofSeconds(2));
    }

    private Path writeFile(int index) throws IOException {
        Path file = directory.resolve("source-" + index + ".csv");
        if (Files.exists(file)) {
            return file;
        }
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            text.append("u").append(row).append(',').append(100L + row * 7L).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static SourceBinding binding(String stream, Path file, boolean follow) {
        return new SourceBinding(
                stream,
                "filesystem",
                Map.of(
                        "path", file.toString(),
                        "schema", "user_id:STRING,amount:INT64",
                        "follow", Boolean.toString(follow)));
    }

    private static StreamSchema schema(String name) {
        return StreamSchema.builder(name)
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    /**
     * Descriptors this process holds on {@code file} itself. The process-wide count moves with
     * whatever else the JVM opens or closes meanwhile -- a metrics or tracing thread, a finished
     * test's leftovers -- and once went down by six between two registrations; the number this
     * test is about is the file's own.
     */
    private static long descriptorsOn(Path file) throws IOException {
        Path target = file.toRealPath();
        long count = 0;
        try (var entries = Files.list(Path.of("/proc/self/fd"))) {
            for (Path fd : (Iterable<Path>) entries::iterator) {
                try {
                    if (Files.readSymbolicLink(fd).equals(target)) {
                        count++;
                    }
                } catch (IOException | UnsupportedOperationException gone) {
                    // Closed between the listing and the read: not open on the file.
                }
            }
        }
        return count;
    }

    /**
     * Open file descriptors for this process.
     *
     * <p>Through the JVM's own Unix MXBean where it is present, and {@code /proc/self/fd} otherwise.
     * Counting a directory is not elegant; being able to state a number here rather than reason
     * about one is worth the inelegance, and this is the ceiling nobody set.
     */
    private static long openDescriptors() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        try (var entries = Files.list(Path.of("/proc/self/fd"))) {
            return entries.count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : -1L;
    }

    private static long platformThreads() {
        return Thread.getAllStackTraces().keySet().size();
    }

    private static long usedHeapAfterGc() {
        System.gc();
        park(Duration.ofMillis(200));
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void park(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

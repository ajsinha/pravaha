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
package com.ash.messaging.pravaha.it.qa.state;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STATE-092..094 -- compaction, and what happens because nothing calls it.
 */
@Timeout(180)
class StateCompactionTest extends StateTestSupport {

    private static boolean straceUsable() {
        try {
            Process p =
                    new ProcessBuilder("strace", "-V").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("pravaha-server/src/main/resources/application.yaml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).isNotNull();
        return dir;
    }

    @Test
    void state092_compactRewritesTheJournalWithOnlyWhatIsLive(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        for (int i = 0; i < 50; i++) {
            journal.recordRegistration(
                    "q" + i, "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        }
        for (int i = 0; i < 50; i += 2) {
            journal.recordDrop("q" + i);
        }

        List<RegistryJournal.Entry> live = journal.replay();
        assertThat(live).hasSize(25);
        List<String> expectedNames = new ArrayList<>();
        for (int i = 1; i < 50; i += 2) {
            expectedNames.add("q" + i);
        }
        assertThat(live.stream().map(RegistryJournal.Entry::name).toList()).containsExactlyElementsOf(expectedNames);

        long before = Files.size(journalFile);
        journal.compact(live);

        List<RegistryJournal.Entry> afterCompact = new RegistryJournal(journalFile).replay();
        assertThat(afterCompact.stream().map(RegistryJournal.Entry::name).toList())
                .containsExactlyElementsOf(expectedNames);

        long after = Files.size(journalFile);
        assertThat(after).isLessThan(before);
        assertThat(Files.exists(dir.resolve("registry.journal.compacting"))).isFalse();

        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(journalFile))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }

        Assumptions.assumeTrue(straceUsable(), "requires a usable strace (ptrace) in this environment");
        Path journalFile2 = dir.resolve("registry2.journal");
        Path traceFile = dir.resolve("trace.log");
        List<String> command = new ArrayList<>();
        command.add("strace");
        command.add("-f");
        command.add("-e");
        command.add("trace=fsync,fdatasync");
        command.add("-o");
        command.add(traceFile.toString());
        command.add(javaBinary());
        command.add("-cp");
        command.add(classpath());
        command.add(StraceCompactRunner.class.getName());
        command.add(journalFile2.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> out = readAll(process);
        assertThat(process.waitFor()).as(String.join("\n", out)).isZero();
        String trace = Files.readString(traceFile);
        // 10 registrations + 5 drops = 15 appends to the original journal, each its own fsync (one
        // record at a time, per the javadoc on append()). replay() then leaves 5 live entries (q5..q9;
        // q0..q4 were dropped), and compact() re-appends each of those to the temporary file -- 5 more
        // appends, 5 more fsyncs -- before the atomic rename and one final fsync of the parent
        // directory (SensitiveFiles.syncDirectory). 15 + 5 + 1 = 21, and the last of those 21 is the
        // one this case is actually about: a directory fsync that STATE-039 found FileCheckpointStore
        // does NOT do for checkpoints.
        long fsyncs = trace.lines().filter(l -> l.contains("fsync")).count();
        assertThat(fsyncs).as(trace).isEqualTo(21);
    }

    private static List<String> readAll(Process process) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    void state093_nothingCallsCompactSoTheJournalGrowsForTheLifeOfTheDeployment(@TempDir Path dir) throws Exception {
        List<String> compactCallers = grep("\\.compact(", repoRoot()).stream()
                .filter(l -> l.contains("/src/main/"))
                .toList();
        assertThat(compactCallers).as("no shipped code calls compact()").isEmpty();

        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog views = new ViewCatalog();
        // 200 cycles rather than the case's 1000, for test speed; the point (monotonic growth, zero
        // live entries surviving replay) does not depend on the exact count.
        int cycles = 200;
        long sizeAfter100 = -1;
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            long previous = 0;
            for (int i = 0; i < cycles; i++) {
                registry.register("churn", "SELECT user_id, amount FROM txn", List.of(0), DANA);
                registry.drop("churn");
                long size = Files.size(journalFile);
                assertThat(size).as("cycle " + i).isGreaterThan(previous);
                previous = size;
                if (i == 99) {
                    sizeAfter100 = size;
                }
            }
        }
        assertThat(sizeAfter100).isGreaterThan(0);

        long start = System.nanoTime();
        List<RegistryJournal.Entry> live = new RegistryJournal(journalFile).replay();
        long replayMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(live)
                .as("the whole file is history: every register was matched by a drop")
                .isEmpty();

        String extrapolatedPer1000 = "measured " + sizeAfter100
                + " bytes for 100 register/drop cycles of a 5-char name and a 31-char SQL text -- "
                + "linear extrapolation to 1000 cycles is roughly 10x that";
        System.out.println("STATE-093: " + extrapolatedPer1000 + "; replay of " + (cycles * 2) + " records took "
                + replayMillis + "ms and returned 0 live entries");
    }

    private static List<String> grep(String pattern, Path root) throws Exception {
        Process process = new ProcessBuilder("grep", "-rn", pattern, "--include=*.java", ".")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        process.waitFor();
        return lines;
    }

    @Test
    void state094_anInterruptedCompactionLeavesACompleteJournalOldOrNewNeverAPartialOne(@TempDir Path dir)
            throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        // 50 entries, each with a 64 KiB SQL text, so the rewrite takes tens of milliseconds and a
        // concurrent reader can genuinely observe it in progress.
        String bigSql = "SELECT user_id, amount FROM txn WHERE amount > 0 " + "-- ".repeat(1) + "x".repeat(65536);
        for (int i = 0; i < 50; i++) {
            journal.recordRegistration("q" + i, bigSql, List.of(0), "dana", Retention.DEFAULT, List.of());
        }
        List<RegistryJournal.Entry> live = journal.replay();
        assertThat(live).hasSize(50);

        AtomicBoolean stop = new AtomicBoolean(false);
        List<Integer> observedSizes = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                int size = new RegistryJournal(journalFile).replay().size();
                observedSizes.add(size);
            }
        });
        reader.start();
        journal.compact(live);
        stop.set(true);
        reader.join(Duration_ofSeconds(10));

        assertThat(observedSizes)
                .as("every observation during the rewrite was the same 50 entries either way")
                .allMatch(size -> size == 50);

        // The kill-mid-compaction half of this case (a real JVM SIGKILL during compact(), then
        // confirming the pre-compaction journal survives and the .compacting temp file is left
        // behind) was not attempted this round: it needs an external process killed at a precise
        // moment inside a library call, not a clean seam this harness has. STATE-091 already
        // confirms an existing .compacting leftover is not read as the journal; that is the
        // observable half of this sub-case this round does establish.
    }

    private static long Duration_ofSeconds(long seconds) {
        return seconds * 1000L;
    }
}

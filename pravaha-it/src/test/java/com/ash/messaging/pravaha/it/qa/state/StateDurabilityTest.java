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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-036..042 -- permissions, atomicity, and what "durable" actually means.
 *
 * <p>{@code SensitiveFiles} exists because checkpoints hold serialised operator state, which is the
 * aggregated data itself, and a checkpoint file that was world-readable for the duration of one write
 * has been world-readable.
 */
@Timeout(180)
class StateDurabilityTest extends StateTestSupport {

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static boolean straceUsable() {
        try {
            Process p =
                    new ProcessBuilder("strace", "-V").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Runs {@code mainClass} in a fresh JVM under a given umask, and returns its stdout lines. */
    private static List<String> runUnderUmask(String mainClass, String umask, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add("-c");
        StringBuilder script = new StringBuilder("umask ")
                .append(umask)
                .append(" && exec \"")
                .append(javaBinary())
                .append("\" -cp \"")
                .append(classpath())
                .append("\" ")
                .append(mainClass);
        for (String a : args) {
            script.append(" \"").append(a).append('"');
        }
        command.add(script.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = readAll(process);
        int exit = process.waitFor();
        assertThat(exit)
                .as("runner exited cleanly; output:\n" + String.join("\n", lines))
                .isZero();
        return lines;
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
    void state036_aCheckpointFileIsMode0600(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(posix(tmp), "requires POSIX permissions");
        Path dir = tmp.resolve("ck");
        List<String> out = runUnderUmask(StatePermissionsRunner.class.getName(), "0022", dir.toString());
        String filePerm = out.stream()
                .filter(l -> l.startsWith("FILE:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FILE: line in:\n" + String.join("\n", out)))
                .substring("FILE:".length());
        assertThat(filePerm)
                .as("rw------- under umask 0022, not the unguarded rw-r--r--")
                .isEqualTo("rw-------");
    }

    @Test
    void state037_theCheckpointDirectoryIsMode0700(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(posix(tmp), "requires POSIX permissions");
        Path dir = tmp.resolve("ck");
        List<String> out = runUnderUmask(StatePermissionsRunner.class.getName(), "0022", dir.toString());
        String before = valueOf(out, "DIR-BEFORE:");
        String after = valueOf(out, "DIR-AFTER:");
        assertThat(before)
                .as("Files.createDirectories does not narrow: at the umask before the first store")
                .isEqualTo("rwxr-xr-x");
        assertThat(after)
                .as("narrowed to rwx------ by createOwnerOnly on the first store()")
                .isEqualTo("rwx------");
    }

    private static String valueOf(List<String> lines, String prefix) {
        return lines.stream()
                .filter(l -> l.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + prefix + " line in:\n" + String.join("\n", lines)))
                .substring(prefix.length());
    }

    @Test
    void state038_aCheckpointIsPublishedByRenameAndIsNeverReadableHalfWritten(@TempDir Path dir) throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        byte[] big = new byte[64 * 1024 * 1024];
        Checkpoint checkpoint = cpState(1, "lane-0", big);

        AtomicBoolean stop = new AtomicBoolean(false);
        List<Object[]> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                for (Long id : store.availableIds()) {
                    if (id == 1) {
                        var loaded = store.load(1);
                        observed.add(new Object[] {
                            loaded.isPresent(),
                            loaded.map(c -> c.operatorState().get("lane-0").length)
                                    .orElse(-1)
                        });
                    }
                }
            }
        });
        reader.start();
        store.store(checkpoint);
        stop.set(true);
        reader.join(java.time.Duration.ofSeconds(10).toMillis());

        for (Object[] o : observed) {
            boolean present = (boolean) o[0];
            int length = (int) o[1];
            if (present) {
                assertThat(length)
                        .as("if load(1) is present it must be complete")
                        .isEqualTo(64 * 1024 * 1024);
            }
        }
        assertThat(Files.exists(dir.resolve("checkpoint-1.tmp")))
                .as("the temp file is gone after store()")
                .isFalse();
    }

    @Test
    void state039_storeReturnsWithNoFsyncContraryToTheInterfacesContract(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(straceUsable(), "requires a usable strace (ptrace) in this environment");
        Path checkpointDir = tmp.resolve("ck");
        Path journalFile = tmp.resolve("registry.journal");
        Path traceFile = tmp.resolve("trace.log");

        List<String> javaCmd = List.of(
                javaBinary(),
                "-cp",
                classpath(),
                StraceDurabilityRunner.class.getName(),
                checkpointDir.toString(),
                journalFile.toString());
        List<String> command = new ArrayList<>();
        command.add("strace");
        command.add("-f");
        command.add("-e");
        command.add("trace=fsync,fdatasync,openat,rename,renameat,renameat2");
        command.add("-o");
        command.add(traceFile.toString());
        command.addAll(javaCmd);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> out = readAll(process);
        int exit = process.waitFor();
        assertThat(exit)
                .as("runner+strace exited cleanly:\n" + String.join("\n", out))
                .isZero();

        String trace = Files.readString(traceFile);
        long checkpointFsyncCount = trace.lines()
                .filter(l -> l.contains("fsync") || l.contains("fdatasync"))
                .count();
        assertThat(trace).contains("checkpoint-1.tmp");
        assertThat(trace.contains("renameat") || trace.contains("rename("))
                .as("a rename of some form published checkpoint-1.bin; trace:\n" + trace)
                .isTrue();
        // Exactly one fsync in the whole trace: RegistryJournal.append's channel.force(true). The
        // checkpoint store's store() contributes zero.
        assertThat(checkpointFsyncCount)
                .as("one fsync total, from the journal append -- none from the checkpoint store")
                .isEqualTo(1);
    }

    @Test
    void state040_aTruncatedCheckpointIsSkippedAndThePreviousOneIsUsed(@TempDir Path dir) throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cpState(1, "lane-0", new byte[256]));
        store.store(cpState(2, "lane-0", new byte[256]));
        store.store(cpState(3, "lane-0", new byte[256]));

        Path file3 = dir.resolve("checkpoint-3.bin");
        long fullLength = Files.size(file3);
        byte[] original = Files.readAllBytes(file3);

        // Arm (a): truncate 4 bytes -- removes the trailer's final MAGIC.
        Files.write(file3, java.util.Arrays.copyOf(original, (int) fullLength - 4));
        assertArm(store, dir);

        // Arm (b): truncate 12 bytes -- the whole trailer gone (EOFException path).
        Files.write(file3, java.util.Arrays.copyOf(original, (int) fullLength - 12));
        assertArm(store, dir);

        // Arm (c): truncate to 30 bytes -- fails inside the header.
        Files.write(file3, java.util.Arrays.copyOf(original, 30));
        assertArm(store, dir);
    }

    private static void assertArm(FileCheckpointStore store, Path dir) {
        assertThat(store.load(3)).isEmpty();
        assertThat(store.latest()).hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(2));
        assertThat(store.availableIds()).containsExactly(3L, 2L, 1L);
    }

    @Test
    void state041_aFileWhoseMagicIsWrongIsSkippedNotRead(@TempDir Path tmp) throws Exception {
        // Two independent arms (two directories): arm 1's corruption of checkpoint-2.bin must not
        // still be in effect when arm 2 asks what the newest *readable* id is.
        Path dir1 = tmp.resolve("arm1");
        FileCheckpointStore store1 = new FileCheckpointStore(dir1);
        store1.store(cpState(1, "lane-0", new byte[16]));
        store1.store(cpState(2, "lane-0", new byte[16]));
        try (var raf =
                new java.io.RandomAccessFile(dir1.resolve("checkpoint-2.bin").toFile(), "rw")) {
            raf.seek(0);
            raf.write(new byte[] {0, 0, 0, 0});
        }
        assertThat(store1.load(2)).isEmpty();
        assertThat(store1.latest()).hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(1));

        Path dir2 = tmp.resolve("arm2");
        FileCheckpointStore store2 = new FileCheckpointStore(dir2);
        store2.store(cpState(1, "lane-0", new byte[16]));
        store2.store(cpState(2, "lane-0", new byte[16]));
        byte[] random = new byte[4096];
        new Random(20260909L).nextBytes(random);
        Files.write(dir2.resolve("checkpoint-4.bin"), random);
        assertThat(store2.load(4)).isEmpty();
        assertThat(store2.latest())
                .as("the random file is id 4, is skipped, and 2 is the next highest -- readable -- id")
                .hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(2));
    }

    @Test
    void state042_aCheckpointFromADifferentFormatVersionThrowsOutOfLatestInsteadOfBeingSkipped(@TempDir Path dir)
            throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cpState(1, "lane-0", new byte[16]));
        store.store(cpState(2, "lane-0", new byte[16]));

        // Patch bytes 4..7 (the FORMAT_VERSION int) of checkpoint-2.bin from 1 to 2.
        try (var raf =
                new java.io.RandomAccessFile(dir.resolve("checkpoint-2.bin").toFile(), "rw")) {
            raf.seek(4);
            raf.writeInt(2);
            // CKPTSUM-1: a checkpoint a newer engine wrote carries a checksum that matches it, so the
            // patched file is given one -- otherwise it is skipped as damaged before its version is read.
            long body = raf.length() - 12;
            byte[] bytes = new byte[(int) body];
            raf.seek(0);
            raf.readFully(bytes);
            java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
            crc.update(bytes);
            raf.seek(body + 4);
            raf.writeLong(crc.getValue());
        }

        // E-1. This refusal was an uncoded IllegalStateException until PRV-4002 STATE_UNREADABLE was
        // given a throw site -- the code had been declared and unreachable, so the one failure it
        // existed to describe was reported as a bare runtime exception. The diagnosis itself was
        // always good and is unchanged; what is new is that it now carries the code a caller can
        // branch on and an operator can look up.
        assertThatThrownBy(() -> store.load(2))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-4002")
                .hasMessageContaining(
                        "checkpoint 2 is format version 2 and this engine reads 1. Refusing to guess at the "
                                + "difference.");
        assertThat(store.load(1)).isPresent();
        assertThatThrownBy(store::latest)
                .as("latest() walks highest-id-first and never reaches the readable id 1")
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-4002");

        int removed = store.prune(1);
        assertThat(removed).isEqualTo(1);
        assertThatThrownBy(store::latest)
                .as("id 2's file (still format-2) is now the only one left, and still throws")
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-4002");
    }
}

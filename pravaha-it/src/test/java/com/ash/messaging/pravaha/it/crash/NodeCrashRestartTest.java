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
package com.ash.messaging.pravaha.it.crash;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node that is killed, not stopped, and what happens when it comes back.
 *
 * <p>Gate P4 asks for "kill a node mid-checkpoint; exact recovery". Gate P7 asks for "a killed node
 * restarts onto its own state and nothing else's". Both were recorded as <em>not demonstrated</em>
 * in their gate packs for the same reason: every recovery and ownership test in this repository
 * interrupts a run <em>in-process</em>, which runs the code on the way down. {@code SIGKILL} runs
 * none of it, and that is the entire point — a marker left by a graceful stop is deleted, and a
 * marker left by a kill is not.
 *
 * <p>So this spawns a real JVM, kills it with {@code SIGKILL}, and starts another with the same node
 * id against the same directory. Linux only; skipped elsewhere, because {@code destroyForcibly} on a
 * platform without signals does not test the thing being claimed.
 */
@Timeout(180)
class NodeCrashRestartTest {

    private record Child(Process process, long pid, List<String> output) {}

    @Test
    void aKilledNodeCanClaimItsOwnStateAgainImmediately(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));

        Child first = start(state, "node-a");
        assertThat(first.output())
                .as("the first node claimed the directory")
                .anySatisfy(line -> assertThat(line).startsWith("CLAIMED"));

        kill(first);

        // The marker is still there, and its lease has seconds left to run. This is precisely the
        // state a crash leaves behind, and it is the state no previous test could produce.
        assertThat(state.resolve(".pravaha-owner"))
                .as("a killed process deletes nothing; the marker outlives it")
                .exists();

        Child second = start(state, "node-a");

        assertThat(second.output())
                .as("a node restarting onto its own state after a crash must not be told that a second "
                        + "instance of itself is running. The lease says the claim is live because the lease "
                        + "cannot know the process is gone -- but the marker records the pid, and on the same "
                        + "host that is checkable. This is Gate P7's criterion, stated as a test")
                .anySatisfy(line -> assertThat(line).startsWith("CLAIMED"));
        kill(second);
    }

    @Test
    void anotherNodeIsStillRefusedTheDirectory(@TempDir Path dir) throws Exception {
        // The property the fix must not cost. Reclaiming after a crash is about *this* node's own
        // state; taking another node's remains the corruption being prevented, and a dead pid does
        // not make somebody else's directory yours.
        Path state = Files.createDirectories(dir.resolve("state"));

        Child owner = start(state, "node-a");
        assertThat(owner.output()).anySatisfy(line -> assertThat(line).startsWith("CLAIMED"));
        kill(owner);

        Child intruder = start(state, "node-b");

        assertThat(intruder.output())
                .as("node-b must still be refused node-a's state, crashed or not")
                .anySatisfy(line -> assertThat(line).startsWith("REFUSED"));
        assertThat(String.join(" ", intruder.output())).contains("PRV-4003");
    }

    @Test
    void aSecondLiveInstanceOfTheSameNodeIsStillRefused(@TempDir Path dir) throws Exception {
        // The other property the fix must not cost, and the reason the lease exists at all. Two
        // live processes with one node id is the split brain the marker is there to prevent, and a
        // pid check must not weaken it -- the first process is still running, so its pid is alive.
        Path state = Files.createDirectories(dir.resolve("state"));

        Child running = start(state, "node-a");
        assertThat(running.output()).anySatisfy(line -> assertThat(line).startsWith("CLAIMED"));

        Child sameId = start(state, "node-a");

        assertThat(sameId.output())
                .as("the first instance is alive, so the second must be refused")
                .anySatisfy(line -> assertThat(line).startsWith("REFUSED"));
        kill(running);
    }

    /** Starts a child JVM and reads its first line of output. */
    private static Child start(Path state, String nodeId) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                CrashNodeMain.class.getName(),
                state.toString(),
                nodeId,
                "19090"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> output = new ArrayList<>();
        // Read until the child's *verdict* line appears, not merely until it says anything. The
        // reclaim path logs an INFO line through java.util.logging first, and taking the first line
        // of output made a successful claim look like a failure -- a harness bug that reads exactly
        // like the product bug it was written to catch, which is worth the extra few lines here.
        // Reading to EOF would block for ever on the process that claims successfully.
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    output.add(line);
                    if (line.startsWith("CLAIMED") || line.startsWith("REFUSED") || line.startsWith("FAILED")) {
                        break;
                    }
                }
            }
            if (!process.isAlive() && !reader.ready()) {
                break;
            }
            Thread.sleep(20L);
        }
        return new Child(process, process.pid(), output);
    }

    private static void kill(Child child) throws Exception {
        // destroyForcibly is SIGKILL on Linux: no shutdown hook, no finally, no marker deletion.
        child.process().destroyForcibly();
        child.process().waitFor(30, TimeUnit.SECONDS);
        assertThat(child.process().isAlive()).as("the child is actually gone").isFalse();
    }
}

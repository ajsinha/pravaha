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
package com.ash.messaging.pravaha.it.qa.errc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.cli.PravahaCli;

/**
 * What every ERRC case needs: a CLI runner that is the real product surface ({@code PravahaCli}
 * driven exactly as {@code PravahaCliTest} drives it -- captured output streams and an exit code, not
 * the throwing Java method called directly) and a scratch directory per test for the configuration
 * files a case's Setup describes.
 *
 * <p>{@code $QA} from the case file's Standing Setup is {@code @TempDir root} here: JUnit gives each
 * test method a fresh directory and removes it afterwards, which is a stronger isolation guarantee
 * than a fixed path shared across a whole round would be, and the case file itself only names {@code
 * $QA} as "the scratchpad", not a specific fixed path every case must share.
 */
abstract class ErrcTestSupport {

    @TempDir
    Path qa;

    /** Runs the CLI with {@code args} and returns the captured result -- stdout, stderr, exit code. */
    static CliResult cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new PravahaCli(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args);
        return new CliResult(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    record CliResult(int exitCode, String stdout, String stderr) {
        /** stdout and stderr concatenated, for assertions that do not care which stream. */
        String combined() {
            return stdout + stderr;
        }
    }

    /**
     * Runs the CLI's own shaded, executable jar as a genuinely separate OS process -- {@code java
     * -jar pravaha-cli-*-cli.jar args...}, exactly how {@code bin/pravaha} would.
     *
     * <p>Needed rather than {@link #cli}'s in-process call whenever a case actually constructs an
     * Arrow {@code FlightClient} (i.e. any {@code --url} call that gets as far as {@code connect()}):
     * {@code pravaha-it}'s own reactor dependency graph pulls in {@code io.netty:netty-buffer:4.1.135}
     * transitively through {@code pravaha-server} (test scope) alongside the {@code 4.2.9} line Arrow
     * Flight needs, and mixing them throws {@code AbstractMethodError} the moment
     * {@code org.apache.arrow.flight.ArrowMessage}'s static initialiser runs -- a real, pre-existing
     * classpath defect in this test module's dependency graph, not in the product. The CLI's own
     * shaded jar has no such conflict (confirmed: {@code pravaha-cli}'s dependency tree is netty 4.2.9
     * throughout), and running it as a subprocess is arguably the more faithful product surface
     * anyway -- a real process, a real classpath, the one an operator actually runs.
     */
    static CliResult cliSubprocess(Duration timeout, String... args) throws IOException, InterruptedException {
        Path jar = cliJar();
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String out;
        String err;
        try (var outStream = process.getInputStream();
                var errStream = process.getErrorStream()) {
            // Both streams are drained concurrently with waitFor, or a process whose stderr fills its
            // pipe buffer before stdout is read would deadlock -- exactly the kind of harness bug that
            // would silently turn every subprocess case into a false BLOCKED.
            var outFuture = outStream.readAllBytes();
            var errFuture = errStream.readAllBytes();
            out = new String(outFuture, StandardCharsets.UTF_8);
            err = new String(errFuture, StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("cli subprocess did not exit within " + timeout);
        }
        return new CliResult(process.exitValue(), out, err);
    }

    private static Path cliJar() throws IOException {
        Path root = repoRoot();
        try (var files = Files.list(root.resolve("pravaha-cli/target"))) {
            return files.filter(p -> p.getFileName().toString().endsWith("-cli.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no *-cli.jar under pravaha-cli/target; run 'mvnw -pl pravaha-cli package' first"));
        }
    }

    /** Same technique as {@code ErrorCodeUniquenessTest.repoRoot()}: walk up to the directory with {@code docs/adr}. */
    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException(
                    "could not locate the repository root from " + Path.of("").toAbsolutePath());
        }
        return path;
    }

    /** Writes {@code content} to {@code qa/name}, creating parent directories, and returns the path. */
    Path file(String name, String content) {
        try {
            Path path = qa.resolve(name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

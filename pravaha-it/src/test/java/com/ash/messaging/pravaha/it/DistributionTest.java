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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The quickstart names binaries; this checks the build produces them.
 *
 * <p>{@code docs/QUICKSTART.md} has told readers to run {@code pravaha-server} and {@code pravaha}
 * since the first version of it, and for just as long the build produced plain jars with no manifest
 * main class and no dependencies inside. There was no way to start this software except to assemble
 * a classpath by hand -- which is the difference between something that can be installed and
 * something that can be compiled, and it meant QA could not begin at all.
 *
 * <p>{@code ExamplesTest} covers the CLI commands the quickstart runs, in-process. It could not
 * cover these, because a command invoked through {@code PravahaCli.run} never touches packaging:
 * the class is on the test classpath either way. What is checked here is the launch path itself.
 */
class DistributionTest {

    @Test
    void theBuildProducesAnExecutableCliJarThatRuns() throws Exception {
        Path jar = artifact("pravaha-cli", "-cli.jar");
        assumeTrue(jar != null, "the CLI jar is built by `package`; this run skipped it");

        // `version` and not `--help`: it is the one command that touches no engine state, so a
        // failure here is packaging and nothing else.
        Launch launch = launch(List.of(java(), "-jar", jar.toString(), "version"));
        assertThat(launch.exitCode())
                .as("`java -jar %s version` failed: %s", jar.getFileName(), launch.output())
                .isZero();
        assertThat(launch.output()).contains("pravaha");
    }

    @Test
    void theBuildProducesAnExecutableServerJar() throws Exception {
        Path jar = artifact("pravaha-server", "-app.jar");
        assumeTrue(jar != null, "the server jar is built by `package`; this run skipped it");

        // A repackaged Spring Boot jar carries its dependencies under BOOT-INF and a launcher as
        // its main class. Checking the manifest rather than starting the server keeps this test
        // about packaging: booting is what ServerSecurityTest and the node tests already do.
        try (java.util.jar.JarFile opened = new java.util.jar.JarFile(jar.toFile())) {
            String mainClass = opened.getManifest().getMainAttributes().getValue("Main-Class");
            assertThat(mainClass)
                    .as("the server jar has no Main-Class, so `java -jar` cannot start it")
                    .isNotNull();
            assertThat(opened.getEntry("BOOT-INF/classes/com/ash/messaging/pravaha/server/PravahaNode.class"))
                    .as("the server jar does not contain the server")
                    .isNotNull();
        }
    }

    @Test
    void theLaunchersExistAndAreExecutable() {
        for (String script : List.of("pravaha", "pravaha-server")) {
            Path launcher = repoRoot().resolve("bin").resolve(script);
            assertThat(launcher)
                    .as("the quickstart tells a reader to run '%s'", script)
                    .exists();
            assertThat(launcher.toFile().canExecute())
                    .as("bin/%s is not executable, so the mode bit did not survive the commit", script)
                    .isTrue();
        }
    }

    @Test
    void everyBinaryTheQuickstartNamesIsOneTheBuildProduces() throws IOException {
        // The rot this guards is specific and already happened once: the quickstart named
        // pravaha-server for months while nothing built it. A command in the documentation is a
        // promise, and this is the test that keeps it checkable.
        String quickstart = Files.readString(repoRoot().resolve("docs/QUICKSTART.md"), StandardCharsets.UTF_8);
        for (String binary : List.of("pravaha-server", "pravaha ")) {
            assertThat(quickstart).contains(binary);
        }
        assertThat(repoRoot().resolve("bin/pravaha")).exists();
        assertThat(repoRoot().resolve("bin/pravaha-server")).exists();
        assertThat(repoRoot().resolve("Dockerfile"))
                .as("the quickstart offers a container build")
                .exists();
    }

    // ------------------------------------------------------------------ harness

    private record Launch(int exitCode, String output) {}

    private static Launch launch(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES))
                .as("the launch did not finish")
                .isTrue();
        return new Launch(process.exitValue(), output);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** The built artifact, or null when this run has not packaged it. */
    private static Path artifact(String module, String suffix) throws IOException {
        Path target = repoRoot().resolve(module).resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> jars = Files.list(target)) {
            return jars.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("not inside a git working tree");
        }
        return p;
    }
}

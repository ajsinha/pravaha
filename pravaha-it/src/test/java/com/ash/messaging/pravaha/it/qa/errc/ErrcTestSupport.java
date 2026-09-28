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

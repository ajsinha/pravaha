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
package com.ash.messaging.pravaha.cli.qa.errc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.ash.messaging.pravaha.cli.PravahaCli;

/**
 * What every server-backed ERRC case in this package needs: a CLI runner identical to {@code
 * CliAgainstServerTest}'s own (captured stdout/stderr, exit code) so evidence is comparable across
 * both, plus small conveniences. Server/registry construction is left to each test class's own
 * {@code @BeforeEach}, since the cases in this range need different security/TLS/admission
 * combinations and a shared fixture would force the least common denominator.
 */
abstract class ErrcServerSupport {

    static CliResult cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = new PravahaCli(outStream, errStream).run(args);
        }
        return new CliResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    record CliResult(int code, String out, String err) {
        String combined() {
            return out + err;
        }
    }
}

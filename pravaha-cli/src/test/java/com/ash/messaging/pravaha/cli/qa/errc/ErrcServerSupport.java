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

import com.ash.messaging.pravaha.cli.SdkVerbs;

/**
 * What every server-backed ERRC case in this package needs: a runner for the server verbs (captured
 * stdout/stderr, exit code) so evidence stays comparable across cases, plus small conveniences.
 *
 * <p>These cases once drove the Java CLI's remote commands. Those moved to the Python CLI, and the
 * cases were always about the server's answers rather than the CLI's formatting, so they now go
 * through the Java SDK ({@link SdkVerbs}) with the same call shape. Server/registry construction is
 * left to each test class's own {@code @BeforeEach}, since the cases in this range need different
 * security/TLS/admission combinations and a shared fixture would force the least common denominator.
 */
abstract class ErrcServerSupport {

    static CliResult cli(String... args) {
        SdkVerbs.Result result = SdkVerbs.run(args);
        return new CliResult(result.code(), result.out(), result.err());
    }

    record CliResult(int code, String out, String err) {
        String combined() {
            return out + err;
        }
    }
}

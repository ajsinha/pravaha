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
package com.ash.messaging.pravaha.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs a query over a file and writes the results to another.
 *
 * <p>Reports plan and execution time separately, because they answer different questions: planning
 * is paid once at registration and execution is paid per record (design section 6.3), and an operator
 * looking at a slow run needs to know which half is slow.
 */
final class RunCommand {

    private final PrintStream out;
    private final PrintStream err;

    RunCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int run(List<String> arguments) {
        Args args = Args.parse(arguments);
        String deadLetterFile = args.get("dlq", "");
        QueryRunner.Result result = QueryRunner.run(
                args.require("sql"),
                args.get("stream", "txn"),
                args.require("schema"),
                args.require("in"),
                args.require("out-schema"),
                args.require("out"),
                1,
                deadLetterFile.isBlank() ? null : Path.of(deadLetterFile));

        out.println(Ansi.good("ok") + "  " + result.rowsRead() + " in, " + result.rowsWritten() + " out");
        if (result.rowsRejected() > 0) {
            // On stdout next to the counts it belongs with, and never silently: a run that reports
            // "ok" while having discarded input is the failure a dead-letter queue exists to make
            // visible, not one it is allowed to create.
            out.println(Ansi.bad("  " + result.rowsRejected() + " rejected") + Ansi.dim(" -> " + deadLetterFile));
        }
        if (result.deadLetterFailures() > 0) {
            err.println(Ansi.bad("  " + result.deadLetterFailures()
                    + " rejected records could not be written to " + deadLetterFile
                    + "; that many are gone with no record of them"));
        }
        out.println(Ansi.dim("  plan " + result.planMicros() + " us, execute " + result.executeMicros() + " us"));
        return PravahaCli.EXIT_OK;
    }
}

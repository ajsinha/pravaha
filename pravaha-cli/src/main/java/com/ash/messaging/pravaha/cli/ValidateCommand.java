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
import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * Parses, validates and plans without running anything.
 *
 * <p>Design section 24.1 targets validation in under 50 ms, because this is what the console's editor
 * calls on every keystroke burst and what a CI job runs over a repository of queries. It is also
 * the cheapest way to find out that a query will be refused for unbounded state before deploying
 * it anywhere.
 */
final class ValidateCommand {

    private final PrintStream out;
    private final PrintStream err;

    ValidateCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int run(List<String> arguments) {
        Args args = Args.parse(arguments);
        String sql = args.require("sql");
        String streamName = args.get("stream", "txn");
        StreamSchema schema = FilesystemSourcePlugin.parseSchema(streamName, args.require("schema"));

        long startNanos = System.nanoTime();
        PhysicalOperator plan;
        try {
            plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(schema).plan(sql));
        } catch (com.ash.messaging.pravaha.api.PravahaException e) {
            // SX-19: the planner withholds the declared stream names, correctly, because on a
            // server it cannot tell who is asking. Here the caller typed them.
            throw PravahaCli.namingTheStreamYouGaveIt(e, streamName);
        }
        long elapsedMicros = (System.nanoTime() - startNanos) / 1_000L;

        out.println(Ansi.good("valid") + "  " + Ansi.dim(elapsedMicros + " us"));
        out.println(Ansi.dim("  output: ")
                + plan.outputSchema().fields().stream()
                        .map(f -> f.name() + " " + f.type().sqlName())
                        .toList());
        return PravahaCli.EXIT_OK;
    }
}

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
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * Shows the plan.
 *
 * <p>Two levels, because they answer different questions. {@code logical} is Calcite's optimised
 * tree and tells you what the optimiser decided; {@code physical} is what the engine will actually
 * execute, including which predicates were compiled and which stayed residual. Showing only one
 * leaves an operator guessing which layer a surprise came from.
 */
final class ExplainCommand {

    private final PrintStream out;
    private final PrintStream err;

    ExplainCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int run(List<String> arguments) {
        Args args = Args.parse(arguments);
        String sql = args.require("sql");
        String level = args.get("level", "physical");
        StreamSchema schema = FilesystemSourcePlugin.parseSchema(args.get("stream", "txn"), args.require("schema"));
        SqlPlanner planner = SqlPlanner.withStreams(schema);

        switch (level) {
            case "logical" -> {
                out.println(Ansi.bold("Logical plan"));
                out.print(planner.explain(sql));
            }
            case "physical" -> {
                out.println(Ansi.bold("Physical plan"));
                out.print(PhysicalPlanBuilder.explain(new PhysicalPlanBuilder().build(planner.plan(sql))));
            }
            case "all" -> {
                out.println(Ansi.bold("Logical plan"));
                out.print(planner.explain(sql));
                out.println();
                out.println(Ansi.bold("Physical plan"));
                out.print(PhysicalPlanBuilder.explain(new PhysicalPlanBuilder().build(planner.plan(sql))));
            }
            default -> throw new Args.UsageException("--level must be logical, physical or all; got '" + level + "'");
        }
        return PravahaCli.EXIT_OK;
    }
}

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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.codegen.FilterProjectGenerator;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * Shows the plan.
 *
 * <p>Three levels, because they answer different questions. {@code logical} is Calcite's optimised
 * tree and tells you what the optimiser decided; {@code physical} is what the engine will actually
 * execute, including which predicates were compiled and which stayed residual; {@code codegen} is
 * the Java the engine generates for it. Showing only one leaves an operator guessing which layer a
 * surprise came from.
 *
 * <p>The codegen level exists because a generated plan that produces a wrong answer is otherwise
 * undebuggable from outside: there is no source file to open, no line to breakpoint, and the class
 * name in the stack trace belongs to something that was compiled from a string. Printing it -- with
 * line numbers, since a compiler error citing line 47 is useless without them -- is the difference
 * between a bug report and a guess (design section 12.4).
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
        String streamName = args.get("stream", "txn");
        StreamSchema schema = FilesystemSourcePlugin.parseSchema(streamName, args.require("schema"));
        SqlPlanner planner = SqlPlanner.withStreams(schema);

        try {
            return explain(planner, sql, level);
        } catch (PravahaException e) {
            // SX-19, as in ValidateCommand: the name the planner withholds is the one this caller
            // typed on the same command line.
            throw PravahaCli.namingTheStreamYouGaveIt(e, streamName);
        }
    }

    private int explain(SqlPlanner planner, String sql, String level) {
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
            case "codegen" -> {
                out.println(Ansi.bold("Generated source"));
                out.print(codegen(planner, sql));
            }
            default ->
                throw new Args.UsageException("--level must be logical, physical, codegen or all; got '" + level + "'");
        }
        return PravahaCli.EXIT_OK;
    }

    /**
     * Generates the fused stage and renders it, or explains why there is nothing to render.
     *
     * <p>A plan the generator does not cover is not an error here. The interpreted path runs it
     * correctly and that is the design's guarantee (section 12.4); what an operator needs to know is
     * that this query will take the slower path and why, which is a diagnostic rather than a
     * failure.
     */
    private String codegen(SqlPlanner planner, String sql) {
        PhysicalOperator plan = new PhysicalPlanBuilder().build(planner.plan(sql));
        try {
            FilterProjectGenerator.Fused fused = new FilterProjectGenerator().generate(plan, "ExplainStage");
            StringBuilder rendered = new StringBuilder();
            String[] lines = fused.source().split("\n");
            for (int i = 0; i < lines.length; i++) {
                rendered.append(String.format("%4d  %s%n", i + 1, lines[i]));
            }
            return rendered.toString();
        } catch (PravahaException e) {
            return "This query has no generated form: " + e.getMessage() + System.lineSeparator()
                    + "It will run on the interpreted path, which is correct and slower." + System.lineSeparator();
        }
    }
}

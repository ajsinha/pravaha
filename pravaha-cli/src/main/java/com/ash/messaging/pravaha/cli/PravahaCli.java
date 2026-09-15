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
import java.util.Arrays;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The {@code pravaha} command.
 *
 * <p>Its reason for existing is design section 24.1: Flink's most-cited weakness is not throughput but
 * that the loop from idea to first output takes minutes and a cluster. Compressing that to seconds
 * is a competitive lever, and it has to be designed in rather than bolted on -- which is why the CLI
 * is a Wave 2 deliverable and not a Wave 8 one.
 *
 * <p>Exit codes follow the usual convention: {@code 0} success, {@code 1} the command ran and
 * reported a problem, {@code 2} the command line itself was wrong. Scripts distinguish those, and
 * collapsing them makes the CLI unusable in a pipeline.
 */
public final class PravahaCli {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    private final PrintStream out;
    private final PrintStream err;

    public PravahaCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new PravahaCli(System.out, System.err).run(args));
    }

    /** Runs a command and returns the exit code, rather than calling {@code System.exit}, so it is testable. */
    public int run(String... args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return args.length == 0 ? EXIT_USAGE : EXIT_OK;
        }

        String command = args[0];
        List<String> rest = Arrays.asList(args).subList(1, args.length);

        try {
            return switch (command) {
                case "validate" -> new ValidateCommand(out, err).run(rest);
                case "explain" -> new ExplainCommand(out, err).run(rest);
                case "run" -> new RunCommand(out, err).run(rest);
                // Everything below talks to a running server, through the published SDK rather
                // than reaching into the engine -- so the CLI is the client API's first consumer
                // and its awkward corners show up here before a customer finds them.
                case "query" -> new ServerCommand(out, err).query(rest);
                case "register" -> new ServerCommand(out, err).register(rest);
                case "queries" -> new ServerCommand(out, err).queries(rest);
                case "drop", "pause", "resume" -> new ServerCommand(out, err).lifecycle(command, rest);
                case "subscribe" -> new ServerCommand(out, err).subscribe(rest);
                case "version" -> {
                    out.println("pravaha " + version());
                    yield EXIT_OK;
                }
                default -> {
                    err.println(Ansi.bad("unknown command: " + command));
                    printUsage();
                    yield EXIT_USAGE;
                }
            };
        } catch (Args.UsageException e) {
            // A command-line mistake, not a query problem. Scripts distinguish these.
            err.println(Ansi.bad(e.getMessage()));
            return EXIT_USAGE;
        } catch (PravahaException e) {
            // Engine errors already carry a PRV code and an actionable message; a stack trace here
            // would bury the useful part.
            err.println(Ansi.bad(e.getMessage()));
            err.println(Ansi.dim("  " + e.helpUrl()));
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println(Ansi.bad(e.getClass().getSimpleName() + ": " + e.getMessage()));
            return EXIT_FAILED;
        }
    }

    private static boolean isHelp(String arg) {
        return "help".equals(arg) || "-h".equals(arg) || "--help".equals(arg);
    }

    static String version() {
        String implementation = PravahaCli.class.getPackage().getImplementationVersion();
        return implementation == null ? "0.1.0-SNAPSHOT" : implementation;
    }

    private void printUsage() {
        out.println(Ansi.bold("pravaha") + " " + Ansi.dim(version()) + "  " + Ansi.accent("Ask once. Answer always."));
        out.println();
        out.println(Ansi.bold("Usage:") + "  pravaha <command> [options]");
        out.println();
        out.println(Ansi.bold("Commands:"));
        out.println("  validate  --sql <query> --schema <spec> [--stream <name>]");
        out.println("            Parse, validate and plan without running anything.");
        out.println();
        out.println("  query     --sql <query> [--params a,b] [--url grpc://host:9090] [--token t]");
        out.println("            Ask a running server a question and print the rows.");
        out.println();
        out.println("  register  --name <view> --sql-file <path> [--keys 0,1] [--url ...]");
        out.println("            Register a continuous query. It runs until it is dropped.");
        out.println();
        out.println("  queries   [--url ...]");
        out.println("            List the continuous queries a server is running.");
        out.println();
        out.println("  subscribe --view <name> [--filter col=val,col2=val2] [--limit N] [--url ...]");
        out.println("            Stream changes as they are committed. One blank-lined group per commit.");
        out.println();
        out.println("  pause | resume | drop   --name <view> [--url ...]");
        out.println("            Lifecycle. A computation is released when its last name is dropped.");
        out.println();
        out.println("  explain   --sql <query> --schema <spec> [--level logical|physical|codegen|all]");
        out.println("            Show the plan the engine would execute.");
        out.println();
        out.println("  run       --sql <query> --schema <spec> --in <file>");
        out.println("            --out <file> --out-schema <spec> [--dlq <file>]");
        out.println("            Run a query over a delimited file.");
        out.println();
        out.println("  version");
        out.println();
        out.println(Ansi.bold("Schema spec:") + "  name:TYPE,name:TYPE   (suffix a type with ? for nullable)");
        out.println(Ansi.dim("  BOOLEAN INT8 INT16 INT32 INT64 FLOAT32 FLOAT64 STRING BYTES TIMESTAMP"));
        out.println();
        out.println(Ansi.bold("Example:"));
        out.println(Ansi.dim("  pravaha run --sql \"SELECT user_id FROM txn WHERE amount > 100\" \\"));
        out.println(Ansi.dim("    --schema 'txn_id:INT64,user_id:STRING,amount:INT64' \\"));
        out.println(Ansi.dim("    --in txn.csv --out big.csv --out-schema 'user_id:STRING'"));
    }
}

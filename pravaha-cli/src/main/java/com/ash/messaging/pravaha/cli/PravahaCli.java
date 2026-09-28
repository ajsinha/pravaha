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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.HelpUrls;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The {@code pravaha-engine} command: the commands that need the engine in-process.
 *
 * <p>Its reason for existing is design section 24.1: Flink's most-cited weakness is not throughput but
 * that the loop from idea to first output takes minutes and a cluster. Compressing that to seconds
 * is a competitive lever, and it has to be designed in rather than bolted on. So {@code validate},
 * {@code explain} and {@code run} plan and execute here, in this JVM, with no server at all.
 *
 * <p>Everything that talks to a running engine -- query, register, subscribe, the lifecycle and
 * replacement verbs, dead letters, identity, lanes, the debugger -- belongs to the Python CLI,
 * {@code pravaha}, which speaks the same REST and Flight surfaces the SDKs do. Typing one of those
 * words here says where it went rather than "unknown command", because the documentation sent
 * readers to this binary for them for a long time.
 *
 * <p>Exit codes follow the usual convention: {@code 0} success, {@code 1} the command ran and
 * reported a problem, {@code 2} the command line itself was wrong. Scripts distinguish those, and
 * collapsing them makes the CLI unusable in a pipeline.
 */
public final class PravahaCli {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    /** The program's name, as the launcher installs it and as every message here spells it. */
    static final String PROGRAM = "pravaha-engine";

    /**
     * The commands that moved to the Python CLI when this one stopped talking to servers. Each is
     * answered with where it went, and exit 2: the command line was wrong for this binary.
     */
    static final Set<String> MOVED = Set.of(
            "query",
            "register",
            "queries",
            "drop",
            "pause",
            "resume",
            "replace",
            "cutover",
            "rollback",
            "abandon",
            "finish",
            "throttle",
            "pause-backfill",
            "resume-backfill",
            "replacements",
            "subscribe",
            "dlq",
            "login",
            "password",
            "user",
            "key",
            "session",
            "lanes",
            "debug");

    private final PrintStream out;
    private final PrintStream err;

    public PravahaCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        // DOCX-21. The CLI has no configuration file, so the help-page base is an environment
        // variable, and a bad one is refused here rather than pasted onto a code. `run` is left
        // out of this deliberately: it is the testable entry point, and a unit test must not
        // depend on the environment of the machine running it.
        try {
            HelpUrls.configureFromEnvironment();
        } catch (PravahaException e) {
            System.err.println(e.getMessage());
            System.exit(EXIT_USAGE);
            return;
        }
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

        // Before --help, so `pravaha-engine queries --help` says where queries went rather than
        // "unknown command".
        if (MOVED.contains(command)) {
            err.println(movedText(command));
            return EXIT_USAGE;
        }

        // P-4: --help was parsed as an ordinary flag, so a command answered "missing required
        // option" instead of listing its own flags. They have to be discoverable from the binary.
        if (rest.stream().anyMatch(PravahaCli::isHelp)) {
            List<String> help = HELP.get(command);
            if (help == null) {
                err.println(Ansi.bad("unknown command: " + command));
                printUsage();
                return EXIT_USAGE;
            }
            out.println(Ansi.bold("Usage:"));
            help.forEach(out::println);
            return EXIT_OK;
        }

        try {
            return switch (command) {
                case "validate" -> new ValidateCommand(out, err).run(rest);
                case "explain" -> new ExplainCommand(out, err).run(rest);
                case "run" -> new RunCommand(out, err).run(rest);
                case "version" -> {
                    out.println(PROGRAM + " " + version());
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
            err.println(Ansi.dim("  " + HelpUrls.helpLine(e.errorCode().code())));
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println(Ansi.bad(e.getClass().getSimpleName() + ": " + e.getMessage()));
            return EXIT_FAILED;
        }
    }

    /** Where a command that left this binary went, in one line. */
    static String movedText(String command) {
        return "'" + command + "' is in the Python CLI now: pip install the Pravaha Python SDK and run `pravaha "
                + command + "`";
    }

    private static boolean isHelp(String arg) {
        return "help".equals(arg) || "-h".equals(arg) || "--help".equals(arg);
    }

    /**
     * The marker on the planner's deliberately incomplete "object not found" (SX-19).
     *
     * <p>Matched on the sentence rather than the code, because {@code PRV-2002} covers every
     * validation failure and only this one withholds anything.
     */
    private static final String WITHHELD = ". This server has ";

    /**
     * Puts the caller's own stream name back on an offline refusal (SX-19).
     *
     * <p>{@code SqlPlanner} stopped listing the declared streams on "object not found", and it was
     * right to: the planner runs before authorization can, so on a server that list is a catalogue
     * dump to a caller who may be entitled to nothing. It cannot tell one caller from another, so
     * the suppression is blanket -- and on {@code validate}, {@code explain} and {@code run} the
     * name it is withholding is the one the user typed on the same command line seconds earlier.
     * There it protects nothing and removes the one hint that ends a typo, and it sends the reader
     * to "the listing call" on a command that never contacts a server.
     *
     * <p>So the CLI puts it back. It owns the schema it passed in, which is exactly the case the
     * planner cannot reason about.
     */
    static PravahaException namingTheStreamYouGaveIt(PravahaException e, String streamName) {
        String message = e.getMessage();
        if (!"PRV-2002".equals(e.errorCode().code()) || message == null || !message.contains(WITHHELD)) {
            return e;
        }
        return new PravahaException(
                e.errorCode(),
                message.substring(0, message.indexOf(WITHHELD)) + ". This command plans against one stream, the "
                        + "one you named: '" + streamName + "' (--stream, default txn), with the columns "
                        + "in --schema.",
                e);
    }

    static String version() {
        String implementation = PravahaCli.class.getPackage().getImplementationVersion();
        return implementation == null ? "0.1.0-SNAPSHOT" : implementation;
    }

    /**
     * Each command's own usage block, keyed by the word that invokes it.
     *
     * <p>One table rather than two: {@code pravaha-engine --help} prints every block and {@code
     * pravaha-engine <command> --help} prints one of them, so a command cannot be documented at the
     * top level and undiscoverable from itself (P-4).
     */
    private static final Map<String, List<String>> HELP = help();

    private static Map<String, List<String>> help() {
        Map<String, List<String>> commands = new LinkedHashMap<>();
        commands.put(
                "validate",
                List.of(
                        "  validate  --sql <query> --schema <spec> [--stream <name>] [--event-time <column>]",
                        "            Parse, validate and plan without running anything.",
                        "            --event-time marks the stream's event-time column; a windowed",
                        "            query over a stream without one is refused, as a node refuses it."));
        commands.put(
                "explain",
                List.of(
                        "  explain   --sql <query> --schema <spec> [--level logical|physical|codegen|all]",
                        "            [--event-time <column>]",
                        "            Show the plan the engine would execute."));
        commands.put(
                "run",
                List.of(
                        "  run       --sql <query> --schema <spec> --in <file>",
                        "            --out <file> --out-schema <spec> [--dlq <file>] [--event-time <column>]",
                        "            Run a query over a delimited file."));
        commands.put("version", List.of("  version", "            Print the version and exit."));
        return Collections.unmodifiableMap(commands);
    }

    /** The commands a help request may name, in the order the top-level usage prints them. */
    static Set<String> helpTopics() {
        return HELP.keySet();
    }

    private void printUsage() {
        out.println(Ansi.bold(PROGRAM) + " " + Ansi.dim(version()) + "  " + Ansi.accent("Ask once. Answer always."));
        out.println();
        out.println(Ansi.bold("Usage:") + "  " + PROGRAM + " <command> [options]");
        out.println();
        out.println(Ansi.bold("Commands:"));
        boolean first = true;
        for (List<String> block : HELP.values()) {
            if (!first) {
                out.println();
            }
            first = false;
            block.forEach(out::println);
        }
        out.println();
        out.println(Ansi.dim("  " + PROGRAM + " <command> --help prints one command's flags."));
        out.println(Ansi.dim("  Commands that talk to a running engine -- query, register, queries, subscribe,"));
        out.println(Ansi.dim("  dlq, login, lanes, debug and the rest -- are in the Python CLI: pip install the"));
        out.println(Ansi.dim("  Pravaha Python SDK and run `pravaha --help`."));
        out.println();
        out.println(Ansi.bold("Schema spec:") + "  name:TYPE,name:TYPE   (suffix a type with ? for nullable)");
        out.println(Ansi.dim("  BOOLEAN INT8 INT16 INT32 INT64 FLOAT32 FLOAT64 STRING BYTES TIMESTAMP"));
        out.println();
        out.println(Ansi.bold("Example:"));
        out.println(Ansi.dim("  " + PROGRAM + " run --sql \"SELECT user_id FROM txn WHERE amount > 100\" \\"));
        out.println(Ansi.dim("    --schema 'txn_id:INT64,user_id:STRING,amount:INT64' \\"));
        out.println(Ansi.dim("    --in txn.csv --out big.csv --out-schema 'user_id:STRING'"));
    }
}

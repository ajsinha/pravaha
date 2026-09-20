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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // P-4: --help was parsed as an ordinary flag, so `pravaha queries --help` opened a
        // connection to say so and six other commands answered "missing required option --name".
        // A command's own flags have to be discoverable from the binary, and asking a server is
        // never part of answering that.
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
                // Everything below talks to a running server, through the published SDK rather
                // than reaching into the engine -- so the CLI is the client API's first consumer
                // and its awkward corners show up here before a customer finds them.
                case "query" -> new ServerCommand(out, err).query(rest);
                case "register" -> new ServerCommand(out, err).register(rest);
                case "queries" -> new ServerCommand(out, err).queries(rest);
                case "drop", "pause", "resume" -> new ServerCommand(out, err).lifecycle(command, rest);
                case "replace" -> new ServerCommand(out, err).replace(rest);
                case "cutover", "rollback", "abandon", "finish", "throttle", "pause-backfill", "resume-backfill" ->
                    new ServerCommand(out, err).replacement(command, rest);
                case "replacements" -> new ServerCommand(out, err).replacements(rest);
                case "subscribe" -> new ServerCommand(out, err).subscribe(rest);
                case "dlq" -> new DlqCommand(out, err).run(rest);
                // The time-travel debugger (ADR-048). Its own command with verbs of its own,
                // because a session is a conversation rather than a single call.
                case "debug" -> new DebugCommand(out, err).run(rest);
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
     * <p>One table rather than two: {@code pravaha --help} prints every block and {@code pravaha
     * <command> --help} prints one of them, so a command cannot be documented at the top level and
     * undiscoverable from itself (P-4). {@code pause}, {@code resume} and {@code drop} share a
     * block because they share every flag.
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
                "query",
                List.of(
                        "  query     --sql <query> [--params a,b] [--url grpc://host:9090] [--token t]",
                        "            Ask a running server a question and print the rows. Also runs",
                        "            CREATE / DROP / PAUSE / RESUME CONTINUOUS QUERY and SHOW CONTINUOUS QUERIES."));
        commands.put(
                "register",
                List.of(
                        "  register  --name <view> --sql-file <path> [--keys 0,1] [--sink <name>] [--retain PT24H]",
                        "            [--url ...]",
                        "            Register a continuous query. It runs until it is dropped. --sink also writes",
                        "            its changes to a sink the server binds under pravaha.sinks.<name>. --retain",
                        "            is how much event time the view keeps (ISO-8601, or 'forever')."));
        commands.put(
                "queries",
                List.of(
                        "  queries   [--verbose] [--url ...]",
                        "            List the continuous queries a server is running. SINK is the binding",
                        "            each one writes to, or '-'. A query whose source stopped mid-read shows",
                        "            'RUNNING (source stopped)', and a sink that refused a batch shows",
                        "            '(detached)'; each gets a line naming the code and what happened.",
                        "            --verbose adds each query's FEED."));
        commands.put(
                "subscribe",
                List.of(
                        "  subscribe --view <name> [--filter col=val,col2=val2] [--snapshot] [--limit N] [--url ...]",
                        "            Stream changes as they are committed. Each change leads with its weight:",
                        "            +1 a row arriving, -1 a row withdrawn. A '-- commit' line closes each commit.",
                        "            --snapshot prints the view's rows first ('-- snapshot at frontier F'), then",
                        "            every commit after them, none missed; without it the stream starts at the",
                        "            next commit and a read of the view beside it can miss the one in flight."));
        List<String> lifecycle = List.of(
                "  pause | resume | drop   --name <view> [--url ...]",
                "            Lifecycle. A computation is released when its last name is dropped.");
        commands.put("pause", lifecycle);
        commands.put("resume", lifecycle);
        commands.put("drop", lifecycle);
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
        commands.put(
                "debug",
                List.of(
                        "  debug     <verb> [--url ...]",
                        "            Fork a query from a checkpoint and step it under inspection. Every sink is",
                        "            disabled and nothing can read the fork's view; the live query is untouched.",
                        "",
                        "            fork        --name <view> [--checkpoint <id>]   open a session; prints its id",
                        "            checkpoints --name <view>                        which checkpoints to fork from",
                        "            step        --session <id> [--step row|rows:N|commit|watermark:<nanos>|",
                        "                                        until:<column>:<op>:<value>]",
                        "            state       --session <id>                       what state the fork holds",
                        "            inspect     --session <id> --operator <id> [--key k] [--offset N] [--limit N]",
                        "            view        --session <id>                       the fork's own answer",
                        "            fixture     --session <id> --name <what it reproduces> [--out <path>]",
                        "            sessions                                         every session you may see",
                        "            end         --session <id>                       release the fork"));
        commands.put("version", List.of("  version", "            Print the version and exit."));
        return java.util.Collections.unmodifiableMap(commands);
    }

    /** The commands a help request may name, in the order the top-level usage prints them. */
    static java.util.Set<String> helpTopics() {
        return HELP.keySet();
    }

    private void printUsage() {
        out.println(Ansi.bold("pravaha") + " " + Ansi.dim(version()) + "  " + Ansi.accent("Ask once. Answer always."));
        out.println();
        out.println(Ansi.bold("Usage:") + "  pravaha <command> [options]");
        out.println();
        out.println(Ansi.bold("Commands:"));
        boolean first = true;
        for (List<String> block : new java.util.LinkedHashSet<>(HELP.values())) {
            if (!first) {
                out.println();
            }
            first = false;
            block.forEach(out::println);
        }
        out.println();
        out.println("  query     --sql <query> [--params a,b] [--url grpc://host:9090] [--token t]");
        out.println("            Ask a running server a question and print the rows. Also runs");
        out.println("            CREATE / DROP / PAUSE / RESUME CONTINUOUS QUERY and SHOW CONTINUOUS QUERIES.");
        out.println();
        out.println("  register  --name <view> --sql-file <path> [--keys 0,1] [--sink <name>] [--retain PT24H]");
        out.println("            [--url ...]");
        out.println("            Register a continuous query. It runs until it is dropped. --sink also writes");
        out.println("            its changes to a sink the server binds under pravaha.sinks.<name>. --retain");
        out.println("            is how much event time the view keeps (ISO-8601, or 'forever').");
        out.println();
        out.println("  queries   [--verbose] [--url ...]");
        out.println("            List the continuous queries a server is running. SINK is the binding");
        out.println("            each one writes to, or '-'. A query whose source stopped mid-read shows");
        out.println("            'RUNNING (source stopped)', and a sink that refused a batch shows");
        out.println("            '(detached)'; each gets a line naming the code and what happened.");
        out.println("            --verbose adds each query's FEED.");
        out.println();
        out.println("  subscribe --view <name> [--filter col=val,col2=val2] [--snapshot] [--limit N] [--url ...]");
        out.println("            Stream changes as they are committed. Each change leads with its weight:");
        out.println("            +1 a row arriving, -1 a row withdrawn. A '-- commit' line closes each commit.");
        out.println("            --snapshot prints the view's rows first ('-- snapshot at frontier F'), then");
        out.println("            every commit after them, none missed; without it the stream starts at the");
        out.println("            next commit and a read of the view beside it can miss the one in flight.");
        out.println();
        out.println("  replace   --name <view> --sql-file <path> [--keys 0,1] [--backfill history|none]");
        out.println("            [--rate-limit N] [--cutover manual|auto] [--rollback-retention PT1H] [--wait]");
        out.println("            Start a blue/green replacement: a new version beside the running one,");
        out.println("            backfilled from the source and spliced onto the live stream. The name keeps");
        out.println("            answering the old version until you cut over.");
        out.println();
        out.println("  replacements [--name <view>] [--url ...]");
        out.println("            How the replacements this server knows about are getting on.");
        out.println();
        out.println("  cutover | rollback | abandon | finish   --name <view> [--url ...]");
        out.println("            Move the name to the new version; put the old one back while it is still");
        out.println("            retained; end a replacement that has not cut over; or confirm one, which");
        out.println("            releases the version it replaced and ends the chance to roll back.");
        out.println();
        out.println("  throttle  --name <view> --rate N   |   pause-backfill | resume-backfill --name <view>");
        out.println("            Control a backfill while it runs. The rate is a ceiling the replacement was");
        out.println("            started with; above it the server refuses.");
        out.println();
        out.println("  dlq list   --name <view> [--offset N] [--limit N] [--url ...]");
        out.println("  dlq show   --name <view> --id <id> [--url ...]");
        out.println("  dlq replay --name <view> --id <id>[,<id>...] [--url ...]");
        out.println("            The records a query's feed could not decode: list them newest first,");
        out.println("            print one whole with its bytes, or feed chosen ones back through the");
        out.println("            query -- as new rows at its current frontier, not a rewind. A record");
        out.println("            that fails again goes back on the queue rather than being retried.");
        out.println();
        out.println("  pause | resume | drop   --name <view> [--url ...]");
        out.println("            Lifecycle. A computation is released when its last name is dropped.");
        out.println();
        out.println("  debug     fork | step | state | inspect | view | fixture | sessions | end | checkpoints");
        out.println("            Fork a query from a checkpoint and step it under inspection, with every sink");
        out.println("            disabled and nothing able to read the fork's view. `pravaha debug --help`");
        out.println("            lists each verb's flags. The end of the journey is `debug fixture`, which");
        out.println("            writes the session out as a JUnit test you can commit.");
        out.println();
        out.println("  explain   --sql <query> --schema <spec> [--level logical|physical|codegen|all]");
        out.println("            [--event-time <column>]");
        out.println("            Show the plan the engine would execute.");
        out.println();
        out.println("  run       --sql <query> --schema <spec> --in <file>");
        out.println("            --out <file> --out-schema <spec> [--dlq <file>] [--event-time <column>]");
        out.println("            Run a query over a delimited file.");
        out.println();
        out.println("  version");
        out.println(Ansi.dim("  pravaha <command> --help prints one command's flags, without a server."));
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

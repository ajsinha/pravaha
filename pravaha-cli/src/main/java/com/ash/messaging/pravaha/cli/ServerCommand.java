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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.RegisteredQueryInfo;
import com.ash.messaging.pravaha.sdk.flight.ReplacementInfo;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The commands that talk to a running server.
 *
 * <p>Everything here goes through the published SDK rather than reaching into the engine, and that
 * is deliberate rather than convenient: the CLI is the first consumer of the client API, so if
 * something is awkward to do here it is awkward for everybody, and the awkwardness shows up before
 * a customer finds it.
 */
final class ServerCommand {

    private final PrintStream out;
    private final PrintStream err;

    ServerCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int query(List<String> arguments) {
        Args args = Args.parse(arguments);
        String sql = sqlFrom(args);
        List<Object> parameters = new ArrayList<>();
        args.get("params").ifPresent(text -> {
            for (String value : text.split(",")) {
                parameters.add(coerce(value.strip()));
            }
        });

        try (PravahaFlightClient client = connect(args)) {
            try (QueryResult result =
                    parameters.isEmpty() ? client.query(sql) : client.query(sql, parameters.toArray())) {
                boolean header = false;
                int rows = 0;
                for (Row row : result) {
                    if (!header) {
                        out.println(Ansi.bold(String.join("\t", row.columns())));
                        header = true;
                    }
                    out.println(render(row));
                    rows++;
                }
                out.println(Ansi.dim(rows + " row" + (rows == 1 ? "" : "s")));
            }
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    int register(List<String> arguments) {
        Args args = Args.parse(arguments);
        // HLP-12. CONTINUOUS_QUERIES once documented `register --param`, and an unknown option is
        // otherwise ignored, so a value copied from it would have been dropped without a word. The
        // register action carries no parameters; only an embedded QueryRegistry binds them.
        if (args.has("param") || args.has("params")) {
            throw new Args.UsageException("register takes no parameters: the server's register action cannot "
                    + "carry bound values. Write the value into the SQL, or register once without it and "
                    + "filter by that column at read time (subscribe --filter, or query with --params)");
        }
        String name = args.require("name");
        String sql = sqlFrom(args);
        List<Integer> keys = new ArrayList<>();
        for (String ordinal : args.get("keys", "0").split(",")) {
            keys.add(Integer.parseInt(ordinal.strip()));
        }

        // Optional: a binding name under the server's pravaha.sinks, which the query's changelog is
        // written to as well as its view (ADR-043).
        String sink = args.get("sink", "").strip();
        // Optional: how much event time the view keeps, ISO-8601 (PT24H, P7D) or "forever". Checked by
        // the server, which refuses what it cannot read rather than keeping a different amount.
        String retain = args.get("retain", "").strip();

        try (PravahaFlightClient client = connect(args)) {
            RegisteredQueryInfo registered =
                    client.register(name, sql, keys, sink.isEmpty() ? null : sink, retain.isEmpty() ? null : retain);
            out.println(Ansi.good("registered ")
                    + registered.name()
                    + Ansi.dim("  state=" + registered.state() + "  fingerprint=" + registered.fingerprint())
                    + (sink.isEmpty() ? "" : Ansi.dim("  sink=" + sink))
                    + (retain.isEmpty() ? "" : Ansi.dim("  retain=" + retain)));
            // Worth saying out loud: two names on one fingerprint are one computation and one copy
            // of the state, and somebody reading this should know which happened.
            out.println(Ansi.dim("a query with the same fingerprint is the same computation, shared"));
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /**
     * {@code pravaha replace}: a new version of a registered query, beside the running one.
     *
     * <p>It prints what the server says and stops. The cutover is a separate command on purpose --
     * it is the moment the answer changes -- unless {@code --cutover auto} says the operator does
     * not want to be asked. {@code --wait} holds the terminal until the backfill has caught up, so
     * a script can start one and then cut over without polling.
     */
    int replace(List<String> arguments) {
        Args args = Args.parse(arguments);
        String name = args.require("name");
        String sql = sqlFrom(args);
        List<Integer> keys = new ArrayList<>();
        for (String ordinal : args.get("keys", "0").split(",")) {
            keys.add(Integer.parseInt(ordinal.strip()));
        }
        List<String> options = new ArrayList<>();
        args.get("backfill").ifPresent(value -> options.add("backfill=" + value.strip()));
        args.get("rate-limit").ifPresent(value -> options.add("backfill.rate.limit=" + value.strip()));
        args.get("cutover").ifPresent(value -> options.add("cutover=" + value.strip()));
        args.get("rollback-retention").ifPresent(value -> options.add("rollback.retention=" + value.strip()));

        try (PravahaFlightClient client = connect(args)) {
            ReplacementInfo replacement =
                    client.replace(name, sql, keys, options.isEmpty() ? null : String.join(";", options));
            print(replacement);
            if (args.has("wait")) {
                replacement = awaitCaughtUp(client, name);
                print(replacement);
            }
            out.println(Ansi.dim("'" + name + "' still answers the version it answered before; "
                    + "`pravaha cutover --name " + name + "` is what moves it"));
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /** {@code cutover}, {@code rollback}, {@code abandon} and {@code finish}, which take a name. */
    int replacement(String verb, List<String> arguments) {
        Args args = Args.parse(arguments);
        String name = args.require("name");
        try (PravahaFlightClient client = connect(args)) {
            ReplacementInfo replacement =
                    switch (verb) {
                        case "cutover" -> client.cutOver(name);
                        case "rollback" -> client.rollBack(name);
                        case "abandon" -> client.abandonReplacement(name);
                        case "finish" -> client.finishReplacement(name);
                        case "throttle" -> client.throttleBackfill(name, Long.parseLong(args.require("rate")));
                        case "pause-backfill" -> client.pauseBackfill(name);
                        case "resume-backfill" -> client.resumeBackfill(name);
                        default -> throw new Args.UsageException("unknown replacement command '" + verb + "'");
                    };
            print(replacement);
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /** {@code pravaha replacements}: every replacement a server knows about, in flight or finished. */
    int replacements(List<String> arguments) {
        Args args = Args.parse(arguments);
        try (PravahaFlightClient client = connect(args)) {
            java.util.Optional<String> name = args.get("name");
            List<ReplacementInfo> all = name.isPresent()
                    ? client.replacement(name.get()).map(List::of).orElse(List.of())
                    : client.replacements();
            if (all.isEmpty()) {
                out.println(Ansi.dim("no query is being replaced"));
                return PravahaCli.EXIT_OK;
            }
            out.println(Ansi.bold("NAME\tSTATE\tHISTORY\tROWS/S\tLIVE\tROLLBACK"));
            for (ReplacementInfo replacement : all) {
                out.println(replacement.name() + "\t" + replacement.state() + "\t" + replacement.historyRows()
                        + "\t" + replacement.rowsPerSecond() + "\t"
                        + replacement.partitionsLive() + "/" + replacement.partitions() + "\t"
                        + (replacement.rollbackAvailable() ? "until " + replacement.rollbackUntil() : "-"));
            }
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    private ReplacementInfo awaitCaughtUp(PravahaFlightClient client, String name) {
        long deadline = System.nanoTime() + java.time.Duration.ofHours(24).toNanos();
        while (System.nanoTime() < deadline) {
            ReplacementInfo replacement = client.replacement(name)
                    .orElseThrow(() -> new IllegalStateException("'" + name + "' is no longer being replaced"));
            if (!"BACKFILLING".equals(replacement.state())) {
                return replacement;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return replacement;
            }
        }
        throw new IllegalStateException("'" + name + "' has not caught up in a day");
    }

    private void print(ReplacementInfo replacement) {
        out.println(Ansi.good(
                        replacement.state().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                + " " + replacement.name()
                + Ansi.dim("  candidate=" + replacement.candidate() + "  replacing=" + replacement.replacing()));
        out.println(Ansi.dim("  history " + replacement.historyRows() + " rows"
                + (replacement.partitions() > 0
                        ? ", " + replacement.partitionsLive() + " of " + replacement.partitions()
                                + " partitions on the live stream"
                        : "")
                + ", " + replacement.rowsPerSecond() + " rows/s"
                + (replacement.rateLimit() > 0 ? " (limit " + replacement.rateLimit() + ")" : "")
                + (replacement.paused() ? ", paused" : "")
                + ", lag " + replacement.lagNanos() / 1_000_000 + " ms"));
        if (replacement.rollbackAvailable()) {
            out.println(Ansi.dim("  the version it replaced is retained until " + replacement.rollbackUntil()));
        }
        if (replacement.failure() != null) {
            err.println(Ansi.bad(replacement.failureCode() + "  " + replacement.failure()));
        }
    }

    int queries(List<String> arguments) {
        Args args = Args.parse(arguments);
        try (PravahaFlightClient client = connect(args)) {
            List<RegisteredQueryInfo> queries = client.queries();
            if (queries.isEmpty()) {
                out.println(Ansi.dim("no continuous queries are registered"));
                return PravahaCli.EXIT_OK;
            }
            boolean verbose = args.has("verbose");
            out.println(Ansi.bold("NAME\tSTATE\tFINGERPRINT\tROWS IN\tSINK" + (verbose ? "\tFEED" : "")));
            boolean anyWithheld = false;
            List<RegisteredQueryInfo> stopped = new ArrayList<>();
            List<RegisteredQueryInfo> detached = new ArrayList<>();
            for (RegisteredQueryInfo query : queries) {
                anyWithheld = anyWithheld || query.rowsIn() < 0;
                if (query.isSourceStopped()) {
                    stopped.add(query);
                }
                if (query.isSinkDetached()) {
                    detached.add(query);
                }
                out.println(query.name() + "\t" + stateText(query) + "\t" + query.fingerprint() + "\t"
                        + rowsInText(query.rowsIn()) + "\t" + sinkText(query)
                        + (verbose ? "\t" + (query.feed() == null ? "-" : query.feed()) : ""));
            }
            if (anyWithheld) {
                out.println(Ansi.dim("a '-' under ROWS IN means the server did not disclose the count: your "
                        + "access to that view is a filtered subset of its rows, and its total is not "
                        + "part of what you may see"));
            }
            // FEED-1. Not behind --verbose: a query that says RUNNING and is not moving is the one line
            // of this listing somebody must not have to ask for.
            for (RegisteredQueryInfo query : stopped) {
                out.println(Ansi.bad(query.name() + ": " + stopText(query.feedStop())));
            }
            if (!stopped.isEmpty()) {
                out.println(Ansi.dim("a stopped source is not retried: the view keeps answering at the frontier it "
                        + "reached. Fix the cause, then drop the query and register it again, or restart the "
                        + "node. " + codeLookupSentence()));
            }
            // SINK-3. Beside the stopped-source lines and for the same reason: a query that says
            // RUNNING while nothing reaches the table it was registered to write is the other thing
            // an operator must not have to go and ask about.
            for (RegisteredQueryInfo query : detached) {
                out.println(Ansi.bad(query.name() + ": " + sinkFailureText(query)));
            }
            if (!detached.isEmpty()) {
                out.println(Ansi.dim("a detached sink is not retried either, and the query and its view carry on "
                        + "and stay right. Fix the cause, then drop the query and register it again: the sink "
                        + "is sent the view's whole contents first, so nothing written while it was detached "
                        + "is lost"));
            }
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /**
     * The {@code STATE} cell: the query's state, and -- when a source of a running query has stopped --
     * that too (FEED-1).
     *
     * <p>A marker in the state cell rather than a column, so the listing an operator already reads, and
     * every example of it, keeps its shape; {@code --verbose} adds the feed as a column of its own. The
     * state itself is not changed: the query is running, and its view answers at the frontier it
     * reached.
     */
    static String stateText(RegisteredQueryInfo query) {
        return query.isSourceStopped() ? query.state() + " (source stopped)" : query.state();
    }

    /**
     * The {@code SINK} cell: the binding a query writes to, {@code -} when it writes nowhere, and
     * the binding marked when it has been detached (SINK-3).
     *
     * <p>A column rather than a marker on the state, unlike a stopped source, because the sink is a
     * fact about the query that an operator asks for by name -- "which table does this write to"
     * was not answerable from this listing at all, and the only other way to find out was the HTTP
     * API. The detachment rides on the same cell so the column is never merely a name when the name
     * is no longer being written to.
     *
     * <p>A server that predates the sink's state sends nothing for it, and such a sink reads as its
     * name alone: unknown is not the same as attached, and claiming it is would be the one mistake
     * this column must not make.
     */
    static String sinkText(RegisteredQueryInfo query) {
        if (query.sink() == null || query.sink().isEmpty()) {
            return "-";
        }
        return query.isSinkDetached() ? query.sink() + " (detached)" : query.sink();
    }

    /** One detached sink, as the line under the listing says it: the code and what happened. */
    static String sinkFailureText(RegisteredQueryInfo query) {
        RegisteredQueryInfo.SinkFailure failure = query.sinkFailure();
        if (failure == null) {
            return "the sink '" + query.sink() + "' was detached, and this server did not say why";
        }
        StringBuilder text = new StringBuilder("sink '").append(query.sink()).append("' detached with ");
        text.append(failure.code());
        if (failure.message() != null && !failure.message().isEmpty()) {
            text.append(": ").append(failure.message());
        }
        return text.toString();
    }

    /**
     * How the listing tells a reader to resolve the codes it has just printed (DOCX-21).
     *
     * <p>It used to be the sentence "Each code has a help page:
     * {@code https://docs.pravaha.io/errors/<code>}", naming a host that has never resolved. The
     * deployment decides whether there is a help page at all, and when there is not this says
     * where the codes are written down instead rather than saying nothing.
     */
    static String codeLookupSentence() {
        return com.ash.messaging.pravaha.api.HelpUrls.configured()
                ? "Each code has a help page: " + com.ash.messaging.pravaha.api.HelpUrls.base() + "<code>"
                : "Look each code up in the console's help under Errors, or in docs/TROUBLESHOOTING.md.";
    }

    /** One stopped source, as the line under the listing says it: code, where, when, and why. */
    static String stopText(RegisteredQueryInfo.FeedStop stop) {
        if (stop == null) {
            return "a source stopped, and this server did not say why";
        }
        StringBuilder text = new StringBuilder("source stopped with ").append(stop.code());
        if (stop.where() != null && !stop.where().isEmpty()) {
            text.append(" reading ").append(stop.where());
        }
        if (stop.at() != null && !stop.at().isEmpty()) {
            text.append(" at ").append(stop.at());
        }
        if (stop.message() != null && !stop.message().isEmpty()) {
            text.append(": ").append(stop.message());
        }
        return text.toString();
    }

    /**
     * The {@code ROWS IN} cell: a count, or a dash when the server withheld it.
     *
     * <p>SX-18. A principal entitled to a row-filtered slice of a view was told the view's
     * <em>unfiltered</em> row count -- {@code sales_view} reported 4 rows to a caller whose own read
     * of it returns 2. The server now sends {@code -1} for exactly that case: the field stays a
     * decimal long, which both SDKs already parse, and {@code rowsIn} is a counter that is never
     * negative, so no real count can be mistaken for it.
     *
     * <p>Printed as {@code -} rather than as {@code -1}, because {@code -1} in a column of counts
     * reads as a count. The client is the right place to make that legible; the wire is the right
     * place to make it unambiguous, and they are not the same job.
     */
    static String rowsInText(long rowsIn) {
        return rowsIn < 0 ? "-" : Long.toString(rowsIn);
    }

    int lifecycle(String action, List<String> arguments) {
        Args args = Args.parse(arguments);
        String name = args.require("name");
        try (PravahaFlightClient client = connect(args)) {
            switch (action) {
                case "drop" -> client.drop(name);
                case "pause" -> client.pause(name);
                default -> client.resume(name);
            }
            out.println(Ansi.good(pastTense(action) + " ") + name);
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /**
     * What the lifecycle command did, in words. HLP-10: this was {@code action + "ped"}, which is
     * right for "drop" and printed "pauseped" and "resumeped" for the other two.
     */
    static String pastTense(String action) {
        return switch (action) {
            case "drop" -> "dropped";
            case "pause" -> "paused";
            case "resume" -> "resumed";
            default -> action;
        };
    }

    int subscribe(List<String> arguments) {
        Args args = Args.parse(arguments);
        String view = args.require("view");
        Map<String, String> filters = new LinkedHashMap<>();
        args.get("filter").ifPresent(text -> {
            for (String pair : text.split(",")) {
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    throw new Args.UsageException("--filter takes column=value pairs, got '" + pair + "'");
                }
                filters.put(
                        pair.substring(0, equals).strip(),
                        pair.substring(equals + 1).strip());
            }
        });
        long limit = Long.parseLong(args.get("limit", "0"));
        // SUB-1. Without --snapshot a subscription starts at the next commit and says nothing of
        // what the view already holds, so reading the view beside it can miss the commit in flight.
        // With it the view's rows come first, then every commit after them, with nothing between.
        boolean fromSnapshot = args.has("snapshot");

        try (PravahaFlightClient client = connect(args)) {
            boolean[] header = {false};
            java.util.function.Consumer<com.ash.messaging.pravaha.sdk.flight.ChangeBatch> print = batch -> {
                for (Row row : batch) {
                    if (!header[0]) {
                        out.println(Ansi.bold("WEIGHT\t" + String.join("\t", row.columns())));
                        header[0] = true;
                    }
                    out.println(renderChange(row));
                }
                // One batch is one commit, and saying so makes the boundary visible to whoever is
                // watching the output rather than something they have to know.
                String rows = batch.size() + " row" + (batch.size() == 1 ? "" : "s");
                out.println(Ansi.dim(
                        batch.isSnapshot()
                                ? "-- snapshot at frontier " + batch.frontier() + ", " + rows
                                : "-- commit, " + rows));
            };
            Subscription subscription = fromSnapshot
                    ? client.subscribeFromSnapshot(view, filters, print)
                    : client.subscribe(view, filters, print);
            // API-F7, fixed twice over and kept both ways. The banner was printed at the top of
            // this block, before anything had been sent to the server at all, because a Flight
            // stream is lazy: against a node that was not running, "subscribed to x" went to
            // stdout and the failure went to stderr a moment later, so a pipeline reading stdout
            // had a confirmation from a command that exited 1.
            //
            // So it waits, and it moves. awaitOpen returns when the server has sent its schema,
            // which it does once it has authorized the reader and found the view -- the banner is
            // then a statement about something that happened. And it goes to stderr, because it
            // is a note to a person and stdout carries the rows.
            subscription.awaitOpen();
            err.println(Ansi.dim("subscribed to " + view + (filters.isEmpty() ? "" : " " + filters)
                    + (fromSnapshot ? "; the view's rows print first, then" : ";")
                    + " changes print as they are committed. Ctrl-C to stop."));
            Runtime.getRuntime().addShutdownHook(new Thread(subscription::close));
            if (limit > 0) {
                Thread watcher = Thread.ofVirtual().start(() -> {
                    while (!subscription.isClosed() && subscription.rows() < limit) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    subscription.close();
                });
                subscription.run();
                watcher.interrupt();
            } else {
                subscription.run();
            }
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /**
     * The address the last {@link #connect} was pointed at, so a refusal can name it (API-F7).
     *
     * <p>Every one of the seven commands against nothing listening failed with the bare stderr
     * text {@code PRV-1041  io exception}: no host, no port, no scheme. An operator debugging "why
     * did my script print `io exception` and exit 1" had nothing to go on -- not even whether the
     * default endpoint had been used because {@code --url} went into a different flag.
     */
    private String endpoint;

    private PravahaFlightClient connect(Args args) {
        String url = args.get("url", "grpc://localhost:19090");
        this.endpoint = url;
        ClientOptions.Builder options = ClientOptions.builder(url);
        // P-3. This was `.allowInsecureToken(true)` unconditionally, on every command, on every
        // invocation carrying --token, with no flag to opt out -- so the CLI answered the safety
        // question on the operator's behalf and always answered "yes". It has to be typed now.
        args.get("token").ifPresent(token -> options.token(token).allowInsecureToken(args.has("insecure-token")));
        return PravahaFlightClient.connect(options.build());
    }

    /** SQL from --sql, or from a file with --sql-file, which is how real queries are kept. */
    private String sqlFrom(Args args) {
        if (args.has("sql-file")) {
            Path path = Path.of(args.require("sql-file"));
            try {
                return Files.readString(path).strip();
            } catch (java.io.IOException e) {
                throw new Args.UsageException("cannot read " + path + ": " + e.getMessage());
            }
        }
        return args.require("sql");
    }

    /** A parameter is a number when it looks like one, and text otherwise. */
    private static Object coerce(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notALong) {
            try {
                return Double.valueOf(value);
            } catch (NumberFormatException notADouble) {
                return value;
            }
        }
    }

    /**
     * One change of a subscribed view: its Z-set weight, then its columns.
     *
     * <p>HLP-11. The weight was not printed, so a retraction and the insert it withdraws printed as
     * the same line and a reader of the stream could not tell a row leaving from a row arriving. The
     * weight is the model -- a view is a set of rows with weights, and a change is a row with a
     * weight -- so it leads the line, signed: {@code +1} arriving, {@code -1} withdrawn.
     */
    static String renderChange(Row row) {
        return weightText(row.weight()) + "\t" + render(row);
    }

    /** A weight, always signed, so {@code +1} and {@code -1} line up and neither reads as a count. */
    static String weightText(long weight) {
        return weight > 0 ? "+" + weight : Long.toString(weight);
    }

    private static String render(Row row) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < row.columns().size(); i++) {
            if (i > 0) {
                text.append('\t');
            }
            String value = row.getString(i);
            text.append(value == null ? "NULL" : value);
        }
        return text.toString();
    }

    private int fail(RuntimeException e) {
        // The server's own message, PRV code and all. A CLI that replaced it with "query failed"
        // would be throwing away the part that says what to do.
        //
        // API-F7: with the address appended when the message does not already carry it. The
        // transport's own text for nothing listening is "io exception", which is true of any
        // socket anywhere; the one thing this process knows and that message does not is where it
        // was pointed.
        String message = String.valueOf(e.getMessage());
        if (endpoint != null && !message.contains(endpoint)) {
            message = message + " (talking to " + endpoint
                    + " -- pass --url if that is not the node you meant; it is the default when "
                    + "--url is not given)";
        }
        err.println(Ansi.bad(message));
        if (System.getenv("PRAVAHA_CLI_TRACE") != null) {
            e.printStackTrace(err);
        }
        return PravahaCli.EXIT_FAILED;
    }
}

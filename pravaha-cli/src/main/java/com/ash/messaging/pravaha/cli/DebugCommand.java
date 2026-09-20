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
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.DebugSessionInfo;
import com.ash.messaging.pravaha.sdk.flight.DebugStatePage;
import com.ash.messaging.pravaha.sdk.flight.DebugStepReport;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;

/**
 * {@code pravaha debug}: the time-travel debugger from a terminal (ADR-048).
 *
 * <p>Design section 23.9 says the debugger "has no meaningful CLI form", and as a stepping
 * <em>interface</em> that is right -- four panels updating together is a screen, not a scroll. What
 * a terminal is good at is the two ends of the journey, and both are here: opening a session
 * against a production node over SSH, and exporting the fixture at the end so it can be committed
 * without a browser. Everything between the two works too, one step per line, because a session
 * that could only be driven from the console could not be driven from a script.
 *
 * <p>The session id goes to stdout on its own line at {@code fork}, so a shell can capture it:
 * {@code SESSION=$(pravaha debug fork --name spend | tail -1)}.
 */
final class DebugCommand {

    private final PrintStream out;
    private final PrintStream err;

    DebugCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int run(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw new Args.UsageException(
                    "debug needs a verb: fork, step, sessions, state, inspect, view, " + "fixture, end or checkpoints");
        }
        String verb = arguments.get(0);
        Args args = Args.parse(arguments.subList(1, arguments.size()));
        try (PravahaFlightClient client = connect(args)) {
            return switch (verb) {
                case "fork" -> fork(client, args);
                case "checkpoints" -> checkpoints(client, args);
                case "step" -> step(client, args);
                case "sessions" -> sessions(client);
                case "state" -> state(client, args);
                case "inspect" -> inspect(client, args);
                case "view" -> view(client, args);
                case "fixture" -> fixture(client, args);
                case "end" -> end(client, args);
                default ->
                    throw new Args.UsageException("unknown debug verb '" + verb + "'; try "
                            + "fork, step, sessions, state, inspect, view, fixture, end or checkpoints");
            };
        } catch (Args.UsageException e) {
            throw e;
        } catch (RuntimeException e) {
            err.println(Ansi.bad(String.valueOf(e.getMessage())));
            return PravahaCli.EXIT_FAILED;
        }
    }

    private int fork(PravahaFlightClient client, Args args) {
        String name = args.require("name");
        Long checkpoint = args.get("checkpoint").map(Long::parseLong).orElse(null);
        DebugSessionInfo session = client.debugFork(name, checkpoint);
        out.println(Ansi.bold("debug session on '" + session.query() + "'") + "  "
                + Ansi.dim("checkpoint " + session.checkpointId()));
        out.println(Ansi.accent("DEBUG — sinks disabled, nothing reads this fork's view"));
        out.println(session.id());
        return PravahaCli.EXIT_OK;
    }

    private int checkpoints(PravahaFlightClient client, Args args) {
        List<Long> ids = client.debugCheckpoints(args.require("name"));
        if (ids.isEmpty()) {
            out.println(Ansi.dim("no retained checkpoint: this node may not be checkpointing, or the query "
                    + "has not taken one yet"));
            return PravahaCli.EXIT_OK;
        }
        ids.forEach(out::println);
        return PravahaCli.EXIT_OK;
    }

    private int step(PravahaFlightClient client, Args args) {
        DebugStepReport report = client.debugStep(args.require("session"), args.get("step", "row"));
        out.println(Ansi.bold("step " + report.sequence() + " (" + report.kind() + ")") + "  "
                + Ansi.dim(report.stopped()));
        for (DebugStepReport.InputRow row : report.rowsIn()) {
            out.println("  in   " + (row.weight() < 0 ? "-" : "+") + Math.abs(row.weight()) + " " + row.stream() + "#"
                    + row.partition() + "@" + row.offset() + " " + row.values());
        }
        for (DebugStepReport.OperatorFlow operator : report.operators()) {
            out.println("  op   " + operator.id() + (operator.label().isEmpty() ? "" : " " + operator.label()) + "  in="
                    + operator.rowsIn() + " out=" + operator.rowsOut());
        }
        for (DebugStepReport.ViewDelta change : report.viewChanges()) {
            out.println(
                    "  view " + (change.weight() < 0 ? "-" : "+") + Math.abs(change.weight()) + " " + change.values());
        }
        out.println(Ansi.dim("  rows consumed " + report.rowsConsumed() + ", view " + report.viewSize() + " rows"
                + (report.watermarkNanos() == null ? "" : ", watermark " + report.watermarkNanos())
                + (report.exhausted() ? ", sources exhausted" : "")));
        return PravahaCli.EXIT_OK;
    }

    private int sessions(PravahaFlightClient client) {
        List<DebugSessionInfo> open = client.debugSessions();
        if (open.isEmpty()) {
            out.println(Ansi.dim("no debug session is open"));
            return PravahaCli.EXIT_OK;
        }
        out.println(Ansi.bold("ID\tQUERY\tCHECKPOINT\tSTEPS\tROWS\tLAST USED"));
        for (DebugSessionInfo session : open) {
            out.println(session.id() + "\t" + session.query() + "\t" + session.checkpointId() + "\t" + session.steps()
                    + "\t" + session.rowsConsumed() + "\t" + session.lastUsedAt());
        }
        return PravahaCli.EXIT_OK;
    }

    private int state(PravahaFlightClient client, Args args) {
        List<PravahaFlightClient.DebugStateSlot> slots = client.debugState(args.require("session"));
        if (slots.isEmpty()) {
            out.println(Ansi.dim("this query holds no operator state: it is a filter or a projection, and its "
                    + "view is its whole answer"));
            return PravahaCli.EXIT_OK;
        }
        out.println(Ansi.bold("OPERATOR\tKIND\tWHAT\tENTRIES"));
        for (PravahaFlightClient.DebugStateSlot slot : slots) {
            out.println(slot.id() + "\t" + slot.kind() + "\t" + slot.label() + "\t" + slot.entries());
        }
        return PravahaCli.EXIT_OK;
    }

    private int inspect(PravahaFlightClient client, Args args) {
        DebugStatePage page = client.debugInspect(
                args.require("session"),
                args.require("operator"),
                args.get("key").orElse(null),
                Integer.parseInt(args.get("offset", "0")),
                Integer.parseInt(args.get("limit", "20")));
        for (DebugStatePage.Entry entry : page.entries()) {
            out.println(entry.key() + "\t" + render(entry.values()));
        }
        out.println(Ansi.dim((page.offset() + page.entries().size()) + " of " + page.total()
                + (page.hasMore() ? " — more, raise --offset" : "")));
        return PravahaCli.EXIT_OK;
    }

    private int view(PravahaFlightClient client, Args args) {
        for (DebugStepReport.ViewDelta row : client.debugView(args.require("session"))) {
            out.println((row.weight() < 0 ? "-" : "+") + Math.abs(row.weight()) + " " + row.values());
        }
        return PravahaCli.EXIT_OK;
    }

    private int fixture(PravahaFlightClient client, Args args) {
        PravahaFlightClient.DebugFixture fixture = client.debugExport(args.require("session"), args.require("name"));
        Path into = args.get("out").map(Path::of).orElse(null);
        if (into == null) {
            out.println(fixture.source());
            return PravahaCli.EXIT_OK;
        }
        // A path ending in .java is the file; anything else is the directory to put it in, whether
        // or not it exists yet. Naming the file after the class is what makes it compile, and a
        // caller who typed a directory should not have to know that.
        Path file =
                into.getFileName().toString().endsWith(".java") ? into : into.resolve(fixture.className() + ".java");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, fixture.source());
        } catch (java.io.IOException e) {
            throw new Args.UsageException("cannot write " + file + ": " + e.getMessage());
        }
        out.println(Ansi.good("wrote " + file));
        out.println(Ansi.dim("it belongs at " + fixture.path()));
        return PravahaCli.EXIT_OK;
    }

    private int end(PravahaFlightClient client, Args args) {
        String id = args.require("session");
        client.debugEnd(id);
        out.println(Ansi.good("ended " + id));
        return PravahaCli.EXIT_OK;
    }

    private static String render(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        values.forEach((column, value) -> {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append(column).append('=').append(value);
        });
        return text.toString();
    }

    private PravahaFlightClient connect(Args args) {
        ClientOptions.Builder options = ClientOptions.builder(args.get("url", "grpc://localhost:9090"));
        args.get("token").ifPresent(token -> options.token(token).allowInsecureToken(args.has("insecure-token")));
        return PravahaFlightClient.connect(options.build());
    }
}

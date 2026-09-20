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
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.DeadLetterInfo;
import com.ash.messaging.pravaha.sdk.flight.DeadLetterPageInfo;
import com.ash.messaging.pravaha.sdk.flight.DeadLetterReplayInfo;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;

/**
 * {@code pravaha dlq list | show | replay}: the records a query's feed could not decode.
 *
 * <p>Before this the queue was a file on the node's disk, and the documented way to read it was
 * {@code jq} on a machine the operator often cannot reach. This is the same file through the
 * server, which means it is also through the server's authorization: a caller who reads a view
 * through a row filter gets the counts and the codes and not the records, and the listing says so
 * rather than printing an empty column.
 *
 * <p>Through the published SDK, like every other command that talks to a server, so the CLI is the
 * client API's first consumer and its awkward corners show up here before a customer finds them.
 */
final class DlqCommand {

    private final PrintStream out;
    private final PrintStream err;

    DlqCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int run(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw new Args.UsageException("dlq needs a subcommand: list, show or replay");
        }
        String subcommand = arguments.get(0);
        Args args = Args.parse(arguments.subList(1, arguments.size()));
        return switch (subcommand) {
            case "list" -> list(args);
            case "show" -> show(args);
            case "replay" -> replay(args);
            default ->
                throw new Args.UsageException(
                        "'" + subcommand + "' is not a dlq subcommand; it is list, show or replay");
        };
    }

    /**
     * A page of the queue, newest first.
     *
     * <p>Newest first because a queue is read when something has just started failing. The
     * evicted count is printed whenever it is non-zero and not behind a flag: a depth that has
     * silently lost its oldest entries is a number somebody would otherwise read as the whole
     * history.
     */
    private int list(Args args) {
        String name = args.require("name");
        int offset = number(args, "offset", 0);
        int limit = number(args, "limit", 50);
        try (PravahaFlightClient client = connect(args)) {
            DeadLetterPageInfo page = client.deadLetters(name, offset, limit);
            if (!page.configured()) {
                out.println(Ansi.dim("this server has no pravaha.dlq.directory, so a record it cannot decode "
                        + "stops the source rather than being kept. That is not an empty queue."));
                return PravahaCli.EXIT_OK;
            }
            if (page.entries().isEmpty()) {
                out.println(Ansi.good(name + " has no dead letters"));
                return PravahaCli.EXIT_OK;
            }
            out.println("ID\tWHEN\tCODE\tSTREAM\tOFFSET\tBYTES\tSTATE\tREASON");
            for (DeadLetterInfo entry : page.entries()) {
                out.println(entry.id() + "\t"
                        + orDash(entry.at() == null ? "" : entry.at().toString()) + "\t"
                        + orDash(entry.code()) + "\t" + orDash(entry.stream()) + "\t" + orDash(entry.offset())
                        + "\t" + entry.size() + "\t" + entry.replay() + "\t" + reasonText(entry));
            }
            out.println();
            out.println(summary(page));
            if (page.hasMore()) {
                out.println(Ansi.dim("older entries follow: --offset "
                        + (page.offset() + page.entries().size())));
            }
            if (page.evicted() > 0) {
                out.println(Ansi.bad(page.evicted() + " older entries (" + page.evictedBytes()
                        + " bytes) have been evicted by retention and are gone. The bound is "
                        + page.retention() + "; raise pravaha.dlq.max-bytes, or drain the queue more often."));
            }
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /** One entry whole, with its bytes written out as they arrived. */
    private int show(Args args) {
        String name = args.require("name");
        String id = args.require("id");
        try (PravahaFlightClient client = connect(args)) {
            DeadLetterInfo entry = client.deadLetter(name, id);
            out.println(Ansi.bold("id       ") + entry.id());
            out.println(Ansi.bold("query    ") + name);
            out.println(Ansi.bold("stream   ") + orDash(entry.stream()));
            out.println(Ansi.bold("offset   ") + orDash(entry.offset()));
            out.println(Ansi.bold("when     ")
                    + orDash(entry.at() == null ? "" : entry.at().toString()));
            out.println(Ansi.bold("code     ")
                    + orDash(entry.code())
                    + (entry.code().isEmpty() ? "" : Ansi.dim("  https://docs.pravaha.io/errors/" + entry.code())));
            out.println(Ansi.bold("reason   ") + reasonText(entry));
            out.println(Ansi.bold("replay   ")
                    + entry.replay()
                    + (entry.replayedAt() == null ? "" : " at " + entry.replayedAt()));
            out.println(Ansi.bold("bytes    ") + entry.size());
            if (entry.isWithheld()) {
                out.println(Ansi.dim(entry.withheld()));
                return PravahaCli.EXIT_OK;
            }
            out.println(Ansi.bold("record"));
            // Written as it arrived, not as an escaped string: the record is what somebody has to
            // read, and a decode failure is often a byte that does not survive being quoted.
            out.println(new String(entry.raw(), StandardCharsets.UTF_8));
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /**
     * Feeds chosen entries back through the query.
     *
     * <p>Ids are named, never inferred. There is no {@code --all}: a queue is usually a mix of
     * causes and most of it is still malformed, so replaying everything would mostly rewrite the
     * queue with a copy of itself -- at a cost of one poll's worth of work per record.
     *
     * <p>Exits 1 when any record failed again, because a script that replays a corrected batch
     * wants to know that some of it is still wrong without parsing the output.
     */
    private int replay(Args args) {
        String name = args.require("name");
        List<String> ids = List.of(args.require("id").split(","));
        try (PravahaFlightClient client = connect(args)) {
            List<DeadLetterReplayInfo> results = client.replayDeadLetters(
                    name,
                    ids.stream().map(String::strip).filter(id -> !id.isEmpty()).toList());
            int failed = 0;
            for (DeadLetterReplayInfo result : results) {
                if (result.succeeded()) {
                    out.println(Ansi.good("replayed ") + result.id());
                } else {
                    failed++;
                    err.println(Ansi.bad("failed again ")
                            + result.id()
                            + (result.newId().isEmpty() ? "" : Ansi.dim(" -> back on the queue as " + result.newId())));
                }
                out.println(Ansi.dim("  " + result.detail()));
            }
            out.println(Ansi.dim("a replayed record is a new row at the query's current frontier, not a rewind: "
                    + "nothing is re-read and no earlier answer is recomputed."));
            return failed == 0 ? PravahaCli.EXIT_OK : PravahaCli.EXIT_FAILED;
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /** The line under the listing: how many, how large, and how much has already been dealt with. */
    static String summary(DeadLetterPageInfo page) {
        StringBuilder text = new StringBuilder();
        text.append(page.total()).append(page.total() == 1 ? " dead letter" : " dead letters");
        text.append(" (").append(page.bytes()).append(" bytes)");
        if (page.replayed() > 0 || page.failedAgain() > 0) {
            text.append(", ")
                    .append(page.replayed())
                    .append(" replayed, ")
                    .append(page.failedAgain())
                    .append(" failed again");
        }
        text.append("; retention ").append(page.retention());
        return text.toString();
    }

    /** The reason, or the sentence saying why it is not this caller's to read. */
    static String reasonText(DeadLetterInfo entry) {
        if (entry.isWithheld()) {
            return Ansi.dim("withheld");
        }
        return entry.reason().isEmpty() ? "-" : entry.reason();
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static int number(Args args, String key, int fallback) {
        String value = args.get(key, Integer.toString(fallback));
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new Args.UsageException("--" + key + " must be a number, and '" + value + "' is not");
        }
    }

    private PravahaFlightClient connect(Args args) {
        String url = args.get("url", "grpc://localhost:9090");
        ClientOptions.Builder options = ClientOptions.builder(url);
        args.get("token").ifPresent(token -> options.token(token).allowInsecureToken(args.has("insecure-token")));
        return PravahaFlightClient.connect(options.build());
    }

    private int fail(RuntimeException e) {
        err.println(Ansi.bad(e.getMessage()));
        return PravahaCli.EXIT_FAILED;
    }
}

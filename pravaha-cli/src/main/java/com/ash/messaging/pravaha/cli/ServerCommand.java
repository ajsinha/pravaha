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
        String name = args.require("name");
        String sql = sqlFrom(args);
        List<Integer> keys = new ArrayList<>();
        for (String ordinal : args.get("keys", "0").split(",")) {
            keys.add(Integer.parseInt(ordinal.strip()));
        }

        try (PravahaFlightClient client = connect(args)) {
            RegisteredQueryInfo registered = client.register(name, sql, keys);
            out.println(Ansi.good("registered ")
                    + registered.name()
                    + Ansi.dim("  state=" + registered.state() + "  fingerprint=" + registered.fingerprint()));
            // Worth saying out loud: two names on one fingerprint are one computation and one copy
            // of the state, and somebody reading this should know which happened.
            out.println(Ansi.dim("a query with the same fingerprint is the same computation, shared"));
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
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
            out.println(Ansi.bold("NAME\tSTATE\tFINGERPRINT\tROWS IN"));
            for (RegisteredQueryInfo query : queries) {
                out.println(query.name() + "\t" + query.state() + "\t" + query.fingerprint() + "\t" + query.rowsIn());
            }
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
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
            out.println(Ansi.good(action + "ped ") + name);
            return PravahaCli.EXIT_OK;
        } catch (RuntimeException e) {
            return fail(e);
        }
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

        try (PravahaFlightClient client = connect(args)) {
            out.println(Ansi.dim("subscribed to " + view + (filters.isEmpty() ? "" : " " + filters)
                    + "; changes print as they are committed. Ctrl-C to stop."));
            long[] seen = {0};
            Subscription subscription = client.subscribe(view, filters, batch -> {
                for (Row row : batch) {
                    out.println(render(row));
                    seen[0]++;
                }
                // One batch is one commit, and saying so makes the boundary visible to whoever is
                // watching the output rather than something they have to know.
                out.println(Ansi.dim("-- commit, " + batch.size() + " row" + (batch.size() == 1 ? "" : "s")));
            });
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

    private PravahaFlightClient connect(Args args) {
        String url = args.get("url", "grpc://localhost:9090");
        ClientOptions.Builder options = ClientOptions.builder(url);
        args.get("token").ifPresent(token -> options.token(token).allowInsecureToken(true));
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
        err.println(Ansi.bad(String.valueOf(e.getMessage())));
        if (System.getenv("PRAVAHA_CLI_TRACE") != null) {
            e.printStackTrace(err);
        }
        return PravahaCli.EXIT_FAILED;
    }
}

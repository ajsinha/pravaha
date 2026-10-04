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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.RegisteredQueryInfo;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The server verbs these tests used to reach through the Java CLI, driven through the Java SDK.
 *
 * <p>The remote commands left {@code pravaha-engine} for the Python CLI, but the cases that drove a
 * real in-process Flight server through them were testing the server -- its codes, its refusals, its
 * lifecycle -- and the CLI was only the hand on the lever. This keeps their call shape ({@code
 * "query", "--url", url, "--sql", ...}) and their evidence (exit code, stdout, stderr with the
 * server's own message) so the assertions stay as written, and puts the SDK the Python CLI's peers
 * use in the CLI's place.
 */
public final class SdkVerbs {

    private SdkVerbs() {}

    /** What a verb did: 0 on success, 1 when the server or the SDK refused, and what it printed. */
    public record Result(int code, String out, String err) {
        public String combined() {
            return out + err;
        }
    }

    @SuppressWarnings("NullAway") // options pass through as given, a missing one as null, as a script's would
    public static Result run(String... args) {
        String verb = args[0];
        Map<String, String> options = options(args);
        StringBuilder out = new StringBuilder();
        String url = options.getOrDefault("url", "grpc://localhost:19090");
        ClientOptions.Builder builder = ClientOptions.builder(url);
        if (options.containsKey("token")) {
            // Loopback plaintext by construction, which is what allowInsecureToken exists for.
            builder.token(options.get("token")).allowInsecureToken(true);
        }
        try (PravahaFlightClient client = PravahaFlightClient.connect(builder.build())) {
            switch (verb) {
                case "query" -> {
                    try (QueryResult result = client.query(options.get("sql"))) {
                        int rows = 0;
                        for (Row row : result) {
                            if (rows == 0) {
                                out.append(String.join("\t", row.columns())).append('\n');
                            }
                            out.append(render(row)).append('\n');
                            rows++;
                        }
                        out.append(rows).append(rows == 1 ? " row" : " rows").append('\n');
                    }
                }
                case "queries" -> {
                    for (RegisteredQueryInfo query : client.queries()) {
                        out.append(query.name())
                                .append('\t')
                                .append(query.state())
                                .append('\t')
                                .append(query.fingerprint())
                                .append('\n');
                    }
                }
                case "register" -> {
                    List<Integer> keys = new ArrayList<>();
                    for (String ordinal : options.getOrDefault("keys", "0").split(",", -1)) {
                        keys.add(Integer.parseInt(ordinal.strip()));
                    }
                    RegisteredQueryInfo registered = client.register(
                            options.get("name"), options.get("sql"), keys, options.get("sink"), options.get("retain"));
                    out.append("registered ")
                            .append(registered.name())
                            .append("  state=")
                            .append(registered.state())
                            .append('\n');
                }
                case "pause" -> client.pause(options.get("name"));
                case "resume" -> client.resume(options.get("name"));
                case "drop" -> client.drop(options.get("name"));
                case "subscribe" -> {
                    // Opening is the part these cases are about. A refusal can come with the
                    // schema or just after it, so the stream is read briefly before it is closed.
                    // Closed by hand, in the middle, so the reader's thread can be joined after it;
                    // the finally is for a refusal from awaitOpen.
                    Subscription subscription = client.subscribe(options.get("view"), batch -> {});
                    try {
                        subscription.awaitOpen();
                        RuntimeException[] failure = {null};
                        Thread reader = Thread.ofVirtual().start(() -> {
                            try {
                                subscription.run();
                            } catch (RuntimeException e) {
                                failure[0] = e;
                            }
                        });
                        joinQuietly(reader, 2_000);
                        subscription.close();
                        joinQuietly(reader, 5_000);
                        if (failure[0] != null) {
                            throw failure[0];
                        }
                    } finally {
                        subscription.close();
                    }
                }
                default -> throw new IllegalArgumentException("no such verb here: " + verb);
            }
            return new Result(0, out.toString(), "");
        } catch (RuntimeException e) {
            return new Result(1, out.toString(), String.valueOf(e.getMessage()) + "\n");
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String name = args[i].substring(2);
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                options.put(name, hasValue ? args[++i] : "");
            }
        }
        return options;
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
}

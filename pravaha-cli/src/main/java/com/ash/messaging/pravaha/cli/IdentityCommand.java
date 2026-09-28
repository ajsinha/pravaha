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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code pravaha login}, {@code user}, {@code key}, {@code session} and {@code password}: the engine's
 * users, API keys and sessions from a shell (ADR-052, stage 2). Every rule is the engine's; this sends
 * the request to {@code /api/v1} and prints the answer, or the engine's refusal with its code.
 *
 * <p>{@code --token} is a session token from {@code pravaha login} or an API key; {@code --http} is the
 * engine's HTTP address. Nothing is written to disk: the token printed by {@code login} is yours to
 * keep where your shell keeps secrets.
 */
final class IdentityCommand {

    static final String DEFAULT_HTTP = "http://localhost:18080";

    private final PrintStream out;
    private final PrintStream err;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    IdentityCommand(PrintStream out, PrintStream err) {
        this(
                out,
                err,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    IdentityCommand(PrintStream out, PrintStream err, HttpClient http) {
        this.out = out;
        this.err = err;
        this.http = http;
    }

    int run(String command, List<String> arguments) {
        Args args = Args.parse(arguments);
        List<String> words = args.positional();
        String verb = words.isEmpty() ? "list" : words.get(0);
        try {
            return switch (command) {
                case "login" -> login(args);
                case "password" -> {
                    send(
                            args,
                            "POST",
                            "/auth/password",
                            Map.of("current", args.require("current"), "new", args.require("new")));
                    out.println("password changed; your other sessions have ended");
                    yield 0;
                }
                case "user" -> user(args, verb, words);
                case "key" -> key(args, verb, words);
                case "session" -> session(args, verb, words);
                case "lanes" -> lanes(args, words);
                default -> throw new Args.UsageException("unknown identity command '" + command + "'");
            };
        } catch (Refused refused) {
            err.println(refused.getMessage());
            return 1;
        }
    }

    private int login(Args args) {
        JsonNode answer = send(
                args,
                "POST",
                "/auth/login",
                Map.of("username", args.require("user"), "password", args.require("password")));
        if (answer.path("mustChangePassword").asBoolean()) {
            err.println("this account must change its password first: pravaha password --current ... --new ...");
        }
        out.println(answer.path("token").asText());
        return 0;
    }

    private int user(Args args, String verb, List<String> words) {
        switch (verb) {
            case "list" ->
                table(
                        send(args, "GET", "/users", null).path("users"),
                        "username",
                        "roles",
                        "status",
                        "tenant",
                        "lastLoginAt");
            case "create" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("username", nth(words, 1, "user create <name>"));
                body.put("roles", roles(args.require("roles")));
                body.put("password", args.require("password"));
                args.get("tenant").ifPresent(t -> body.put("tenant", t));
                args.get("email").ifPresent(e -> body.put("email", e));
                body.put("service", args.has("service"));
                send(args, "POST", "/users", body);
                out.println("created " + body.get("username"));
            }
            case "disable", "enable" -> {
                String name = nth(words, 1, "user " + verb + " <name>");
                send(args, "PATCH", "/users/" + name, Map.of("status", verb.equals("enable") ? "active" : "disabled"));
                out.println(name + (verb.equals("enable") ? " enabled" : " disabled; their sessions have ended"));
            }
            case "roles" -> {
                String name = nth(words, 1, "user roles <name> --roles a,b");
                send(args, "PUT", "/users/" + name + "/roles", Map.of("roles", roles(args.require("roles"))));
                out.println(name + " now has " + args.require("roles"));
            }
            case "reset" -> {
                String name = nth(words, 1, "user reset <name>");
                JsonNode reset = send(args, "POST", "/users/" + name + "/password-reset", Map.of());
                out.println("reset token for " + name + " (shown once, until "
                        + reset.path("expiresAt").asText() + "):");
                out.println(reset.path("resetToken").asText());
            }
            default -> throw new Args.UsageException("user takes list, create, disable, enable, roles or reset");
        }
        return 0;
    }

    private int key(Args args, String verb, List<String> words) {
        switch (verb) {
            case "list" ->
                table(
                        send(args, "GET", "/keys" + (args.has("all") ? "?all=true" : ""), null)
                                .path("keys"),
                        "keyId",
                        "name",
                        "holder",
                        "roles",
                        "status",
                        "expiresAt",
                        "lastUsedAt");
            case "create" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", nth(words, 1, "key create <name>"));
                args.get("roles").ifPresent(r -> body.put("roles", roles(r)));
                args.get("days").ifPresent(d -> body.put("expiresDays", Integer.parseInt(d)));
                args.get("for").ifPresent(u -> body.put("forUser", u));
                issued(send(args, "POST", "/keys", body));
            }
            case "rotate" ->
                issued(send(args, "POST", "/keys/" + nth(words, 1, "key rotate <keyId>") + "/rotate", Map.of()));
            case "revoke" -> {
                String id = nth(words, 1, "key revoke <keyId>");
                send(args, "DELETE", "/keys/" + id, null);
                out.println("revoked " + id);
            }
            case "report" -> {
                JsonNode report = send(args, "GET", "/keys/report", null);
                for (String part : List.of("unused", "expiring", "superseded")) {
                    out.println(part + ":");
                    table(report.path(part), "keyId", "name", "holder", "expiresAt", "lastUsedAt");
                }
            }
            default -> throw new Args.UsageException("key takes list, create, rotate, revoke or report");
        }
        return 0;
    }

    private void issued(JsonNode key) {
        out.println("key " + key.path("keyId").asText() + ", expires "
                + key.path("expiresAt").asText()
                + (key.hasNonNull("oldExpiresAt")
                        ? "; the old key works until "
                                + key.path("oldExpiresAt").asText()
                        : "")
                + ". Shown once:");
        out.println(key.path("key").asText());
    }

    private int session(Args args, String verb, List<String> words) {
        switch (verb) {
            case "list" ->
                table(
                        send(args, "GET", "/sessions" + (args.has("all") ? "?all=true" : ""), null)
                                .path("sessions"),
                        "id",
                        "username",
                        "createdAt",
                        "lastSeenAt",
                        "expiresAt",
                        "current");
            case "end" -> {
                String id = nth(words, 1, "session end <id>");
                send(args, "DELETE", "/sessions/" + id, null);
                out.println("ended " + id);
            }
            default -> throw new Args.UsageException("session takes list or end");
        }
        return 0;
    }

    // ------------------------------------------------------------------ plumbing

    /** The engine refused; its code and message, which is all a script needs. */
    static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    private JsonNode send(Args args, String method, String path, Object body) {
        String base = args.get("http", System.getenv().getOrDefault("PRAVAHA_ENGINE_HTTP", DEFAULT_HTTP));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30));
        args.get("token").ifPresent(token -> request.header("Authorization", "Bearer " + token));
        try {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode answer = response.body() == null || response.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(response.body());
            if (response.statusCode() >= 400) {
                throw new Refused(answer.path("code").asText("HTTP " + response.statusCode()) + "  "
                        + answer.path("message").asText(response.body()));
            }
            return answer;
        } catch (java.io.IOException e) {
            throw new Refused("cannot reach the engine at " + base + ": " + e.getMessage()
                    + " (set --http or PRAVAHA_ENGINE_HTTP)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused("interrupted");
        }
    }

    private void table(JsonNode rows, String... columns) {
        List<String[]> lines = new ArrayList<>();
        lines.add(java.util.Arrays.stream(columns)
                .map(c -> c.toUpperCase(java.util.Locale.ROOT))
                .toArray(String[]::new));
        for (JsonNode row : rows) {
            String[] line = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                JsonNode value = row.path(columns[i]);
                line[i] = value.isArray()
                        ? String.join(",", json.convertValue(value, String[].class))
                        : value.isNull() || value.isMissingNode() ? "-" : value.asText();
            }
            lines.add(line);
        }
        int[] widths = new int[columns.length];
        lines.forEach(l -> {
            for (int i = 0; i < l.length; i++) {
                widths[i] = Math.max(widths[i], l[i].length());
            }
        });
        for (String[] line : lines) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < line.length; i++) {
                text.append(i == 0 ? "" : "  ").append(String.format("%-" + widths[i] + "s", line[i]));
            }
            out.println(text.toString().stripTrailing());
        }
    }

    /**
     * {@code pravaha lanes}: where every query runs; {@code lanes rebalance}: the plan an administrator
     * would run, and with {@code --yes} the run itself; {@code lanes rebalance status}: how it went.
     * A rebalance is never started without {@code --yes}, because it moves running queries.
     */
    private int lanes(Args args, List<String> words) {
        if (words.isEmpty()) {
            JsonNode summary = send(args, "GET", "/lanes", null);
            out.println("mode " + summary.path("mode").asText()
                    + (summary.path("autoFrom").isNull()
                            ? ""
                            : ", a lane each until " + summary.path("autoFrom").asInt())
                    + ", at most " + summary.path("maxQueriesPerLane").asInt() + " per shared lane; "
                    + summary.path("hosted").asInt() + " hosted, "
                    + summary.path("ownLaneQueries").asInt()
                    + " on lanes of their own ("
                    + summary.path("dedicatedQueries").asInt() + " dedicated)");
            table(send(args, "GET", "/queries", null), "name", "state", "lane", "sharedLane");
            return 0;
        }
        if (!words.get(0).equals("rebalance")) {
            throw new Args.UsageException("usage: pravaha lanes [rebalance [status] [--yes]]");
        }
        JsonNode plan;
        if (words.size() > 1 && words.get(1).equals("status")) {
            plan = send(args, "GET", "/lanes/rebalance", null);
        } else if (args.has("yes")) {
            plan = send(args, "POST", "/lanes/rebalance", Map.of());
        } else {
            plan = send(args, "POST", "/lanes/rebalance?dryRun=true", Map.of());
        }
        out.println("mode " + plan.path("mode").asText() + ", room for "
                + plan.path("room").asInt() + " more on lanes of their own"
                + (plan.path("running").asBoolean() ? "; running" : ""));
        if (plan.path("moves").isEmpty()) {
            out.println("nothing to move");
        } else {
            table(plan.path("moves"), "name", "fromSharedLane", "status", "detail");
        }
        if (!args.has("yes") && words.size() == 1 && !plan.path("moves").isEmpty()) {
            out.println("a plan only: run 'pravaha lanes rebalance --yes' to move these, one at a time");
        }
        return 0;
    }

    private static List<String> roles(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String nth(List<String> words, int index, String usage) {
        if (words.size() <= index) {
            throw new Args.UsageException("usage: pravaha " + usage);
        }
        return words.get(index);
    }
}

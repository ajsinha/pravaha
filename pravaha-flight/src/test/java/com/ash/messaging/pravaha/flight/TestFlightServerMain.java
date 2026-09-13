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
package com.ash.messaging.pravaha.flight;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * A server with a known view, for a client in another language to talk to.
 *
 * <p>The Python SDK's tests need a real Pravaha to query, and a Python fake would test the fake. So
 * this starts the actual server with fixed data, prints the port, and waits -- the Python test reads
 * the port, runs its queries against it, and stops it.
 *
 * <p>It lives in test sources because it is a fixture, not a product: a deployment starts
 * {@link PravahaFlightServer} with its own views.
 *
 * <p>Arguments: a port (zero to let the operating system choose), and optionally
 * {@code --authenticated} to require a bearer credential and enforce a policy, which is how the
 * Python SDK's authentication tests get a real server to be refused by.
 *
 * <p>It also tails a file and feeds what appears there into every registered query over the
 * {@code trade} stream, printing the path as {@code PRAVAHA_FEED_FILE=}. Without that, a client in
 * another language could register a continuous query, subscribe to it, and wait for ever -- which
 * is exactly what the Python SDK's subscription test used to do, and why it asserted the shape of
 * the call rather than the delivery of a row. A continuous query nothing feeds is not being tested;
 * its API is.
 */
public final class TestFlightServerMain {

    /**
     * Tails {@code feedFile} and pushes each line into every registered query over {@code trade}.
     *
     * <p>Deliberately the push path rather than a source plugin: this module cannot depend on the
     * server or on a plugin without a cycle, and what a cross-language test needs from ingest is
     * that a row the client wrote reaches the query it registered. The plugin ingest paths have
     * their own tests in the modules that own them.
     *
     * <p>Line format: {@code trade_id,product_type,trade_json[,weight]}. The optional weight is
     * what lets a client in another language see a retraction, which is otherwise unreachable from
     * outside the engine.
     */
    private static void startFeeding(QueryRegistry registry, StreamSchema schema, java.nio.file.Path feedFile) {
        Thread feeder = new Thread(
                () -> {
                    com.ash.messaging.pravaha.common.arena.RowArena arena =
                            new com.ash.messaging.pravaha.common.arena.RowArena(
                                    com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 20, 8);
                    com.ash.messaging.pravaha.common.row.RowLayout layout =
                            com.ash.messaging.pravaha.common.row.RowLayout.of(schema);
                    com.ash.messaging.pravaha.common.row.BinaryRowWriter writer =
                            new com.ash.messaging.pravaha.common.row.BinaryRowWriter(layout);
                    com.ash.messaging.pravaha.common.row.BinaryRowView view =
                            new com.ash.messaging.pravaha.common.row.BinaryRowView(layout);
                    long sequence = 0;
                    long consumed = 0;
                    // Reported once. A fixture polling every 25ms and printing on each failure
                    // fills the pipe its harness reads, and a full pipe stops the server -- which
                    // presents as a subscription that never delivers.
                    boolean reported = false;
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            List<String> lines = java.nio.file.Files.readAllLines(feedFile);
                            for (long i = consumed; i < lines.size(); i++) {
                                String line = lines.get((int) i).strip();
                                if (line.isEmpty()) {
                                    continue;
                                }
                                String[] parts = line.split(",", -1);
                                long weight = parts.length > 3 ? Long.parseLong(parts[3].strip()) : 1L;
                                long handle = arena.allocate(layout.rowSize(512));
                                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                                writer.setString(0, parts[0])
                                        .setString(1, parts.length > 1 ? parts[1] : "")
                                        .setString(2, parts.length > 2 ? parts[2] : "")
                                        .weight(weight)
                                        .eventTimestampNanos(++sequence)
                                        .sequence(sequence)
                                        .commit();
                                arena.trimTo(handle, writer.sizeSoFar());
                                for (com.ash.messaging.pravaha.registry.RegisteredQuery query : registry.queries()) {
                                    query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
                                    query.awaitApplied(java.time.Duration.ofSeconds(5));
                                    query.commit();
                                }
                            }
                            consumed = lines.size();
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException | java.io.IOException e) {
                            // A fixture that died on one bad line would take every test with it.
                            if (!reported) {
                                reported = true;
                                System.err.println("feeder: " + e);
                            }
                        }
                    }
                },
                "pravaha-test-feeder");
        feeder.setDaemon(true);
        feeder.start();
    }

    /** Fixed so a client in another language can be written against them. */
    public static final String ANALYST_TOKEN = "analyst-token-1";

    public static final String INTERN_TOKEN = "intern-token-1";

    private TestFlightServerMain() {}

    public static void main(String[] args) throws Exception {
        StreamSchema schema = StreamSchema.builder("user_volume")
                .field("user_id", Types.string())
                .field("tier", Types.string().withNullable(true))
                .field("total", Types.int64())
                .build();

        ServedView view = new ServedView("user_volume", schema, List.of(0), 100_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 10);
        view.commit(10);

        StreamSchema wide = StreamSchema.builder("readings")
                .field("id", Types.int64())
                // "value" is a reserved word in SQL and would need quoting at every call site.
                .field("reading", Types.float64())
                .build();
        ServedView readings = new ServedView("readings", wide, List.of(0), 100_000);
        for (long i = 0; i < 5_000; i++) {
            readings.applyValues(new Object[] {i, i * 1.5d}, 1, 10);
        }
        readings.commit(10);

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        boolean authenticated = args.length > 1 && "--authenticated".equals(args[1]);

        ViewCatalog catalog = new ViewCatalog().register(view).register(readings);

        // A registry, so a client in another language can register a continuous query and subscribe
        // to it -- which is most of what an SDK has to be able to do and none of what a fixture
        // serving two fixed views would exercise.
        StreamSchema tradeSchema = StreamSchema.builder("trade")
                .field("trade_id", Types.string())
                .field("product_type", Types.string())
                .field("trade_json", Types.string())
                .build();
        QueryRegistry registry = new QueryRegistry(catalog, SecurityPolicy.PERMISSIVE, AuditSink.NONE, tradeSchema);

        PravahaFlightServer configured = new PravahaFlightServer(catalog).hosting(registry);
        if (authenticated) {
            // The same shape a deployment uses: a verifier that says who a credential belongs to,
            // and a policy that says what that principal may read. The tokens are fixed because a
            // cross-language test needs both sides to agree on them; a deployment's come from its
            // identity provider.
            configured
                    .authenticatedBy(StaticTokenVerifier.of(
                                    ANALYST_TOKEN,
                                    new Principal("dana", "acme", Set.of("analyst"), Map.of("tier", "gold")))
                            .and(INTERN_TOKEN, new Principal("sam", "acme", Set.of("intern"), Map.of())))
                    .authorizedBy(
                            (principal, viewName) -> principal.hasRole("analyst")
                                    ? AccessDecision.allowWithRowFilter(
                                            "tier = '" + principal.claim("tier").orElse("none") + "'")
                                    : AccessDecision.deny("only analysts read " + viewName),
                            AuditSink.NONE);
        }

        java.nio.file.Path feedFile = java.nio.file.Files.createTempFile("pravaha-feed", ".csv");
        startFeeding(registry, tradeSchema, feedFile);

        try (PravahaFlightServer server = configured.start("localhost", port)) {
            // The port, on its own line, so a test harness can read it without parsing logs.
            System.out.println("PRAVAHA_FLIGHT_PORT=" + server.port());
            // Likewise the feed file: a client appends a line to it and its continuous query sees
            // the row, which is the whole of what makes a cross-language streaming test possible.
            System.out.println("PRAVAHA_FEED_FILE=" + feedFile.toAbsolutePath());
            System.out.flush();
            Thread.currentThread().join();
        }
    }
}

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
import com.ash.messaging.pravaha.security.ViewNames;
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
    /**
     * FEED-1, for a client in another language: a query registered under a name starting {@code
     * stalled_} gets a feed whose source has already stopped with {@code PRV-5040} at {@code
     * trade#0}, so an SDK's listing can be checked against a real server. Every other name has no
     * feed of its own and is fed by {@link #startFeeding}, as before.
     */
    static com.ash.messaging.pravaha.registry.SourceFeed stalledFeeds(
            String name,
            com.ash.messaging.pravaha.runtime.exec.QueryExecution execution,
            List<String> streams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom) {
        if (!name.startsWith("stalled_")) {
            return com.ash.messaging.pravaha.registry.SourceFeed.NONE;
        }
        com.ash.messaging.pravaha.registry.FeedStatus status = com.ash.messaging.pravaha.registry.FeedStatus.of(
                "reading trade (1 partition)",
                List.of(new com.ash.messaging.pravaha.registry.FeedStatus.Source(
                        "trade",
                        0,
                        false,
                        com.ash.messaging.pravaha.registry.FeedStatus.SourceState.STOPPED,
                        new com.ash.messaging.pravaha.registry.FeedStatus.Stop(
                                new com.ash.messaging.pravaha.api.PravahaException(
                                        new com.ash.messaging.pravaha.api.ErrorCode(5040, "FILESYSTEM_DECODE_FAILED"),
                                        "line 3: 'abc' is not an INT64"),
                                java.time.Instant.parse("2026-09-19T08:00:00Z"),
                                true))));
        return new com.ash.messaging.pravaha.registry.SourceFeed() {
            @Override
            public com.ash.messaging.pravaha.registry.FeedStatus status() {
                return status;
            }

            @Override
            public String describe() {
                return status.description();
            }

            @Override
            public void pause() {}

            @Override
            public void resume() {}

            @Override
            public long rowsFed() {
                return 0;
            }

            @Override
            public void close() {}
        };
    }

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

    /** The tenant both fixed principals are in, and so the tenant the fixed views are served from. */
    public static final String FIXTURE_TENANT = "acme";

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

        // FLIGHTDECIMAL-1: a DECIMAL column, so a client in another language can check that what it
        // reads is the exact value at the column's scale -- a zero at scale 10 included, which is the
        // one a client printing with its language's default turns into 0E-10.
        StreamSchema ledgerSchema = StreamSchema.builder("ledger")
                .field("entry_id", Types.string())
                .field("amount", Types.decimal(18, 2))
                .field("rate", Types.decimal(38, 10).withNullable(true))
                .build();
        ServedView ledger = new ServedView("ledger", ledgerSchema, List.of(0), 100);
        ledger.applyValues(
                new Object[] {
                    "e1", new java.math.BigDecimal("1234567890123456.78"), new java.math.BigDecimal("0.0000000001")
                },
                1,
                10);
        ledger.applyValues(
                new Object[] {"e2", new java.math.BigDecimal("-0.01"), new java.math.BigDecimal("0E-10")}, 1, 10);
        ledger.applyValues(new Object[] {"e3", new java.math.BigDecimal("0.00"), null}, 1, 10);
        ledger.commit(10);

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        boolean authenticated = args.length > 1 && "--authenticated".equals(args[1]);

        // The two fixed views live in the tenant the callers are in. Views are keyed by (tenant, name)
        // (ADR-060), and a principal plans against its own tenant's views only, so with both views in
        // the default tenant and both principals in "acme", every read over --authenticated answered
        // PRV-4023 "no views are registered" -- correctly -- and the Python SDK's three authorization
        // tests failed while the Maven gate, which does not run them, stayed green (SDK-AUTH). An
        // unauthenticated caller is in the default tenant, so there the views stay bare-named.
        String tenant = authenticated ? FIXTURE_TENANT : ViewNames.DEFAULT_TENANT;
        ViewCatalog catalog = new ViewCatalog()
                .registerAs(ViewNames.engineName(tenant, view.name()), view)
                .registerAs(ViewNames.engineName(tenant, readings.name()), readings)
                .registerAs(ViewNames.engineName(tenant, ledger.name()), ledger);

        // A registry, so a client in another language can register a continuous query and subscribe
        // to it -- which is most of what an SDK has to be able to do and none of what a fixture
        // serving two fixed views would exercise.
        StreamSchema tradeSchema = StreamSchema.builder("trade")
                .field("trade_id", Types.string())
                .field("product_type", Types.string())
                .field("trade_json", Types.string())
                .build();
        // One policy object, shared by the registry and the server.
        //
        // The registry used to be built with PERMISSIVE and the server handed a different policy
        // afterwards. The engine refuses that on purpose -- registering would be judged by one and
        // reading by the other, and the more permissive would decide -- so the authenticated
        // fixture never started, and the Python SDK's five authentication tests skipped every run
        // saying "the Pravaha server did not start; is the module built?". A skip that names the
        // wrong cause is worse than a failure, because it reads as environmental.
        SecurityPolicy policy = authenticated
                ? (principal, viewName) -> principal.hasRole("analyst")
                        ? AccessDecision.allowWithRowFilter(
                                "tier = '" + principal.claim("tier").orElse("none") + "'")
                        : AccessDecision.deny("only analysts read " + viewName)
                : SecurityPolicy.PERMISSIVE;
        // A source that has nothing in it, so that a client in another language can drive a
        // blue/green replacement here (ADR-046): a backfill over an empty stream is caught up at
        // its first poll. Rows still arrive by being pushed, exactly as they did before.
        QueryRegistry registry = new QueryRegistry(catalog, policy, AuditSink.NONE, tradeSchema)
                .feedingFrom(new QuietBackfillSource())
                .writingTo(new BrokenSinks());

        PravahaFlightServer configured = new PravahaFlightServer(catalog).hosting(registry);
        if (authenticated) {
            // The same shape a deployment uses: a verifier that says who a credential belongs to,
            // and a policy that says what that principal may read. The tokens are fixed because a
            // cross-language test needs both sides to agree on them; a deployment's come from its
            // identity provider.
            configured
                    .authenticatedBy(StaticTokenVerifier.of(
                                    ANALYST_TOKEN,
                                    new Principal("dana", FIXTURE_TENANT, Set.of("analyst"), Map.of("tier", "gold")))
                            .and(INTERN_TOKEN, new Principal("sam", FIXTURE_TENANT, Set.of("intern"), Map.of())))
                    .authorizedBy(policy, AuditSink.NONE);
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

    /**
     * One sink binding, {@code broken_sink}, which refuses every batch (SINK-3).
     *
     * <p>So that a client in another language can see {@code PRV-8009} on a real server: a query
     * registered against it is detached at its first commit, stays {@code RUNNING} with its view
     * right, and its listing says the sink is gone and why. The configured "password" is there to
     * be struck out -- the failure a listing carries must not carry a credential with it, and an
     * SDK's test is where that is worth proving from outside.
     */
    static final class BrokenSinks implements com.ash.messaging.pravaha.registry.SinkFactory {

        private static final String PASSWORD = "fixture-secret-password";

        @Override
        public com.ash.messaging.pravaha.api.plugin.SinkCapabilities capabilitiesOf(String sinkName) {
            if (!"broken_sink".equals(sinkName)) {
                throw new IllegalArgumentException("no sink is bound to '" + sinkName + "'");
            }
            return com.ash.messaging.pravaha.api.plugin.SinkCapabilities.appendOnly();
        }

        @Override
        public com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin open(String sinkName) {
            capabilitiesOf(sinkName);
            return new BrokenSink();
        }

        @Override
        public String redact(String text) {
            return text == null ? null : text.replace(PASSWORD, "[redacted password]");
        }

        @Override
        public void release(com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin plugin) {}

        private static final class BrokenSink implements com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin {

            @Override
            public com.ash.messaging.pravaha.api.plugin.SinkCapabilities capabilities() {
                return com.ash.messaging.pravaha.api.plugin.SinkCapabilities.appendOnly();
            }

            @Override
            public int write(List<com.ash.messaging.pravaha.api.data.RowView> batch) {
                throw new IllegalStateException("could not reach the fixture's table with password=" + PASSWORD);
            }

            @Override
            public void flush() {}

            @Override
            public String name() {
                return "broken";
            }

            @Override
            public com.ash.messaging.pravaha.api.plugin.Version version() {
                return com.ash.messaging.pravaha.api.plugin.Version.apiVersion();
            }

            @Override
            public void configure(com.ash.messaging.pravaha.api.plugin.PluginContext context) {}

            @Override
            public void open() {}

            @Override
            public void close() {}
        }
    }
}

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
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
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
 */
public final class TestFlightServerMain {

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

        PravahaFlightServer configured =
                new PravahaFlightServer(new ViewCatalog().register(view).register(readings));
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

        try (PravahaFlightServer server = configured.start("localhost", port)) {
            // The port, on its own line, so a test harness can read it without parsing logs.
            System.out.println("PRAVAHA_FLIGHT_PORT=" + server.port());
            System.out.flush();
            Thread.currentThread().join();
        }
    }
}

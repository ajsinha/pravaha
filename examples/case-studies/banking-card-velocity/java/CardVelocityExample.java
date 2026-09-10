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
 */package examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * Registering this case study's continuous query, and reading the view it maintains.
 *
 * <p>A template rather than a program to run unchanged: the schemas and the plugin configuration
 * belong to your deployment, and this file marks where they go. What is exact is the shape --
 * register once, read many times, bind every value.
 *
 * <p>The SQL is read from the {@code sql/} directory beside this file rather than pasted into a
 * string. That is deliberate: those files are checked against the real planner by the build, so SQL
 * that stopped working would fail CI rather than fail in your hands.
 */
public final class CardVelocityExample {

    private static final Path SQL = Path.of("..", "sql");

    private CardVelocityExample() {}

    public static void main(String[] args) throws Exception {
        // ---------------------------------------------------------------------------------
        // 1. Register the continuous query. It runs until it is dropped, maintaining a view
        //    named "card_velocity" that ordinary SQL can read.
        // ---------------------------------------------------------------------------------
        ViewCatalog views = new ViewCatalog();
        QueryRegistry registry = new QueryRegistry(
                views,
                // Your stream schemas go here -- the same fields as schema/streams.properties.
                // See that file; it is what the build checks this study's SQL against.
                sourceSchema(),
                lookupSchema());

        RegisteredQuery query = registry.register(
                "card_velocity",
                Files.readString(firstMatching("01-continuous")),
                List.of(1), // key the view by the first grouping column
                Principal.of("case-study"));

        System.out.println("registered " + query + " -> view 'card_velocity'");

        // ---------------------------------------------------------------------------------
        // 2. Read it, over Arrow Flight SQL, exactly as an application would.
        // ---------------------------------------------------------------------------------
        try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090")) {
            String sql = Files.readString(firstMatching("02-read"));

            // Bound, never concatenated. The value is never parsed as SQL, and the server plans
            // this statement once however many keys you look up.
            try (QueryResult result = client.query(sql, args.length > 0 ? args[0] : "unknown")) {
                for (Row row : result) {
                    System.out.println(row);
                }
            }
        }

        registry.close();
    }

    private static Path firstMatching(String prefix) throws Exception {
        try (var files = Files.list(SQL)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no " + prefix + " SQL in " + SQL));
        }
    }

    private static com.ash.messaging.pravaha.api.data.StreamSchema sourceSchema() {
        throw new UnsupportedOperationException(
                "fill this in from schema/streams.properties -- the source stream's fields");
    }

    private static com.ash.messaging.pravaha.api.data.StreamSchema lookupSchema() {
        throw new UnsupportedOperationException(
                "fill this in from schema/streams.properties -- the lookup table's fields");
    }
}

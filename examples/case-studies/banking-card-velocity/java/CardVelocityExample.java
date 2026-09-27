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
package examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.sdk.flight.ChangeBatch;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.RegisteredQueryInfo;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * This case study, end to end, through the published SDK.
 *
 * <p>Everything here goes over the wire to a running server. That is the whole shape of the thing
 * worth copying: <strong>the client holds no schemas and no engine</strong>. It sends SQL and reads
 * answers. The stream definitions live on the server, where the data is, and a client that had to
 * know them would be a client that has to be redeployed when a column is added.
 *
 * <p>Run a server first, then:
 *
 * <pre>
 *   java CardVelocityExample grpc://localhost:19090
 * </pre>
 */
public final class CardVelocityExample {

    private static final Path SQL = Path.of("..", "sql");

    private CardVelocityExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {

            // 1. Register the continuous query. It runs until it is dropped, maintaining a view
            //    named "card_velocity" that ordinary SQL can read. Registering the same question
            //    again -- even worded differently -- returns the same computation rather than a
            //    second one; the fingerprint is how you can tell.
            RegisteredQueryInfo registered =
                    client.register("card_velocity", read("01-continuous-card-velocity.sql"), List.of(1));
            System.out.println("registered " + registered);

            // 2. Ask it a question. The value is bound, never concatenated: a bound value is never
            //    parsed as SQL, and the server plans the statement once however many you ask about.
            try (QueryResult result = client.query(read("02-read-one-card.sql"), "c-1002")) {
                for (Row row : result) {
                    System.out.println(row.columns() + " -> " + row.getString(0));
                }
            }

            // 3. Watch it. The filter is applied at the tap -- only the high-risk band, so a fraud desk sees its own slice -- so rows this
            //    consumer did not ask for never cross the network, and every other consumer is
            //    reading the same computation with its own filter.
            try (Subscription subscription = client.subscribe("card_velocity", Map.of("risk_band", "HIGH"), batch -> {
                // One batch is one commit, never a partial window. Rows are flyweights over the
                // Arrow buffer that carried them, so copy anything kept past this callback.
                System.out.println("-- commit of " + batch.size() + " row(s)");
                for (Row row : batch) {
                    System.out.println("   " + row.getString(0));
                }
            })) {
                System.out.println("watching card_velocity; Ctrl-C to stop");
                subscription.run();
            }
        }
    }

    private static String read(String name) throws Exception {
        return Files.readString(SQL.resolve(name)).strip();
    }
}

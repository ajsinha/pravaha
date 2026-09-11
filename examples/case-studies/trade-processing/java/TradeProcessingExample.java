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
 *   java TradeProcessingExample grpc://localhost:9090
 * </pre>
 */
public final class TradeProcessingExample {

    private static final Path SQL = Path.of("..", "sql");

    private TradeProcessingExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:9090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {

            // 1. Register the continuous query. It runs until it is dropped, maintaining a view
            //    named "trade_feed" that ordinary SQL can read. Registering the same question
            //    again -- even worded differently -- returns the same computation rather than a
            //    second one; the fingerprint is how you can tell.
            RegisteredQueryInfo registered =
                    client.register("trade_feed", read("01-continuous-trade-feed.sql"), List.of(1));
            System.out.println("registered " + registered);

            // 2. Ask it a question. The value is bound, never concatenated: a bound value is never
            //    parsed as SQL, and the server plans the statement once however many you ask about.
            // Two placeholders, two bound values, in order.
            try (QueryResult result =
                    client.query(read("02-read-by-product-and-source.sql"), "SWAP", "MUREX")) {
                for (Row row : result) {
                    System.out.println(row.columns() + " -> " + row.getString(0));
                }
            }

            // 3. Watch it. The filter is applied at the tap -- the rates desk's slice: swaps from Murex and nothing else -- so rows this
            //    consumer did not ask for never cross the network, and every other consumer is
            //    reading the same computation with its own filter.
            try (Subscription subscription = client.subscribe("trade_feed", Map.of("product_type", "SWAP", "source_system", "MUREX"), batch -> {
                // One batch is one commit, never a partial window. Rows are flyweights over the
                // Arrow buffer that carried them, so copy anything kept past this callback.
                System.out.println("-- commit of " + batch.size() + " row(s)");
                for (Row row : batch) {
                    System.out.println("   " + row.getString(0));
                }
            })) {
                System.out.println("watching trade_feed; Ctrl-C to stop");
                subscription.run();
            }
        }
    }

    private static String read(String name) throws Exception {
        return Files.readString(SQL.resolve(name)).strip();
    }
}

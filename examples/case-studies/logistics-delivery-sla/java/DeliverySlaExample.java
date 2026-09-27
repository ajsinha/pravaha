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

import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The delivery-SLA study, end to end, through the published Java SDK.
 *
 * <p>Start a node from the study's directory with {@code conf/application.yaml}, generate the data,
 * then, from {@code java/}:
 *
 * <pre>
 *   java DeliverySlaExample grpc://localhost:19090
 * </pre>
 */
public final class DeliverySlaExample {

    private static final Path SQL = Path.of("..", "sql");

    private DeliverySlaExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. Late deliveries (a join with a two-to-four-hour bound), written to the
            //    sla_breaches file as well as the view; parcels with no delivery within four hours
            //    (a LEFT JOIN with a bound); and dispatches per depot per hour.
            client.register("late_deliveries", read("01-continuous-late-deliveries.sql"), List.of(0), "sla_breaches");
            client.register("undelivered", read("02-continuous-undelivered.sql"), List.of(0));
            client.register("depot_dispatches", read("03-continuous-depot-dispatches.sql"), List.of(0, 1));

            // 2. One parcel, by bound parameter.
            try (QueryResult result = client.query(read("05-read-one-shipment.sql"), "P1009")) {
                for (Row row : result) {
                    System.out.println(row);
                }
            }

            // 3. Follow one depot's missing parcels. A parcel appears here once, when both streams
            //    are four hours past its dispatch, and is never withdrawn.
            try (Subscription subscription =
                    client.subscribe("undelivered", Map.of("depot", "BRS3"), batch -> {
                        for (Row row : batch) {
                            System.out.println("not delivered: " + row.getString("shipment_id"));
                        }
                    })) {
                subscription.run();
            }
        }
    }

    private static String read(String name) throws Exception {
        return Files.readString(SQL.resolve(name)).strip();
    }
}

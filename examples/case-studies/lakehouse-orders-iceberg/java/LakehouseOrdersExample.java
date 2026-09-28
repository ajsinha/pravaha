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

import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.RegisteredQueryInfo;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The lakehouse orders study, end to end, through the published Java SDK.
 *
 * <p>Start a node from the study's directory with {@code conf/application.yaml}, generate the data,
 * then, from {@code java/}:
 *
 * <pre>
 *   java LakehouseOrdersExample grpc://localhost:19090
 * </pre>
 */
public final class LakehouseOrdersExample {

    private static final Path SQL = Path.of("..", "sql");

    private LakehouseOrdersExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. Revenue per region per hour, to its view and to the Iceberg table the node binds as
            //    hourly_revenue_table. The key -- (window_end, region) -- is the table's key.columns,
            //    so a corrected hour replaces its row in the table rather than adding one.
            RegisteredQueryInfo hourly = client.register(
                    "hourly_revenue", read("01-continuous-hourly-revenue.sql"), List.of(0, 1), "hourly_revenue_table");
            RegisteredQueryInfo categories =
                    client.register("category_revenue", read("02-continuous-category-revenue.sql"), List.of(0, 1));
            System.out.println("registered " + hourly + " and " + categories);

            // 2. The same answer the table holds, read from the view. Values are bound.
            try (QueryResult result = client.query(read("04-read-one-region.sql"), "UK")) {
                for (Row row : result) {
                    System.out.println(row.get("window_end") + " " + row.getLong("orders") + " orders, "
                            + row.getLong("revenue_minor") + " minor units");
                }
            }

            // 3. Follow it. A late order inside the lateness is one commit of -1 old row, +1 new:
            //    the view and the Iceberg table apply the same change.
            try (Subscription subscription = client.subscribe("hourly_revenue", batch -> {
                for (Row row : batch) {
                    System.out.println((row.isRetraction() ? "-1 " : "+1 ") + row);
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

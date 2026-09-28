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
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The retail inventory study, end to end, through the published Java SDK.
 *
 * <p>Start MySQL and a node from the study's directory with {@code conf/application.yaml}, then,
 * from {@code java/}:
 *
 * <pre>
 *   java InventoryExample grpc://localhost:19090
 * </pre>
 */
public final class InventoryExample {

    private static final Path SQL = Path.of("..", "sql");

    private InventoryExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. Two views over one table's change stream, both keyed by the table's primary key
            //    (sku, warehouse): a copy of the table, and the lines at or under their reorder point.
            client.register("stock_levels", read("01-continuous-stock-levels.sql"), List.of(0, 1));
            client.register("low_stock", read("02-continuous-low-stock.sql"), List.of(0, 1));

            // 2. Totals per warehouse, over rows that are always the table's current ones: an
            //    update retracted the old row before it inserted the new.
            try (QueryResult result = client.query(read("04-read-warehouse-totals.sql"))) {
                for (Row row : result) {
                    System.out.println(row.getString("warehouse") + " " + row.getLong("units") + " units on "
                            + row.getLong("lines") + " lines");
                }
            }

            // 3. Follow the alerts. +1 is a line falling under its reorder point; -1 is it leaving
            //    the view -- restocked, or deleted from the table. Apply the weights.
            try (Subscription subscription = client.subscribe("low_stock", batch -> {
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

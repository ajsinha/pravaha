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
 * The shared-Kafka payments study, end to end, through the published Java SDK.
 *
 * <p>Start Kafka and a node from the study's directory with {@code conf/application.yaml}, produce
 * the payments, then, from {@code java/}:
 *
 * <pre>
 *   java SharedPaymentsExample grpc://localhost:19090
 * </pre>
 */
public final class SharedPaymentsExample {

    private static final Path SQL = Path.of("..", "sql");

    private SharedPaymentsExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. Three questions over one topic. The first is a CREATE CONTINUOUS QUERY statement,
            //    because it declares an index -- INDEX (merchant) -- which is a clause of the
            //    statement, not a registration argument. The node reads the topic once for all three.
            try (QueryResult created = client.query(read("01-continuous-merchant-minute.sql"))) {
                created.forEach(row -> System.out.println("created " + row));
            }
            client.register("declines", read("02-continuous-declines.sql"), List.of(0));
            client.register("cross_border", read("03-continuous-cross-border.sql"), List.of(0));

            // 2. One merchant's minutes. merchant is not the whole key, so without the index this
            //    read would walk every row of the view; with it, it is one probe.
            try (QueryResult result = client.query(read("04-read-one-merchant.sql"), "m-coffee")) {
                for (Row row : result) {
                    System.out.println(row.get("window_end") + " " + row.getLong("payments") + " payments, "
                            + row.getLong("amount_minor") + " minor units");
                }
            }

            // 3. The risk desk follows declines; the cross-border desk would follow its own view.
            //    Neither costs another read of the topic.
            try (Subscription subscription = client.subscribe("declines", batch -> {
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

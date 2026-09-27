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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The checkout-funnel study, end to end, through the published Java SDK.
 *
 * <p>Start a node from the study's directory with {@code conf/application.yaml}, generate the data,
 * then, from {@code java/}:
 *
 * <pre>
 *   java CheckoutFunnelExample grpc://localhost:19090
 * </pre>
 */
public final class CheckoutFunnelExample {

    private static final Path SQL = Path.of("..", "sql");

    private CheckoutFunnelExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. The funnel, per five minutes, step and device; and the windows in which one device's
            //    payments failed three times or more.
            client.register("checkout_funnel", read("01-continuous-checkout-funnel.sql"), List.of(0, 1, 2));
            client.register(
                    "payment_failure_spikes", read("02-continuous-payment-failure-spikes.sql"), List.of(0, 1));

            // 2. Conversion is a division over two rows of one answer, done by the reader: a
            //    continuous query has no CASE, and needs none for this.
            Map<String, Long> sessions = new HashMap<>();
            try (QueryResult result = client.query(read("03-read-funnel-totals.sql"))) {
                for (Row row : result) {
                    sessions.put(row.getString("step"), row.getLong("sessions"));
                }
            }
            long baskets = sessions.getOrDefault("CART", 0L);
            for (String step : List.of("CHECKOUT", "PAYMENT", "ORDER")) {
                long reached = sessions.getOrDefault(step, 0L);
                System.out.printf("%-9s %3d of %d baskets = %.0f%%%n", step, reached, baskets,
                        baskets == 0 ? 0.0 : 100.0 * reached / baskets);
            }

            // 3. Page someone when a spike appears. Each commit is one closed window's spikes.
            try (Subscription subscription = client.subscribe("payment_failure_spikes", batch -> {
                for (Row row : batch) {
                    if (!row.isRetraction()) {
                        System.out.println("payment failures on " + row.getString("device") + ": "
                                + row.getLong("failures"));
                    }
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

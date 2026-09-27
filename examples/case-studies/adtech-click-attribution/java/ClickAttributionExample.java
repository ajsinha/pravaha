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
 * The click-attribution study, end to end, through the published Java SDK.
 *
 * <p>Start a node from the study's directory with {@code conf/application.yaml}, generate the data,
 * then, from {@code java/}:
 *
 * <pre>
 *   java ClickAttributionExample grpc://localhost:19090
 * </pre>
 */
public final class ClickAttributionExample {

    private static final Path SQL = Path.of("..", "sql");

    private ClickAttributionExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. The join of two streams -- a click counts if it follows its impression within ten
            //    minutes -- and two windowed views, one over the join and one over impressions.
            client.register("attributed_clicks", read("01-continuous-attributed-clicks.sql"), List.of(0));
            client.register("campaign_clicks", read("02-continuous-campaign-clicks.sql"), List.of(0, 1));
            client.register("campaign_impressions", read("03-continuous-campaign-impressions.sql"), List.of(0, 1));

            // 2. A read names one view, so the click-through rate is joined here, on the key the
            //    two views share: (window_end, campaign_id).
            Map<String, Long> clicks = new HashMap<>();
            try (QueryResult result = client.query("SELECT window_end, campaign_id, clicks FROM campaign_clicks")) {
                for (Row row : result) {
                    clicks.put(row.get("window_end") + "/" + row.getString("campaign_id"), row.getLong("clicks"));
                }
            }
            try (QueryResult result = client.query(read("04-read-campaign-impressions.sql"))) {
                for (Row row : result) {
                    long shown = row.getLong("impressions");
                    long clicked = clicks.getOrDefault(row.get("window_end") + "/" + row.getString("campaign_id"), 0L);
                    System.out.printf("%s %-12s %d/%d%n", row.get("window_end"), row.getString("campaign_id"),
                            clicked, shown);
                }
            }

            // 3. Follow attribution as it happens: one +1 per click matched to its impression.
            try (Subscription subscription = client.subscribe("attributed_clicks", batch -> {
                for (Row row : batch) {
                    System.out.println("attributed " + row.getString("click_id") + " to "
                            + row.getString("campaign_id"));
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

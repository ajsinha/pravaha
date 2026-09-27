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
 * The CDR fraud study, end to end, through the published Java SDK.
 *
 * <p>Start a node from the study's directory with {@code conf/application.yaml}, generate the data,
 * then, from {@code java/}:
 *
 * <pre>
 *   java CdrFraudExample grpc://localhost:9090
 * </pre>
 */
public final class CdrFraudExample {

    private static final Path SQL = Path.of("..", "sql");

    private CdrFraudExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:9090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. Three questions over one stream of call records: a sliding five-minute profile per
            //    caller, every long call to a premium destination, and the three busiest callers in
            //    each five-minute window.
            client.register("caller_velocity", read("01-continuous-caller-velocity.sql"), List.of(0, 1));
            client.register("premium_calls", read("02-continuous-premium-calls.sql"), List.of(0));
            client.register("top_callers", read("03-continuous-top-callers.sql"), List.of(0, 1));

            // 2. Who looks like a SIM box: thresholds are bound parameters, so tuning them is not a
            //    redeploy.
            try (QueryResult result = client.query(read("05-read-simbox-suspects.sql"), 8L, 8L)) {
                for (Row row : result) {
                    System.out.println(row.getString("caller") + " flagged in "
                            + row.getLong("windows_flagged") + " windows");
                }
            }

            // 3. Follow the leader board. A new window's top three arrives as one commit; a
            //    commit may renumber a row (a -1 of its old rank, a +1 of its new), so apply the
            //    weights rather than counting rows.
            try (Subscription subscription = client.subscribe("top_callers", batch -> {
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

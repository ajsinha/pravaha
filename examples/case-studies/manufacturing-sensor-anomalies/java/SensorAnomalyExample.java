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
import com.ash.messaging.pravaha.sdk.flight.RegisteredQueryInfo;
import com.ash.messaging.pravaha.sdk.flight.Row;
import com.ash.messaging.pravaha.sdk.flight.Subscription;

/**
 * The sensor-anomaly study, end to end, through the published Java SDK.
 *
 * <p>Start a node from the study's directory with {@code conf/application.yaml}, generate the data,
 * then, from {@code java/}:
 *
 * <pre>
 *   java SensorAnomalyExample grpc://localhost:19090
 * </pre>
 *
 * The client holds no schemas and no engine: it sends SQL and reads answers.
 */
public final class SensorAnomalyExample {

    private static final Path SQL = Path.of("..", "sql");

    private SensorAnomalyExample() {}

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "grpc://localhost:19090";

        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // 1. Two questions, registered once. The first keeps one row per machine per minute,
            //    keyed by (window_end, machine_id); the second passes every overheating reading
            //    through, to its view and to the overheat_alerts file the node binds as a sink.
            RegisteredQueryInfo health =
                    client.register("machine_health", read("01-continuous-machine-health.sql"), List.of(0, 1));
            RegisteredQueryInfo alerts = client.register(
                    "overheat_alerts", read("02-continuous-overheat-alerts.sql"), List.of(0), "overheat_alerts");
            System.out.println("registered " + health + " and " + alerts);

            // 2. Ask. Values are bound, never concatenated into the SQL.
            try (QueryResult result = client.query(read("03-read-one-machine.sql"), "press-02")) {
                for (Row row : result) {
                    System.out.println(row);
                }
            }
            try (QueryResult result = client.query(read("04-read-anomalous-minutes.sql"), 950L, 150L)) {
                for (Row row : result) {
                    System.out.println(row.getString("machine_id") + " " + row.getLong("max_temperature_dc"));
                }
            }

            // 3. Follow one machine. A late reading inside the stream's allowed lateness arrives as
            //    a -1 of the minute's old row and a +1 of the corrected one, in one commit.
            try (Subscription subscription =
                    client.subscribe("machine_health", Map.of("machine_id", "press-02"), batch -> {
                        System.out.println("-- commit of " + batch.size() + " change(s)");
                        for (Row row : batch) {
                            System.out.println("   " + (row.isRetraction() ? "-1 " : "+1 ") + row);
                        }
                    })) {
                System.out.println("following machine_health for press-02; Ctrl-C to stop");
                subscription.run();
            }
        }
    }

    private static String read(String name) throws Exception {
        return Files.readString(SQL.resolve(name)).strip();
    }
}

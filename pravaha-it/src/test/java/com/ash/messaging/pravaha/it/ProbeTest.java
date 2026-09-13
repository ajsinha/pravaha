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
package com.ash.messaging.pravaha.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBinding;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

class ProbeTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long T0 = 1_767_225_600_000_000_000L;
    private static final String SPEC = "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP";

    @Test
    void probeLateness(@TempDir Path root) throws Exception {
        for (Duration d : List.of(
                Duration.ofSeconds(60),
                Duration.ofSeconds(130),
                Duration.ofSeconds(200),
                Duration.ofMinutes(10),
                Duration.ofDays(3650))) {
            Path dir = root.resolve("d" + d.toSeconds());
            Files.createDirectories(dir);
            Path data = dir.resolve("ev.csv");
            StringBuilder csv = new StringBuilder();
            for (int k = 0; k <= 120; k++) {
                csv.append(k)
                        .append(",u")
                        .append(k % 5)
                        .append(',')
                        .append(k)
                        .append(',')
                        .append(T0 + k * SECOND)
                        .append('\n');
            }
            Files.writeString(data, csv);
            StreamSchema ev = StreamSchema.builder("ev")
                    .field("id", Types.int64())
                    .field("usr", Types.string())
                    .field("amount", Types.int64())
                    .field("event_time", Types.timestamp())
                    .eventTime("event_time")
                    .outOfOrderness(d)
                    .build();
            PluginSourceFeeds feeds = new PluginSourceFeeds()
                    .bind(new SourceBinding(
                            "ev",
                            "filesystem",
                            Map.of("path", data.toString(), "schema", SPEC, "event.time", "event_time")));
            ViewCatalog views = new ViewCatalog();
            long start = System.nanoTime();
            String outcome;
            try (QueryRegistry registry = new QueryRegistry(views, ev)
                    .feedingFrom(feeds)
                    .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))) {
                RegisteredQuery q = registry.register(
                        "w",
                        "SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS total FROM "
                                + "TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                                + "GROUP BY window_start, window_end",
                        List.of(0),
                        Principal.ANONYMOUS);
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < deadline && q.rowsIn() < 121) {
                    Thread.sleep(10);
                }
                Thread.sleep(500);
                outcome = "rows="
                        + new ViewQuery(views).execute("SELECT * FROM w").size();
            } catch (RuntimeException e) {
                outcome = "CLOSE THREW " + e.getMessage();
            }
            System.out.println("PROBE d=" + d + " " + outcome + " closeMs=" + (System.nanoTime() - start) / 1_000_000);
        }
    }
}

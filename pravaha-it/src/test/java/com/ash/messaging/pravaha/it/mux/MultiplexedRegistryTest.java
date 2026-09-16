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
package com.ash.messaging.pravaha.it.mux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many registered queries on one lane, through the registry rather than through the seam (W9-8).
 *
 * <p>{@code HostedQueryTest} proves {@code QueryExecution.startOn} shares a lane between two
 * executions. This proves the registry actually uses it: with {@code pravaha.lane.multiplex} on, two
 * separately registered queries over the same stream run on <em>one</em> lane, and each still gets
 * its own answer.
 *
 * <p>The property worth testing is dispatch, not arithmetic. A multiplexer that handed every batch
 * to every pipeline would also produce two correct answers here, so the two queries are deliberately
 * given <em>different</em> filters over the same rows: a shared computation, a shared inbox and a
 * shared arena, and two answers that differ. Anything that fanned rows out wrongly gives the same
 * answer twice or the wrong counts.
 */
@Timeout(120)
final class MultiplexedRegistryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    @Test
    void twoRegisteredQueriesShareOneLaneAndKeepTheirOwnAnswers(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("txn.csv");
        // Four rows, chosen so the two filters below select different, overlapping subsets.
        Files.writeString(data, "ann,100\nbob,250\ncat,50\ndan,400\n");

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn", "filesystem", Map.of("path", data.toString(), "schema", "user_id:STRING,amount:INT64")));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, TXN).feedingFrom(feeds).multiplexingLanes(true)) {

            RegisteredQuery big = registry.register(
                    "big_spenders",
                    "SELECT user_id, amount FROM txn WHERE amount > 200",
                    List.of(0),
                    Principal.ANONYMOUS);
            RegisteredQuery small = registry.register(
                    "small_spenders",
                    "SELECT user_id, amount FROM txn WHERE amount < 200",
                    List.of(0),
                    Principal.ANONYMOUS);

            big.awaitApplied(Duration.ofSeconds(30));
            small.awaitApplied(Duration.ofSeconds(30));

            // First, that multiplexing actually happened. Without this the test passes whether or
            // not the switch does anything: two queries on two separate lanes produce these same
            // two answers, so the answers alone prove nothing about sharing.
            assertThat(registry.pipelinesPerSharedLane())
                    .as("one shared lane, carrying both registered queries")
                    .containsExactly(2);

            ViewQuery reader = new ViewQuery(views);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline
                    && (reader.execute("SELECT user_id FROM big_spenders").size() < 2
                            || reader.execute("SELECT user_id FROM small_spenders")
                                            .size()
                                    < 2)) {
                Thread.sleep(20);
            }

            assertThat(reader.execute("SELECT user_id FROM big_spenders").rows().stream()
                            .map(row -> (String) row[0])
                            .sorted()
                            .toList())
                    .as("only the two rows over 200, on a lane it shares with a query selecting the other two")
                    .containsExactly("bob", "dan");

            assertThat(reader.execute("SELECT user_id FROM small_spenders").rows().stream()
                            .map(row -> (String) row[0])
                            .sorted()
                            .toList())
                    .as("and only the two under 200. Two answers from one inbox and one arena is the "
                            + "whole of W9-8; the same answer twice would mean the batch went to every pipeline")
                    .containsExactly("ann", "cat");
        }
    }
}

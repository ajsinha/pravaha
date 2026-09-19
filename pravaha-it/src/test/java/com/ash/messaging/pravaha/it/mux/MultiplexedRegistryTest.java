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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registered queries on shared lanes, fed by real source feeds rather than by hand (W9-8).
 *
 * <p>{@code HostedQueryTest} proves {@code QueryExecution.startOn} shares a lane between two
 * executions; {@code SharedLanePlacementTest} proves where the registry places them. This proves
 * the two together with the feed layer a node actually uses, where each registration opens a feed
 * of its own and every feed copies its stream into its query's lane.
 *
 * <p><strong>Counts, not projections.</strong> This test used to register two filtered projections
 * over one stream on one lane and read back the right rows from each -- and it passed while every
 * row reached each pipeline twice, once from each query's feed, because a keyed view absorbs a
 * duplicate upsert without a trace. A count does not. Two queries over the same stream now never
 * share a lane, and the second half of this test is what would fail if they did.
 */
@Timeout(120)
final class MultiplexedRegistryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA = "user_id:STRING,amount:INT64";

    private static RegisteredQuery count(QueryRegistry registry, String name, String stream, String where) {
        return registry.register(
                name,
                "SELECT COUNT(*) AS n, SUM(amount) AS total FROM " + stream + where,
                List.of(0),
                Principal.ANONYMOUS);
    }

    /**
     * Waits for a count's view to reach {@code n}, then gives the row it settled on.
     *
     * <p>Once the count has an answer, every read of it must find exactly one row. The feed's timer
     * commits this view on its own thread while this one commits it too, and each commit re-emits
     * the count on the lane as a retraction and an insert; a commit landing between the two used to
     * publish the retraction alone, and a read then found no row at all (VIEW-1). This used to read
     * past that -- an empty read just meant "poll again" -- and the one place it could not, the final
     * read, failed once under full-reactor load with {@code []}. It is asserted at every read now,
     * because an answer that disappears for one commit is the defect, not noise around it.
     */
    private static List<Object> settled(RegisteredQuery query, long n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean answered = false;
        while (System.nanoTime() < deadline) {
            query.commit();
            List<Object> row = onlyRow(query, answered);
            answered |= !row.isEmpty();
            if (!row.isEmpty() && ((Long) row.get(0)) >= n) {
                break;
            }
            Thread.sleep(20);
        }
        // Give a duplicate the time to arrive before the answer is judged: a count that reached n
        // and is about to pass it is the failure this test is for. Read throughout, not just at the
        // end, so a commit that publishes half an emission is caught whenever it lands.
        long judge = System.nanoTime() + Duration.ofMillis(300).toNanos();
        while (System.nanoTime() < judge) {
            query.commit();
            onlyRow(query, answered);
            Thread.sleep(5);
        }
        query.commit();
        return onlyRow(query, answered);
    }

    /** The view's single row; once the count has answered, anything but exactly one row fails. */
    private static List<Object> onlyRow(RegisteredQuery query, boolean answered) {
        List<Object[]> rows = query.view().scan();
        if (answered) {
            assertThat(rows)
                    .as("a count that has answered must go on answering: a commit published part of a lane's "
                            + "batch, the retraction of the old count without the insert of the new one")
                    .hasSize(1);
        }
        return rows.size() == 1 ? List.of(rows.get(0)) : List.of();
    }

    @Test
    void queriesOverTwoStreamsShareOneLaneAndCountEachRowOnce(@TempDir Path dir) throws Exception {
        Path txn = Files.writeString(dir.resolve("txn.csv"), "ann,100\nbob,250\ncat,50\ndan,400\n");
        Path orders = Files.writeString(dir.resolve("orders.csv"), "eve,10\nfay,20\n");

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", txn.toString(), "schema", SCHEMA)))
                .bind(new SourceBinding("orders", "filesystem", Map.of("path", orders.toString(), "schema", SCHEMA)));

        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN, ORDERS)
                .feedingFrom(feeds)
                .multiplexingLanes(1, 10)) {
            RegisteredQuery onTxn = count(registry, "txn_count", "txn", "");
            RegisteredQuery onOrders = count(registry, "orders_count", "orders", "");

            // First, that multiplexing actually happened: two queries on separate lanes would give
            // these same answers, so the answers alone prove nothing about sharing.
            assertThat(registry.pipelinesPerSharedLane())
                    .as("one shared lane, carrying both registered queries")
                    .containsExactly(2);

            assertThat(settled(onTxn, 4)).containsExactly(4L, 800L);
            assertThat(settled(onOrders, 2)).containsExactly(2L, 30L);
        }
    }

    @Test
    void twoQueriesOverOneStreamAreKeptApartAndEachCountsEachRowOnce(@TempDir Path dir) throws Exception {
        Path txn = Files.writeString(dir.resolve("txn.csv"), "ann,100\nbob,250\ncat,50\ndan,400\n");

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", txn.toString(), "schema", SCHEMA)));

        try (QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), TXN).feedingFrom(feeds).multiplexingLanes(1, 10)) {
            RegisteredQuery all = count(registry, "all_count", "txn", "");
            RegisteredQuery big = count(registry, "big_count", "txn", " WHERE amount > 200");

            assertThat(registry.pipelinesPerSharedLane()).containsExactly(1);
            assertThat(registry.queriesOnOwnLanes())
                    .as("the second query over txn, on a lane of its own")
                    .isEqualTo(1);

            // Each registration's feed copies all four rows into its own query's lane. Had both
            // queries been on the one shared lane, each pipeline would have been handed both copies:
            // eight and 1600, and four and 1300.
            assertThat(settled(all, 4)).containsExactly(4L, 800L);
            assertThat(settled(big, 2)).containsExactly(2L, 650L);
        }
    }
}

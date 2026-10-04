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
package com.ash.messaging.pravaha.it.qa.streaming;

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
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CONNECTORS.md} section 4, executed.
 *
 * <p>That section states, in bold, that a continuous query can join streams originating from
 * entirely different connectors, and then records honestly that the claim is "supported by
 * construction and not yet demonstrated by a test." Every other configured-source test in this
 * repository -- {@code IncrementalTest.incr003}/{@code incr005}, {@code WindowAnswerTest},
 * {@code EventTimeTest}, {@code SubscriptionAnswerTest} -- binds exactly one plugin type. The join
 * tests that do exercise two live streams ({@code ContinuousQueryAnswerTest.cq060}/{@code cq061})
 * push rows in by hand through {@code RegisteredQuery.accept}, which proves the join operator but
 * says nothing about two <em>different plugin implementations</em> feeding it.
 *
 * <p>This test binds {@code orders} to the {@code filesystem} plugin and {@code customers} to the
 * {@code feedfile} plugin -- genuinely different {@code StreamSourcePlugin} classes, reading through
 * different decoders, discovered separately by {@code ServiceLoader} -- and registers one join
 * across them. Neither plugin needs a container: {@code filesystem} reads one file once, and
 * {@code feedfile} with {@code completion: immediate} reads a directory once, so this runs in the
 * ordinary build.
 *
 * <p>Each side carries a row with no partner -- order {@code 3} for a customer that never arrives,
 * and customer {@code c5} for an order that never arrives -- so the assertion can fail the way a
 * cross product or a pass-through would fail it: those would produce nine rows or three, not two.
 */
@Timeout(60)
class CrossConnectorJoinTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.int64())
            .field("customer_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("customer_id", Types.string())
            .field("segment", Types.string())
            .build();

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    @Test
    void cx001_aJoinAcrossFilesystemAndFeedfileMatchesExactlyThePairsThatShareAKey(@TempDir Path dir) throws Exception {
        // The filesystem side: one file, read once. order 3 (customer c9) has no partner on the
        // other side.
        Path ordersFile = dir.resolve("orders.csv");
        Files.writeString(ordersFile, "1,c1,100\n2,c2,200\n3,c9,999\n");

        // The feedfile side: a directory a producer would drop files into. customer c5 has no
        // partner on the other side. completion:immediate is what lets this run without waiting
        // out feedfile's default 5s stability window -- the shape a real deployment would use is
        // 'stable' or 'marker', not this.
        Path customersDir = dir.resolve("customers");
        Files.createDirectories(customersDir);
        Files.writeString(customersDir.resolve("customers-01.csv"), "c1,gold\nc2,silver\nc5,bronze\n");

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "orders",
                        "filesystem",
                        Map.of(
                                "path",
                                ordersFile.toString(),
                                "schema",
                                "order_id:INT64,customer_id:STRING,amount:INT64")))
                .bind(new SourceBinding(
                        "customers",
                        "feedfile",
                        Map.of(
                                "dir", customersDir.toString(),
                                "schema", "customer_id:STRING,segment:STRING",
                                "completion", "immediate")));

        // Two distinct plugin names bound to two distinct streams -- the claim under test, stated
        // as data before a single row moves.
        assertThat(feeds.bindings().get("orders").plugin()).isEqualTo("filesystem");
        assertThat(feeds.bindings().get("customers").plugin()).isEqualTo("feedfile");

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, ORDERS, CUSTOMERS).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register(
                    "joined",
                    "SELECT o.order_id, c.segment, o.amount FROM orders o "
                            + "JOIN customers c ON o.customer_id = c.customer_id",
                    List.of(0),
                    Principal.ANONYMOUS);

            // Three rows from each plugin: six arrivals total, across two independently-discovered
            // StreamSourcePlugin instances feeding one query.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && query.rowsIn() < 6) {
                Thread.sleep(10);
            }
            assertThat(query.rowsIn())
                    .as("both plugins must have delivered everything they were configured to read")
                    .isEqualTo(6);
            query.awaitApplied(Duration.ofSeconds(20));

            ViewQuery reader = new ViewQuery(views);
            List<Object[]> rows = List.of();
            for (int pass = 0; pass < 100 && rows.size() < 2; pass++) {
                query.commit();
                rows = reader.execute("SELECT order_id, segment, amount FROM joined")
                        .rows();
                if (rows.size() < 2) {
                    Thread.sleep(20);
                }
            }

            // Two pairs, not nine (a cross product of 3x3) and not three or six (either side passed
            // through unjoined): the join actually matched on the key.
            assertThat(rows)
                    .as("exactly the two orders whose customer_id exists on the other side")
                    .hasSize(2);

            Map<Long, Object[]> byOrder = new java.util.HashMap<>();
            for (Object[] row : rows) {
                byOrder.put((Long) row[0], row);
            }
            assertThat(byOrder.keySet())
                    .as("order 3 (customer c9) has no partner and must not appear")
                    .containsExactlyInAnyOrder(1L, 2L);
            assertThat(byOrder.get(1L)[1]).as("order 1's customer c1 is gold").isEqualTo("gold");
            assertThat(byOrder.get(1L)[2]).isEqualTo(100L);
            assertThat(byOrder.get(2L)[1]).as("order 2's customer c2 is silver").isEqualTo("silver");
            assertThat(byOrder.get(2L)[2]).isEqualTo(200L);
            assertThat(rows.stream().map(row -> row[1]))
                    .as("customer c5 (bronze) has no partner and must not appear")
                    .doesNotContain("bronze");
        }
    }
}

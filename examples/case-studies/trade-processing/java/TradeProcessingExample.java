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
 */package examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionFilter;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * One registration, two desks, two different filters.
 *
 * <p>The point of the example is what is <em>not</em> here: there is no second query for the second
 * desk, and no second read of the store. Both subscriptions tap the same computation, because the
 * query aggregates nothing and therefore every column a desk might filter on survives into the view.
 */
public final class TradeProcessingExample {

    private TradeProcessingExample() {}

    /** The same fields as schema/streams.properties, which the build checks the SQL against. */
    private static StreamSchema tradeSchema() {
        return StreamSchema.builder("trade")
                .field("trade_id", Types.string())
                .field("product_type", Types.string())
                .field("source_system", Types.string())
                .field("trade_event_id", Types.int64())
                .field("trade_time", Types.timestamp())
                .field("trade_json", Types.string())
                .build();
    }

    public static void main(String[] args) throws Exception {
        ViewCatalog views = new ViewCatalog();
        QueryRegistry registry = new QueryRegistry(views, tradeSchema());

        RegisteredQuery feed = registry.register(
                "trade_feed",
                Files.readString(Path.of("..", "sql", "01-continuous-trade-feed.sql")),
                List.of(0), // keyed by trade_event_id: an amendment is a new event, not an overwrite
                Principal.of("trade-processing"));

        // The rates desk. Equality only -- this is a tap, not a query language.
        SubscriptionFilter swapsFromMurex = SubscriptionFilter.matching(
                feed.outputSchema(), Map.of("product_type", "SWAP", "source_system", "MUREX"));

        // FAIL rather than CONFLATE: a settlement or reporting consumer should be told it fell
        // behind, not quietly handed a feed with holes in it. CONFLATE is for dashboards.
        try (Subscription rates = feed.subscribe(
                        SubscriptionOptions.of(50_000, SubscriptionOptions.Overflow.FAIL),
                        swapsFromMurex,
                        changes -> changes.forEach(change -> System.out.println("rates  " + change.values()[5])));
                Subscription equities = feed.subscribe(
                        SubscriptionOptions.DEFAULT,
                        SubscriptionFilter.matching(feed.outputSchema(), "product_type", "EQUITY"),
                        changes -> changes.forEach(change -> System.out.println("equity " + change.values()[5])))) {

            System.out.println("two desks, one computation: " + registry.size() + " registered query");

            // Feed the query from your source plugin here. Each desk sees only its own trades.
            Thread.currentThread().join();
        } finally {
            registry.close();
        }
    }
}

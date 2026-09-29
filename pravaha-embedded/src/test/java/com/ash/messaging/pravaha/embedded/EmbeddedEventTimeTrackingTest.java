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
package com.ash.messaging.pravaha.embedded;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PravahaEngine#trackEventTime}: off by default, and when a stream opts in its event time
 * follows the rows pushed to it -- the greatest event time seen, less the allowed lateness.
 */
class EmbeddedEventTimeTrackingTest {

    private static final Instant BASE = Instant.parse("2026-09-29T10:00:00Z");

    private static final String PAGE_VIEWS = "SELECT window_start, page, COUNT(*) AS views "
            + "FROM TABLE(TUMBLE(TABLE clicks, DESCRIPTOR(ts), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, page";

    private static StreamSchema clicks() {
        return StreamSchema.builder("clicks")
                .field("page", Types.string())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    @Test
    void withoutItEventTimeIsTheHostsToAdvance() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream(clicks());
            engine.start();
            engine.register("page_views", PAGE_VIEWS, "window_start", "page");

            engine.push("clicks", row("home", 1), row("home", 2), row("home", 45));

            assertThat(engine.query("SELECT * FROM page_views").rows())
                    .as("no window closes until the host says event time has moved")
                    .isEmpty();
        }
    }

    @Test
    void withItAWindowClosesOnceTheLatestRowIsTheLatenessPastItsEnd() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream(clicks()).trackEventTime("clicks", Duration.ofSeconds(5));
            engine.start();
            engine.register("page_views", PAGE_VIEWS, "window_start", "page");

            engine.push("clicks", row("home", 1), row("home", 2));
            engine.push("clicks", row("home", 14));
            assertThat(engine.query("SELECT * FROM page_views").rows())
                    .as("the latest row is at 14s, so event time is 9s and [0, 10) is still open")
                    .isEmpty();

            engine.push("clicks", row("home", 15));
            List<Object[]> rows =
                    engine.query("SELECT page, views FROM page_views").rows();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsExactly("home", 2L);

            // An older row does not move event time back.
            engine.push("clicks", row("about", 11));
            assertThat(engine.query("SELECT page, views FROM page_views").rows())
                    .hasSize(1);

            // The host can still advance it itself.
            engine.advanceEventTime("clicks", BASE.plusSeconds(20));
            assertThat(engine.query("SELECT page, views FROM page_views").rows())
                    .hasSize(3);
        }
    }

    @Test
    void aStreamWithNoEventTimeCannotBeTrackedAndNeitherCanANegativeLateness() {
        PravahaEngine untimed = PravahaEngine.createDefault();
        untimed.declareStream("txn", "user_id:STRING,amount:INT64").trackEventTime("txn", Duration.ZERO);
        assertThatThrownBy(untimed::start)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no event-time column");
        untimed.close();

        PravahaEngine undeclared = PravahaEngine.createDefault();
        undeclared.declareStream(clicks()).trackEventTime("orders", Duration.ZERO);
        assertThatThrownBy(undeclared::start)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is not declared");
        undeclared.close();

        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream(clicks());
            assertThatThrownBy(() -> engine.trackEventTime("clicks", Duration.ofSeconds(-1)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("zero or more");
            engine.start();
            assertThatThrownBy(() -> engine.trackEventTime("clicks", Duration.ZERO))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static Object[] row(String page, long seconds) {
        return new Object[] {page, BASE.plusSeconds(seconds)};
    }
}

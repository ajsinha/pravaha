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
package com.ash.messaging.pravaha.spring;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application that adds the starter and nothing else: the engine arrives through Boot's own
 * auto-configuration discovery -- the {@code AutoConfiguration.imports} file -- not through a test
 * naming the configuration class.
 */
@SpringBootTest(
        classes = StarterApplicationTest.OrdersApplication.class,
        properties = {
            "pravaha.node.id=orders",
            "pravaha.streams.orders.schema=order_id:STRING,amount:INT64",
            "pravaha.queries.order_stats.sql=SELECT COUNT(*) AS orders, SUM(amount) AS revenue FROM orders",
            "pravaha.queries.order_stats.keys=orders"
        })
class StarterApplicationTest {

    @SpringBootApplication
    static class OrdersApplication {

        // A bean method rather than @Component: Boot's test filter keeps classes nested in a test out
        // of component scanning, and a listener that is never scanned would pass for the wrong reason.
        @Bean
        Dashboard dashboard() {
            return new Dashboard();
        }
    }

    record OrderStats(long orders, long revenue) {}

    static class Dashboard {
        final List<String> events = new CopyOnWriteArrayList<>();

        @PravahaListener(query = "order_stats")
        void on(OrderStats stats, boolean retraction) {
            events.add((retraction ? "-" : "+") + stats.orders() + "/" + stats.revenue());
        }
    }

    @Autowired
    PravahaEngine engine;

    @Autowired
    PravahaTemplate template;

    @Autowired
    Dashboard dashboard;

    @Test
    void theStarterAloneGivesARunningEngineATemplateAndListeners() {
        assertThat(engine.state()).isEqualTo(EngineState.RUNNING);
        assertThat(engine.instanceId()).isEqualTo("orders");

        template.push("orders", new Object[] {"o-1", 40L});
        template.push("orders", new Object[] {"o-2", 60L});

        PravahaListenerTest.await(() -> dashboard.events.size() >= 3, "the dashboard's three events");
        assertThat(dashboard.events).containsExactly("+1/40", "-1/40", "+2/100");
        assertThat(template.query(OrderStats.class, "SELECT * FROM order_stats"))
                .containsExactly(new OrderStats(2, 100));
    }
}

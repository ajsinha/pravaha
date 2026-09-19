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
package com.ash.messaging.pravaha.sample.orders;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.spring.PravahaTemplate;
import com.ash.messaging.pravaha.spring.test.PravahaTest;
import com.ash.messaging.pravaha.spring.test.PravahaTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample {@code @PravahaTest}: how an application tests its own Pravaha code, and the proof that
 * the slice is what it says.
 *
 * <p>Nothing here sleeps or polls. {@link OrderAlerts} takes 50 ms per change on purpose, so an
 * assertion that did not wait for delivery would see an empty list.
 */
@PravahaTest
@ActiveProfiles("sample")
class OrderAlertsTest {

    @Autowired
    PravahaTester pravaha;

    @Autowired
    OrderAlerts alerts;

    @Autowired
    ApplicationContext context;

    @Test
    void aLargeOrderRaisesAnAlertOnceTheListenerHasBeenHandedIt() {
        pravaha.push("orders", new Object[] {"o-1", 40L}, new Object[] {"o-2", 900L}, new Object[] {"o-3", 700L})
                .awaitListeners("big_orders");

        assertThat(alerts.open()).contains("o-2", "o-3").doesNotContain("o-1");
    }

    @Test
    void theViewAnswersWithWhatWasPushed() {
        pravaha.push("orders", new Object[] {"o-10", 1_000L});

        List<OrderAlerts.BigOrder> big = pravaha.awaitView(
                "big_orders",
                OrderAlerts.BigOrder.class,
                rows -> rows.contains(new OrderAlerts.BigOrder("o-10", 1_000L)));
        assertThat(big).allMatch(order -> order.amount() > 500);
    }

    @Test
    void theSliceHoldsTheEngineAndTheListenersAndNothingElse() {
        assertThat(context.getBeansOfType(PravahaEngine.class)).hasSize(1);
        assertThat(context.getBeansOfType(PravahaTemplate.class)).hasSize(1);
        assertThat(context.getBeansOfType(OrderAlerts.class))
                .as("a component with a @PravahaListener is kept")
                .hasSize(1);
        assertThat(context.getBeansOfType(Invoicing.class))
                .as("a component without one is left out")
                .isEmpty();
        assertThat(context.containsBean("dispatcherServlet"))
                .as("no web layer, though spring-boot-starter-web is on the test classpath")
                .isFalse();
    }

    @Test
    void theApplicationsCheckpointAndJournalPathsDoNotReachTheTest() {
        PravahaEngine engine = pravaha.engine();
        assertThat(engine.instanceId()).as("application-sample.yaml is read").isEqualTo("sample-orders");
        assertThat(engine.configuration().getString("pravaha.checkpoint.directory"))
                .isEmpty();
        assertThat(engine.configuration().getString("pravaha.registry.journal")).isEmpty();
    }
}

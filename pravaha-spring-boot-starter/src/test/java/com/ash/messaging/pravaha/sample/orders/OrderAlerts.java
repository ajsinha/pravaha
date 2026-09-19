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
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.spring.PravahaListener;

/** Raises an alert for every large order, and withdraws it if the order is withdrawn. */
@Component
public class OrderAlerts {

    /** A row of {@code big_orders}. */
    public record BigOrder(String orderId, long amount) {}

    private final List<String> open = new CopyOnWriteArrayList<>();

    @PravahaListener(query = "big_orders")
    void on(BigOrder order, boolean retraction) {
        // Deliberately not instant, so a test that read this without waiting for delivery would
        // see it empty rather than pass by luck.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (retraction) {
            open.remove(order.orderId());
        } else {
            open.add(order.orderId());
        }
    }

    /** Orders with an alert raised, in the order they were raised. */
    public List<String> open() {
        return List.copyOf(open);
    }
}

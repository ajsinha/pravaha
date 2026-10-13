/*
 * Tutorial 12 (docs/tutorials/12-embedding-pravaha.md): an embedded engine that survives a restart.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.payments;

import java.time.Instant;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

/**
 * The same engine, built from configuration -- the keys a node reads -- with a registry journal and a
 * checkpoint directory under {@code state/}, checkpointing every second. Each run pushes one payment
 * of 250. Run it more than once: the journal registers the query again at start, and the checkpoint
 * brings its total back. With {@code CLOSE_AT_ONCE=1} it closes straight after the push, before the
 * next checkpoint -- and that payment is not there the next time.
 */
public final class Durable {

    public static void main(String[] args) throws InterruptedException {
        Configuration configuration = Configuration.builder()
                .set("pravaha.node.id", "payments-service")
                .set("pravaha.streams.txn.schema",
                        "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,event_time:TIMESTAMP")
                .set("pravaha.registry.journal", "state/registry.journal")
                .set("pravaha.checkpoint.directory", "state/checkpoints")
                .set("pravaha.checkpoint.interval", "1s")
                .build();
        try (PravahaEngine engine = PravahaEngine.create(configuration)) {
            engine.start();
            if (engine.find("payments_total").isPresent()) {
                System.out.println("payments_total is back: the journal registered it again at start");
            } else {
                // A global aggregate (no GROUP BY) holds one row, so its state cannot grow: no window needed.
                engine.register("payments_total",
                        "SELECT COUNT(*) AS payments, SUM(amount) AS total FROM txn", "payments");
                System.out.println("registered payments_total");
            }
            System.out.println("at start: " + total(engine));
            engine.push("txn", new Object[] {System.nanoTime(), "ann", "acme", 250L,
                    Instant.parse("2026-10-01T09:00:00Z")});
            System.out.println("after a payment of 250: " + total(engine));
            if (System.getenv("CLOSE_AT_ONCE") == null) {
                // A checkpoint is taken every pravaha.checkpoint.interval, not at close: wait for one.
                Thread.sleep(2_500);
                System.out.println("checkpointed; closing");
            } else {
                System.out.println("closing before the next checkpoint");
            }
        }
    }

    private static String total(PravahaEngine engine) {
        var rows = engine.query("SELECT payments, total FROM payments_total").rows();
        return rows.isEmpty() ? "no rows yet" : rows.get(0)[0] + " payments, " + rows.get(0)[1] + " in all";
    }
}

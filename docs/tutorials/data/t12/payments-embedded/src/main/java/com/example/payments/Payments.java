/*
 * Tutorial 12 (docs/tutorials/12-embedding-pravaha.md): the engine inside a plain Java program.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.payments;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;

/**
 * A payments service with a maintained answer inside it: each merchant's spend per minute, and the
 * large payments. It pushes its own rows, closes the minute by advancing event time, follows one
 * view and reads both. No server, no port, no Spring.
 */
public final class Payments {

    /** A row of large_payments, matched to the view's columns by name. */
    record Large(long txnId, String merchant, long amount) {}

    /** A row of merchant_minutes: TIMESTAMP columns read into an Instant. */
    record Minute(String merchant, Instant windowEnd, long spend, long payments) {}

    private static final Instant NINE = Instant.parse("2026-10-01T09:00:00Z");

    public static void main(String[] args) throws InterruptedException {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            // 1. Declare, then start: every query is planned against the streams declared here.
            engine.declareStream("txn",
                    "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,event_time:TIMESTAMP",
                    "event_time");                  // the event-time column: windows over it can close
            engine.start();
            System.out.println("engine " + engine.state() + ", streams " + engine.streams().size());

            // 2. Register: CREATE CONTINUOUS QUERY through query(), or the registration call.
            engine.query("""
                    CREATE CONTINUOUS QUERY merchant_minutes
                        KEYED BY (merchant, window_end)
                    AS SELECT merchant, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS payments
                       FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
                       GROUP BY merchant, window_start, window_end""");
            engine.register("large_payments",
                    "SELECT txn_id, merchant, amount FROM txn WHERE amount >= 1000", "txn_id");
            System.out.println("registered " + new TreeSet<>(engine.queries()));

            // 3. Follow a view: every commit, as +1 and -1 changes, on the committing thread.
            Queue<String> seen = new ConcurrentLinkedQueue<>();
            engine.subscribe("merchant_minutes", changes -> changes.forEach(c ->
                    seen.add((c.isRetraction() ? "- " : "+ ") + c.as(Minute.class))));

            // 4. Push rows, in column order. Returns once every query reading txn has committed them.
            int reached = engine.push("txn",
                    new Object[] {1L, "ann", "acme", 400L, NINE.plusSeconds(5)},
                    new Object[] {2L, "bob", "acme", 1250L, NINE.plusSeconds(20)},
                    new Object[] {3L, "ann", "globex", 4800L, NINE.plusSeconds(40)},
                    new Object[] {4L, "cat", "acme", 90L, NINE.plusSeconds(55)});
            System.out.println("pushed 4 payments to " + reached + " computations");
            System.out.println("large payments now: " + engine.query(Large.class, "SELECT * FROM large_payments"));
            System.out.println("minutes published so far: "
                    + engine.query("SELECT COUNT(*) AS n FROM merchant_minutes").rows().get(0)[0]);

            // 5. Close 09:00 -- nothing earlier than 09:01:30 is still coming -- and a row of 09:01.
            engine.push("txn", new Object[] {5L, "dan", "globex", 700L, NINE.plusSeconds(75)});
            engine.advanceEventTime("txn", NINE.plusSeconds(90));
            await(seen, 2);
            System.out.println("the subscriber saw:");
            seen.stream().sorted().forEach(line -> System.out.println("  " + line));

            // 6. Read: rows, or records by column name, with parameters.
            List<Object[]> acme = engine.query(
                    "SELECT spend, payments FROM merchant_minutes WHERE merchant = ?", "acme").rows();
            System.out.println("acme 09:00: spend " + acme.get(0)[0] + " in " + acme.get(0)[1] + " payments");

            // 7. What it refuses, it refuses as the server does: with a code, before anything runs.
            try {
                engine.register("per_user", "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id",
                        "user_id");
            } catch (RuntimeException refused) {
                System.out.println("refused: " + firstLine(refused));
            }
            try {
                engine.push("txn", new Object[] {6L, "erin", "acme", "a lot", NINE.plusSeconds(95)});
            } catch (RuntimeException refused) {
                System.out.println("refused: " + firstLine(refused));
            }
        }
        System.out.println("closed");
    }

    /** Waits (at most ten seconds) until the subscriber has seen {@code count} changes. */
    private static void await(Queue<String> seen, int count) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (seen.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static String firstLine(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        int end = message.indexOf(". ");
        return end > 0 ? message.substring(0, end + 1) : message;
    }
}

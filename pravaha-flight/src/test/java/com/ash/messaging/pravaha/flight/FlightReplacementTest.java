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
package com.ash.messaging.pravaha.flight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blue/green replacement over Flight: start, watch, cut over, roll back (ADR-046).
 *
 * <p>Flight SQL has no vocabulary for any of this, so they are Pravaha's own actions beside
 * register, drop, pause and resume -- and they decide exactly as the engine does, because they call
 * it rather than reimplementing it. What this asserts is the wire: the fields a status carries and
 * in what order, that a refusal keeps its code across the network, and that the name moves.
 */
class FlightReplacementTest {

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .build();

    private static final String V1 = "SELECT trade_id, product_type FROM trade";
    private static final String V2 = "SELECT trade_id, product_type, 'reviewed' AS status FROM trade";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private QueryRegistry registry;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE)
                .feedingFrom(new QuietBackfillSource());
        server = new PravahaFlightServer(views, allocator).hosting(registry).start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    @Test
    void aQueryIsReplacedCutOverAndRolledBackOverTheWire() {
        act(ControlWire.REGISTER, "trades", V1, "0");

        List<String> started = act(ControlWire.REPLACE, "trades", V2, "0").get(0);
        assertThat(started).hasSize(ControlWire.REPLACEMENT_FIELDS.size());
        assertThat(started.get(0)).isEqualTo("trades");
        assertThat(started.get(1)).isIn("BACKFILLING", "CAUGHT_UP");
        assertThat(started.get(2)).isEqualTo(V2);
        assertThat(started.get(3)).as("the computation being prepared").isNotBlank();
        assertThat(started.get(6)).as("the options it was started with").contains("backfill=history");

        awaitCaughtUp();
        List<String> status = act(ControlWire.REPLACEMENT, "trades").get(0);
        assertThat(status.get(1)).isEqualTo("CAUGHT_UP");
        assertThat(status.get(17)).as("history_complete").isEqualTo("true");

        List<String> cut = act(ControlWire.CUTOVER, "trades").get(0);
        assertThat(cut.get(1)).isEqualTo("CUT_OVER");
        assertThat(cut.get(11)).as("rollback_available").isEqualTo("true");
        assertThat(registry.find("trades").orElseThrow().sql()).isEqualTo(V2);

        List<String> back = act(ControlWire.ROLLBACK, "trades").get(0);
        assertThat(back.get(1)).isEqualTo("ROLLED_BACK");
        assertThat(registry.find("trades").orElseThrow().sql()).isEqualTo(V1);
    }

    @Test
    void aBackfillIsThrottledPausedAndResumedOverTheWire() {
        act(ControlWire.REGISTER, "trades", V1, "0");
        act(ControlWire.REPLACE, "trades", V2, "0", "backfill=history;backfill.rate.limit=500;cutover=manual");

        assertThat(act(ControlWire.BACKFILL, "trades", "throttle", "100").get(0).get(18))
                .as("rate_limit")
                .isEqualTo("100");
        assertThat(act(ControlWire.BACKFILL, "trades", "pause").get(0).get(19))
                .as("paused")
                .isEqualTo("true");
        assertThat(act(ControlWire.BACKFILL, "trades", "resume").get(0).get(19)).isEqualTo("false");
        assertThatThrownBy(() -> act(ControlWire.BACKFILL, "trades", "reverse"))
                .hasMessageContaining("paused, resumed or throttled");
    }

    @Test
    void theRefusalsKeepTheirCodesAcrossTheWire() {
        act(ControlWire.REGISTER, "trades", V1, "0");

        assertThatThrownBy(() -> act(ControlWire.CUTOVER, "trades")).hasMessageContaining("PRV-4016");
        assertThatThrownBy(() -> act(ControlWire.REPLACE, "trades", V1, "0"))
                .as("a new version that is the same computation as the old one")
                .hasMessageContaining("PRV-4017");
        assertThatThrownBy(() -> act(ControlWire.REPLACE, "trades", V2, "0", "backfill.window=P7D"))
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("backfill.window");
        assertThatThrownBy(() -> act(ControlWire.REPLACE, "trades")).hasMessageContaining("replace needs a name");
    }

    @Test
    void everyReplacementThisNodeKnowsIsListedWhenNoNameIsGiven() {
        act(ControlWire.REGISTER, "trades", V1, "0");
        assertThat(act(ControlWire.REPLACEMENT)).isEmpty();
        act(ControlWire.REPLACE, "trades", V2, "0");
        assertThat(act(ControlWire.REPLACEMENT))
                .singleElement()
                .satisfies(row -> assertThat(row.get(0)).isEqualTo("trades"));
    }

    private void awaitCaughtUp() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if ("CAUGHT_UP".equals(act(ControlWire.REPLACEMENT, "trades").get(0).get(1))) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(act(ControlWire.REPLACEMENT, "trades").get(0).get(1)).isEqualTo("CAUGHT_UP");
    }

    private List<List<String>> act(String type, String... fields) {
        List<List<String>> results = new ArrayList<>();
        client.doAction(new Action(type, ControlWire.encode(fields)))
                .forEachRemaining(result -> results.add(ControlWire.decode(result.getBody())));
        return results;
    }
}

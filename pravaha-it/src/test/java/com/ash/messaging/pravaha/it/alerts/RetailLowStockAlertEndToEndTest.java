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
package com.ash.messaging.pravaha.it.alerts;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.alert.WebhookNotifier;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.alerts.AlertProperties;
import com.ash.messaging.pravaha.server.alerts.NotifierBindingProperties;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-057 end to end, on a real node: the retail study's {@code low_stock} view, an alert on it created
 * over Flight SQL, and a signed webhook to a receiver on loopback. The morning of {@code
 * data/sample/stock.csv} is replayed as mysql-cdc delivers it -- an update as the old row at -1 and the
 * new at +1 -- and the receiver sees each line fire as an update crosses its reorder point and clear when
 * the delivery tops it up or the row is deleted. The node restarts in the middle, and neither re-fires
 * what is firing nor loses the clear that comes after.
 */
@Timeout(180)
class RetailLowStockAlertEndToEndTest {

    private static final StreamSchema STOCK = StreamSchema.builder("stock")
            .field("sku", Types.string())
            .field("warehouse", Types.string())
            .field("on_hand", Types.int32())
            .field("reorder_point", Types.int32())
            .field("updated_at", Types.timestamp())
            .eventTime("updated_at")
            .build();

    private static final String SECRET = "retail-hook-secret";

    /** One request as the buyers' receiver saw it. */
    record Received(String event, String idempotencyKey, String timestamp, String signature, String body) {}

    @TempDir
    Path dir;

    private HttpServer receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private RowArena arena;

    @BeforeEach
    void setUp() throws IOException {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/buyers", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestHeaders().getFirst("X-Pravaha-Event"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("X-Pravaha-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Pravaha-Signature"),
                    body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        Files.writeString(dir.resolve("buyers.secret"), SECRET);
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
        arena.close();
    }

    @Test
    void anUpdateAcrossTheReorderPointFiresAndADeliveryClearsItAcrossARestart() throws Exception {
        PravahaNode node = node();
        node.start();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url(node))) {
            run(
                    client,
                    "CREATE CONTINUOUS QUERY low_stock KEYED BY (sku, warehouse) AS "
                            + "SELECT sku, warehouse, on_hand, reorder_point, updated_at FROM stock "
                            + "WHERE on_hand <= reorder_point");
            List<Object[]> created = run(
                    client,
                    "CREATE ALERT low_stock_alert ON low_stock NOTIFY buyers "
                            + "WITH (severity = 'warning', include = (on_hand, reorder_point))");
            assertThat(created.get(0)[1]).isEqualTo("ACTIVE");
            assertThatThrownBy(() -> run(client, "DROP CONTINUOUS QUERY low_stock"))
                    .hasMessageContaining("PRV-8024")
                    .hasMessageContaining("ALERT low_stock_alert");

            RegisteredQuery low = node.registry().orElseThrow().require("low_stock");
            // 09:00, the opening rows: only sku-400 MAN (3 of 4) is at or under its reorder point.
            push(low, "sku-100", "LDN", 40, 10, "09:00", 1);
            push(low, "sku-100", "MAN", 25, 10, "09:00", 1);
            push(low, "sku-200", "LDN", 12, 8, "09:00", 1);
            push(low, "sku-300", "LDN", 6, 5, "09:00", 1);
            push(low, "sku-300", "MAN", 30, 5, "09:00", 1);
            push(low, "sku-400", "MAN", 3, 4, "09:00", 1);
            low.commit();
            awaitReceived(1);
            // 09:05, an update crosses the reorder point: sku-200 LDN 12 -> 7 (of 8).
            update(low, "sku-200", "LDN", 12, "09:00", 7, "09:05", 8);
            awaitReceived(2);
            // 09:10, sku-300 LDN 6 -> 2 (of 5).
            update(low, "sku-300", "LDN", 6, "09:00", 2, "09:10", 5);
            awaitReceived(3);
            // 09:15, sku-100 MAN 25 -> 22: above its reorder point both before and after -- nothing.
            update(low, "sku-100", "MAN", 25, "09:00", 22, "09:15", 10);
            assertThat(events()).containsExactly("FIRED sku-400/MAN", "FIRED sku-200/LDN", "FIRED sku-300/LDN");

            List<Object[]> shown = run(client, "SHOW ALERTS");
            assertThat(shown.get(0)[0]).isEqualTo("low_stock_alert");
            assertThat(shown.get(0)[7]).isEqualTo(3L);
            awaitCheckpointed(low);
        } finally {
            node.stop();
        }

        // The node restarts: the view restores from its checkpoint, the alert from its journal.
        PravahaNode restarted = node();
        restarted.start();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url(restarted))) {
            Thread.sleep(1_500);
            assertThat(events()).as("nothing firing is fired again").hasSize(3);

            RegisteredQuery low = restarted.registry().orElseThrow().require("low_stock");
            // 09:20, the delivery: sku-200 LDN 7 -> 27. Its -1 takes the line out of low_stock.
            update(low, "sku-200", "LDN", 7, "09:05", 27, "09:20", 8);
            awaitReceived(4);
            // 09:25, sku-400 MAN is deleted: its -1 alone.
            push(low, "sku-400", "MAN", 3, 4, "09:00", -1);
            low.commit();
            awaitReceived(5);
            // 09:30, sku-100 LDN 40 -> 10 (of 10): <= counts.
            update(low, "sku-100", "LDN", 40, "09:00", 10, "09:30", 10);
            awaitReceived(6);
            assertThat(events())
                    .containsExactly(
                            "FIRED sku-400/MAN",
                            "FIRED sku-200/LDN",
                            "FIRED sku-300/LDN",
                            "CLEARED sku-200/LDN",
                            "CLEARED sku-400/MAN",
                            "FIRED sku-100/LDN");
            assertThat(run(client, "SHOW ALERTS").get(0)[7])
                    .as("firing: sku-300 and sku-100 in LDN")
                    .isEqualTo(2L);
            run(client, "DROP ALERT low_stock_alert");
            run(client, "DROP CONTINUOUS QUERY low_stock");
        } finally {
            restarted.stop();
        }

        // Every request is signed: the receiver recomputes HMAC-SHA256 over "<timestamp>.<body>".
        for (Received request : received) {
            String expected = "sha256="
                    + WebhookNotifier.sign(
                            SECRET.getBytes(StandardCharsets.UTF_8), request.timestamp() + "." + request.body());
            assertThat(MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            request.signature().getBytes(StandardCharsets.UTF_8)))
                    .as("signature of " + request.body())
                    .isTrue();
        }
        assertThat(received.get(1).body())
                .contains("\"key\":{\"sku\":\"sku-200\",\"warehouse\":\"LDN\"}")
                .contains("\"row\":{\"on_hand\":7,\"reorder_point\":8}")
                .contains("\"severity\":\"warning\"");
        assertThat(received).extracting(Received::idempotencyKey).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ helpers

    private PravahaNode node() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(STOCK);
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(dir.resolve("registry.journal").toString());
        persistence.getCheckpoint().setDirectory(dir.resolve("checkpoints").toString());
        persistence.getCheckpoint().setInterval(Duration.ofMillis(200));
        PravahaNode node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new SourceBindingProperties())
                .withSinks(new SinkBindingProperties())
                .withDeclaredStreams(new StreamDeclarationProperties())
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("alerts-e2e")
                .build();
        NotifierBindingProperties notifiers = new NotifierBindingProperties();
        NotifierBindingProperties.Spec buyers = new NotifierBindingProperties.Spec();
        buyers.setPlugin("webhook");
        buyers.setOptions(Map.of(
                "url",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/buyers",
                "secret-file",
                dir.resolve("buyers.secret").toString(),
                "backoff",
                "50ms"));
        notifiers.getNotifiers().put("buyers", buyers);
        AlertProperties alerts = new AlertProperties();
        alerts.setRecoveryGrace(Duration.ofSeconds(1));
        node.setAlerts(alerts, notifiers);
        return node;
    }

    private static String url(PravahaNode node) {
        return "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
    }

    private static List<Object[]> run(PravahaFlightClient client, String sql) {
        try (QueryResult result = client.query(sql)) {
            return result.toList();
        }
    }

    /** "FIRED sku-400/MAN", from each body the receiver took, in order. */
    private List<String> events() {
        return received.stream()
                .map(r -> r.event() + " " + field(r.body(), "sku") + "/" + field(r.body(), "warehouse"))
                .toList();
    }

    private static String field(String body, String name) {
        int at = body.indexOf("\"" + name + "\":\"");
        int from = at + name.length() + 4;
        return body.substring(from, body.indexOf('"', from));
    }

    private void awaitReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (received.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(received).as("notifications received").hasSize(count);
    }

    private static void awaitCheckpointed(RegisteredQuery query) throws InterruptedException {
        Instant after = Instant.now();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (query.lastCheckpoint().map(at -> !at.isAfter(after)).orElse(true) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(query.lastCheckpoint()).as("checkpointed").isPresent();
    }

    private void update(
            RegisteredQuery query,
            String sku,
            String warehouse,
            int from,
            String fromAt,
            int to,
            String toAt,
            int reorder) {
        push(query, sku, warehouse, from, reorder, fromAt, -1);
        push(query, sku, warehouse, to, reorder, toAt, 1);
        query.commit();
    }

    private void push(
            RegisteredQuery query, String sku, String warehouse, int onHand, int reorder, String at, long weight) {
        RowLayout layout = RowLayout.of(STOCK);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        Instant time = Instant.parse("2026-06-01T" + at + ":00Z");
        long nanos = time.getEpochSecond() * 1_000_000_000L;
        writer.setString(0, sku)
                .setString(1, warehouse)
                .setInt(2, onHand)
                .setInt(3, reorder)
                .setLong(4, nanos);
        writer.weight(weight)
                .eventTimestampNanos(nanos)
                .sequence(System.nanoTime())
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(
                        "stock", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
    }
}

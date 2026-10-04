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
package com.ash.messaging.pravaha.registry.alert;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.api.plugin.NotifierPlugin;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

/**
 * The retail case study's stock table, in miniature, for alert tests: a stream of rows keyed by (sku,
 * warehouse), a view of the lines at or under their reorder point, a clock the test moves, and a
 * channel that records what it was sent -- and can be told to refuse.
 */
final class AlertFixture implements AutoCloseable {

    static final StreamSchema STOCK = StreamSchema.builder("stock")
            .field("sku", Types.string())
            .field("warehouse", Types.string())
            .field("on_hand", Types.int64())
            .field("reorder_point", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    static final String LOW_STOCK =
            "SELECT sku, warehouse, on_hand, reorder_point FROM stock WHERE on_hand <= reorder_point";

    static final Principal BUYER = new Principal("bea", "acme", Set.of("buyer"), Map.of());

    final MutableClock clock = new MutableClock(Instant.parse("2026-06-01T09:00:00Z"));
    final Recording channel = new Recording("buyers");
    final QueryRegistry registry;
    final RegisteredQuery lowStock;
    final ContinuousQueryStatements statements;
    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    private long ts = 1_000_000_000L;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    AlertService service;

    AlertFixture() {
        this(SecurityPolicy.PERMISSIVE);
    }

    AlertFixture(SecurityPolicy policy) {
        this(policy, BUYER);
    }

    /** With the view registered by {@code registrant}, who owns it under the catalogue. */
    AlertFixture(SecurityPolicy policy, Principal registrant) {
        registry = new QueryRegistry(new ViewCatalog(), policy, AuditSink.NONE, STOCK);
        lowStock = registry.register("low_stock", LOW_STOCK, List.of(0, 1), registrant);
        statements = new ContinuousQueryStatements(registry, policy, AuditSink.NONE);
    }

    /** Opens the alert service over the journal at {@code journal} (null: memory), driven by hand. */
    AlertService open(@Nullable Path journal) {
        return open(journal, AlertService.Settings.manual());
    }

    AlertService open(@Nullable Path journal, AlertService.Settings settings) {
        Notifiers notifiers = Notifiers.none().bind("buyers", channel).bind("ops", new Recording("ops"));
        service = AlertService.open(registry, journal, notifiers, AuditSink.NONE, clock, settings);
        return service;
    }

    ViewQuery.Result sql(Principal who, String text) {
        return statements.execute(ContinuousStatements.recognize(text).orElseThrow(), who);
    }

    /** One stock row at weight +1 (insert) or -1 (retraction), committed. */
    void stock(String sku, String warehouse, long onHand, long reorderPoint, long weight) {
        write(sku, warehouse, onHand, reorderPoint, weight);
        lowStock.commit();
    }

    /** An update: the old row retracted and the new one inserted, in one commit. */
    void update(String sku, String warehouse, long fromOnHand, long toOnHand, long reorderPoint) {
        write(sku, warehouse, fromOnHand, reorderPoint, -1);
        write(sku, warehouse, toOnHand, reorderPoint, 1);
        lowStock.commit();
    }

    private void write(String sku, String warehouse, long onHand, long reorderPoint, long weight) {
        long at = ts += 1_000_000L;
        RowLayout layout = RowLayout.of(STOCK);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, sku)
                .setString(1, warehouse)
                .setLong(2, onHand)
                .setLong(3, reorderPoint)
                .setLong(4, at);
        writer.weight(weight).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        lowStock.accept("stock", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        lowStock.awaitApplied(Duration.ofSeconds(10));
    }

    /** Moves the clock and evaluates. */
    void after(Duration elapsed) {
        clock.advance(elapsed);
        service.tick();
    }

    void tick() {
        service.tick();
    }

    /** "FIRED sku-100/LDN" for each notification the channel accepted, in order. */
    List<String> sent() {
        return channel.accepted.stream()
                .map(n -> n.kind() + " " + n.key().get("sku") + "/" + n.key().get("warehouse"))
                .toList();
    }

    @Override
    public void close() {
        if (service != null) {
            service.close();
        }
        registry.close();
        arena.close();
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A channel that keeps what it accepts, and refuses the next {@link #refuse} sends. */
    static final class Recording implements NotifierPlugin {
        final String channel;
        final List<Notification> accepted = new CopyOnWriteArrayList<>();
        final List<Notification> attempted = new CopyOnWriteArrayList<>();
        final AtomicInteger refuse = new AtomicInteger();

        Recording(String channel) {
            this.channel = channel;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public Version version() {
            return Version.apiVersion();
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public Delivery send(Notification notification) {
            attempted.add(notification);
            if (refuse.get() > 0) {
                refuse.decrementAndGet();
                return Delivery.failed(1, "HTTP 503");
            }
            accepted.add(notification);
            return Delivery.delivered(1, "HTTP 200");
        }

        @Override
        public void close() {}
    }
}

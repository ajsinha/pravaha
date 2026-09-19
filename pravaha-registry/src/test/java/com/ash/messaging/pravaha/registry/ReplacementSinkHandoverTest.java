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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to the sink a name writes to when that name is replaced (ADR-046).
 *
 * <p>The sink follows the name, because after the cutover the table it maintains is the new
 * version's answer -- and it must neither lose a row nor write one twice while it moves. The
 * mechanism is in {@code QueryReplacements.handSinkOver}: the replaced version takes a checkpoint
 * with its feed stopped, which commits everything it was written; the new version's checkpoint ids
 * continue above that one's, so the sink's transaction labels keep increasing; what the sink holds
 * is recorded on the new version as a carried section and its delivery is then owed the
 * <em>difference</em> between that and the new version's view, as one batch.
 *
 * <p>The sink here is a keyed store, because that is what makes "written twice" visible: applying
 * an insert twice to a table with a primary key hides the duplicate, so what is asserted is the
 * sequence of writes as well as the contents.
 */
class ReplacementSinkHandoverTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String V1 = "SELECT user_id, amount FROM txn";
    private static final String V2 = "SELECT user_id, amount * 10 AS amount FROM txn";

    @TempDir
    Path checkpoints;

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    @Test
    void theSinkMovesWithTheNameAtACheckpointBoundaryAndIsSentOnlyTheDifference() {
        ReplayableLog log = new ReplayableLog(TXN);
        log.append("u1", 1L);
        log.append("u2", 2L);
        Warehouse warehouse = new Warehouse();
        QueryRegistry registry = registry(log, warehouse);
        registry.registerWritingTo("orders", V1, List.of(0), DANA, "warehouse");
        awaitView(registry, "orders", 2);
        // Exactly-once: what a transactional sink has been written is visible when the checkpoint
        // recording it is durable, and not before.
        checkpoint(registry, "orders");
        assertThat(warehouse.visible()).isEqualTo(Map.of("u1", 1L, "u2", 2L));

        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(registry, "orders");
        int writesBefore = warehouse.writes().size();
        registry.replacements().cutOver("orders", DANA);

        // The difference is written into the transaction the new version opened, and becomes
        // visible at its next checkpoint -- the same rule as every other row this sink is sent.
        checkpoint(registry, "orders");
        assertThat(warehouse.visible()).isEqualTo(Map.of("u1", 10L, "u2", 20L));
        List<String> handover =
                warehouse.writes().subList(writesBefore, warehouse.writes().size());
        assertThat(handover)
                .as("the sink was sent the difference -- the old answer withdrawn, the new one written "
                        + "-- and nothing else: not the whole of the new view, and nothing twice")
                .containsExactly("-[u1, 1]", "-[u2, 2]", "+[u1, 10]", "+[u2, 20]");
        assertThat(warehouse.committedTwice())
                .as("no transaction was committed under a label the store had already seen")
                .isEmpty();
        assertThat(warehouse.labels())
                .as("labels only increase, across the change of computation")
                .isSorted();

        // And the new version goes on writing to it.
        log.append("u3", 3L);
        awaitView(registry, "orders", 3);
        checkpoint(registry, "orders");
        assertThat(warehouse.visible()).isEqualTo(Map.of("u1", 10L, "u2", 20L, "u3", 30L));
        assertThat(registry.sinkOf("orders")).hasValue("warehouse");
    }

    @Test
    void aRollbackTakesTheSinkBackToTheVersionThatWasThereBefore() {
        ReplayableLog log = new ReplayableLog(TXN);
        log.append("u1", 1L);
        Warehouse warehouse = new Warehouse();
        QueryRegistry registry = registry(log, warehouse);
        registry.registerWritingTo("orders", V1, List.of(0), DANA, "warehouse");
        awaitView(registry, "orders", 1);
        checkpoint(registry, "orders");
        assertThat(warehouse.visible()).isEqualTo(Map.of("u1", 1L));

        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(registry, "orders");
        registry.replacements().cutOver("orders", DANA);
        checkpoint(registry, "orders");
        assertThat(warehouse.visible()).isEqualTo(Map.of("u1", 10L));

        registry.replacements().rollBack("orders", DANA);
        checkpoint(registry, "orders");
        assertThat(warehouse.visible())
                .as("the sink holds the previous version's answer again, and holds it once")
                .isEqualTo(Map.of("u1", 1L));
        assertThat(warehouse.labels()).isSorted();
    }

    private QueryRegistry registry(ReplayableLog log, Warehouse warehouse) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .feedingFrom(log)
                .writingTo(new Warehouses(warehouse))
                .checkpointingTo(
                        checkpoints,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private static void awaitCaughtUp(QueryRegistry registry, String name) {
        await(() -> registry.replacements().of(name).orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
    }

    private static void awaitView(QueryRegistry registry, String name, int rows) {
        await(() -> registry.find(name).orElseThrow().view().size() == rows);
    }

    /** A checkpoint now, which is what makes a transactional sink's transaction visible. */
    private static void checkpoint(QueryRegistry registry, String name) {
        assertThat(registry.find(name).orElseThrow().checkpointNow()).isPresent();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean())
                .as("the condition did not hold in 30 seconds")
                .isTrue();
    }

    /**
     * The external system: a keyed table, written inside transactions that are visible only once
     * committed, and a record of every label it has ever committed.
     */
    static final class Warehouse {
        private final Map<String, Long> visible = new LinkedHashMap<>();
        private final Map<String, List<String>> open = new LinkedHashMap<>();
        private final Map<String, Long> preparedLabels = new LinkedHashMap<>();
        private final List<String> writes = new CopyOnWriteArrayList<>();
        private final List<Long> labels = new CopyOnWriteArrayList<>();
        private final List<Long> committedTwice = new CopyOnWriteArrayList<>();
        private final Set<Long> committed = new java.util.HashSet<>();

        synchronized void write(String handle, String rendered, String key, long value, boolean retraction) {
            writes.add(rendered);
            open.computeIfAbsent(handle, k -> new ArrayList<>()).add(retraction ? "-" + key : key + "=" + value);
        }

        synchronized void prepare(String handle, long label) {
            preparedLabels.put(handle, label);
            labels.add(label);
        }

        synchronized void commit(String handle) {
            Long label = preparedLabels.remove(handle);
            if (label == null) {
                return;
            }
            if (!committed.add(label)) {
                committedTwice.add(label);
            }
            for (String change : open.getOrDefault(handle, List.of())) {
                if (change.startsWith("-")) {
                    visible.remove(change.substring(1));
                } else {
                    int equals = change.indexOf('=');
                    visible.put(change.substring(0, equals), Long.parseLong(change.substring(equals + 1)));
                }
            }
            open.remove(handle);
        }

        synchronized void abortAfter(long label) {
            preparedLabels.entrySet().removeIf(entry -> {
                if (entry.getValue() > label) {
                    open.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }

        synchronized Map<String, Long> visible() {
            return new LinkedHashMap<>(visible);
        }

        List<String> writes() {
            return writes;
        }

        List<Long> labels() {
            return labels;
        }

        List<Long> committedTwice() {
            return committedTwice;
        }
    }

    /** One connection to the warehouse: transactional, keyed, and able to take a retraction. */
    static final class WarehouseSink implements StreamSinkPlugin {
        private final Warehouse warehouse;
        private long label;
        private int prepares;

        WarehouseSink(Warehouse warehouse) {
            this.warehouse = warehouse;
        }

        @Override
        public String name() {
            return "warehouse";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public SinkCapabilities capabilities() {
            return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), true, true, 0);
        }

        @Override
        public List<String> keyColumns() {
            return List.of("user_id");
        }

        @Override
        public java.util.Optional<StreamSchema> schema() {
            return java.util.Optional.of(StreamSchema.builder("warehouse")
                    .field("user_id", Types.string())
                    .field("amount", Types.int64())
                    .build());
        }

        @Override
        public synchronized int write(List<RowView> batch) {
            for (RowView row : batch) {
                boolean retraction = row.weight() < 0;
                String rendered = (retraction ? "-" : "+") + "[" + row.getString(0) + ", " + row.getLong(1) + "]";
                warehouse.write(handle(), rendered, row.getString(0), row.getLong(1), retraction);
            }
            return batch.size();
        }

        @Override
        public void flush() {}

        @Override
        public synchronized void beginTransaction(long checkpointId) {
            this.label = checkpointId;
        }

        @Override
        public synchronized String prepare(long checkpointId) {
            prepares++;
            String handle = handle();
            warehouse.prepare(handle, label);
            return handle;
        }

        @Override
        public synchronized void commit(String handle) {
            warehouse.commit(handle);
        }

        @Override
        public synchronized void abortAfter(long checkpointId) {
            warehouse.abortAfter(checkpointId);
        }

        private String handle() {
            return "txn-" + label;
        }

        @Override
        public void close() {}
    }

    /** The factory a registration's sink name resolves through. */
    record Warehouses(Warehouse warehouse) implements SinkFactory {

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), true, true, 0);
        }

        @Override
        public Description describe(String sinkName) {
            return new Description(
                    capabilitiesOf(sinkName),
                    java.util.Optional.of(StreamSchema.builder("warehouse")
                            .field("user_id", Types.string())
                            .field("amount", Types.int64())
                            .build()),
                    List.of("user_id"));
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            return new WarehouseSink(warehouse);
        }

        @Override
        public void release(StreamSinkPlugin sink) {
            // The connection goes; the warehouse stays, which is the point of the two objects.
        }
    }
}

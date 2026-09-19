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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exactly-once output: a transactional sink's commit tied to the query's checkpoints.
 *
 * <p>The sink here is two objects on purpose. A {@link Database} is the external system and
 * outlives every process; a {@link TxnSink} is one process's connection to it, holding an open
 * transaction that dies with it. A crash is the connection dying -- nothing it has not prepared
 * survives, nothing it would have sent afterwards arrives -- and a restart is a new registry over
 * the same checkpoint directory with a new connection to the same database. What a reader of the
 * database can see is {@link Database#visible()}, and that is what every assertion reads.
 *
 * <p>Rows are pushed rather than pumped, so there is no source to rewind: after a restart the test
 * feeds again exactly the rows after the checkpoint it restored from, which is what a rewound source
 * would deliver.
 */
class TransactionalSinkDeliveryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String SQL = "SELECT user_id, amount FROM txn";

    private static final SinkCapabilities TRANSACTIONAL =
            new SinkCapabilities(EnumSet.of(EmitMode.APPEND), true, false, 0);

    @TempDir
    Path root;

    private RowArena arena;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    @Test
    void nothingIsVisibleUntilTheCheckpointRecordingItIsDurableAndThenAllOfItIs() {
        Database orders = new Database(root);
        QueryRegistry registry = registry(new TxnSinks().bind("orders", orders));
        RegisteredQuery query = registry.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        query.commit();
        assertThat(orders.written())
                .as("written into the open transaction at the commit")
                .containsExactly("+[u1, 300]", "+[u2, 50]");
        assertThat(orders.visible())
                .as("but a transaction nobody has committed is visible to nobody")
                .isEmpty();

        Checkpoint first = checkpointerOf(query).checkpointNow();

        assertThat(orders.visible()).containsExactly("+[u1, 300]", "+[u2, 50]");
        assertThat(orders.commitsBeforeDurable())
                .as("every commit found the checkpoint recording its handle already stored and readable")
                .isZero();
        assertThat(orders.commitsChecked()).isEqualTo(1);
        assertThat(first.operatorState())
                .as("the handle travels in the checkpoint, beside the view it matches")
                .containsKeys("sink:spend", "served-view");

        feed(query, "u3", 7L);
        query.commit();
        assertThat(orders.visible())
                .as("the next transaction is open until the next checkpoint")
                .containsExactly("+[u1, 300]", "+[u2, 50]");
        checkpointerOf(query).checkpointNow();
        assertThat(orders.visible()).containsExactly("+[u1, 300]", "+[u2, 50]", "+[u3, 7]");
        assertThat(registry.sinkGuarantee("spend"))
                .hasValueSatisfying(text -> assertThat(text).startsWith("exactly-once"));
    }

    /**
     * The crash two-phase commit exists for: after the checkpoint is durable, before the commit
     * reaches the sink. The prepared transaction is recorded in exactly one place -- the checkpoint --
     * and the restore is the only thing that can commit it.
     */
    @Test
    void aCrashBetweenPrepareAndCommitLosesNothingAndRepeatsNothing() {
        Database orders = new Database(root);
        TxnSinks before = new TxnSinks().bind("orders", orders);
        QueryRegistry first = registry(before);
        RegisteredQuery query = first.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        query.commit();
        before.sink("orders").dieInsteadOfCommitting();
        checkpointerOf(query).checkpointNow();

        assertThat(orders.prepared())
                .as("prepared at the cut and stored in a durable checkpoint")
                .hasSize(1);
        assertThat(orders.visible())
                .as("the process died before the commit arrived")
                .isEmpty();

        // Work after the checkpoint, into a connection that is already dead: never seen by anybody.
        feed(query, "u3", 7L);
        query.commit();
        crash(first);

        TxnSinks after = new TxnSinks().bind("orders", orders);
        QueryRegistry second = registry(after);
        RegisteredQuery restarted = second.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");
        assertThat(orders.visible())
                .as("the restore committed what the checkpoint recorded, and sent nothing else")
                .containsExactly("+[u1, 300]", "+[u2, 50]");

        // The source rewinds to the checkpoint's offsets and delivers what came after it.
        feed(restarted, "u3", 7L);
        restarted.commit();
        checkpointerOf(restarted).checkpointNow();

        assertThat(orders.visible())
                .as("every row exactly once: none lost with the dead process, none repeated by the replay")
                .containsExactly("+[u1, 300]", "+[u2, 50]", "+[u3, 7]");
        assertThat(orders.prepared()).isEmpty();
    }

    /**
     * What was written after the restored checkpoint must never be committed: an open transaction
     * dies with its connection, and one prepared at a checkpoint that never became durable is
     * abandoned by name when the restore says where it resumed.
     */
    @Test
    void writesAfterTheRestoredCheckpointAreAbortedAndWrittenAgainOnce() throws Exception {
        Database orders = new Database(root);
        TxnSinks before = new TxnSinks().bind("orders", orders);
        QueryRegistry first = registry(before);
        RegisteredQuery query = first.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        assertThat(orders.visible()).containsExactly("+[u1, 300]");

        // Checkpoint 2 cuts and prepares, then cannot be stored: a directory squats on the name its
        // temporary file needs. Its transaction is prepared in the database and recorded nowhere.
        feed(query, "u2", 50L);
        query.commit();
        Files.createDirectories(root.resolve("spend").resolve("checkpoint-2.tmp"));
        assertThatThrownBy(() -> checkpointerOf(query).checkpointNow()).hasMessageContaining("checkpoint 2");
        assertThat(orders.prepared())
                .as("prepared at the cut of a checkpoint that failed")
                .hasSize(1);

        // And more, into the transaction opened after that cut.
        feed(query, "u3", 7L);
        query.commit();
        crash(first);

        TxnSinks after = new TxnSinks().bind("orders", orders);
        QueryRegistry second = registry(after);
        RegisteredQuery restarted = second.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        assertThat(orders.prepared())
                .as("the transaction prepared after checkpoint 1 is abandoned by the restore from it")
                .isEmpty();
        assertThat(orders.aborted()).hasSize(1);
        assertThat(orders.visible()).containsExactly("+[u1, 300]");

        feed(restarted, "u2", 50L);
        feed(restarted, "u3", 7L);
        restarted.commit();
        checkpointerOf(restarted).checkpointNow();
        assertThat(orders.visible()).containsExactly("+[u1, 300]", "+[u2, 50]", "+[u3, 7]");
    }

    /**
     * The cut is the lane's marker, not the last view commit. A row the lane has applied and nobody
     * has committed is before the marker -- its source offset is past it and a restore will not
     * replay it -- so it must be in the checkpoint's view and in the transaction the checkpoint
     * prepares, or it is in neither and lost.
     */
    @Test
    void aRowAppliedButNotYetCommittedWhenTheCheckpointIsTakenIsInsideIt() {
        Database orders = new Database(root);
        TxnSinks before = new TxnSinks().bind("orders", orders);
        QueryRegistry first = registry(before);
        RegisteredQuery query = first.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        // No commit: the row is applied to the view's overlay and the sink has not been written it.
        checkpointerOf(query).checkpointNow();
        assertThat(orders.visible())
                .as("committed with the checkpoint whose cut it came before")
                .containsExactly("+[u1, 300]");
        crash(first);

        QueryRegistry second = registry(new TxnSinks().bind("orders", orders));
        RegisteredQuery restarted = second.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");
        assertThat(restarted.view().size())
                .as("and in the checkpoint's view, so the restored view agrees with the sink")
                .isEqualTo(1);
        feed(restarted, "u2", 50L);
        restarted.commit();
        checkpointerOf(restarted).checkpointNow();
        assertThat(orders.visible()).containsExactly("+[u1, 300]", "+[u2, 50]");
    }

    @Test
    void aCheckpointThatFailsAfterItsCutLeavesItsTransactionToTheNextOne() throws Exception {
        Database orders = new Database(root);
        QueryRegistry registry = registry(new TxnSinks().bind("orders", orders));
        RegisteredQuery query = registry.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        query.commit();
        Files.createDirectories(root.resolve("spend").resolve("checkpoint-1.tmp"));
        assertThatThrownBy(() -> checkpointerOf(query).checkpointNow()).hasMessageContaining("checkpoint 1");
        assertThat(orders.visible())
                .as("not committed: no durable checkpoint records it, so a restore would replay it")
                .isEmpty();

        // With no crash, nothing will replay it -- so it must not be abandoned either.
        feed(query, "u2", 50L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        assertThat(orders.visible()).containsExactly("+[u1, 300]", "+[u2, 50]");
    }

    @Test
    void aJoiningSinksSeedIsInsideItsFirstTransactionAndCommitsWithTheNextCheckpoint() {
        Database late = new Database(root);
        QueryRegistry registry = registry(new TxnSinks().bind("late", late));
        RegisteredQuery shared = registry.register("early", SQL, List.of(0), DANA);
        feed(shared, "u1", 300L);
        shared.commit();

        registry.registerWritingTo("joined", SQL, List.of(0), DANA, "late");
        // No commit has happened since it joined, so the seed is still owed when the checkpoint
        // cuts: written there, before the prepare, or the checkpoint would record this sink as
        // holding a view it was never sent.
        checkpointerOf(shared).checkpointNow();
        assertThat(late.visible()).containsExactly("+[u1, 300]");

        feed(shared, "u2", 50L);
        shared.commit();
        checkpointerOf(shared).checkpointNow();
        assertThat(late.visible()).as("then changes only").containsExactly("+[u1, 300]", "+[u2, 50]");
    }

    /**
     * Two names, one computation, two transactional sinks, and a restart: the second name is
     * registered after the first has restored the computation and moved it on. Its sink holds the
     * checkpoint's view, so it is owed exactly what changed since -- not the whole view again.
     */
    @Test
    void aSecondNameRegisteredAgainAfterARestartIsSentOnlyWhatItMissed() {
        Database a = new Database(root);
        Database b = new Database(root);
        QueryRegistry first = registry(new TxnSinks().bind("a", a).bind("b", b));
        RegisteredQuery query = first.registerWritingTo("qa", SQL, List.of(0), DANA, "a");
        first.registerWritingTo("qb", SQL, List.of(0), DANA, "b");

        feed(query, "u1", 300L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        assertThat(a.visible()).containsExactly("+[u1, 300]");
        assertThat(b.visible()).containsExactly("+[u1, 300]");
        feed(query, "u2", 50L);
        query.commit();
        crash(first);

        QueryRegistry second = registry(new TxnSinks().bind("a", a).bind("b", b));
        RegisteredQuery restarted = second.registerWritingTo("qa", SQL, List.of(0), DANA, "a");
        feed(restarted, "u2", 50L);
        feed(restarted, "u3", 7L);
        restarted.commit();
        second.registerWritingTo("qb", SQL, List.of(0), DANA, "b");
        checkpointerOf(restarted).checkpointNow();

        assertThat(a.visible()).containsExactly("+[u1, 300]", "+[u2, 50]", "+[u3, 7]");
        assertThat(b.visible())
                .as("u1 once: the restored checkpoint says this sink already holds it")
                .containsExactlyInAnyOrder("+[u1, 300]", "+[u2, 50]", "+[u3, 7]");
    }

    @Test
    void withoutCheckpointsEachCommitIsItsOwnTransactionAndTheGuaranteeSaysSo() {
        Database orders = new Database(root);
        QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), TXN).writingTo(new TxnSinks().bind("orders", orders));
        registries.add(registry);
        RegisteredQuery query = registry.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        query.commit();
        assertThat(orders.visible())
                .as("nothing will ever prepare it at a checkpoint, so the commit is the boundary")
                .containsExactly("+[u1, 300]");
        assertThat(registry.sinkGuarantee("spend"))
                .hasValueSatisfying(
                        text -> assertThat(text).startsWith("at-least-once").contains("takes no checkpoints"));
    }

    @Test
    void droppingTheRegistrationCommitsWhatItsSinkWasWritten() {
        Database orders = new Database(root);
        QueryRegistry registry = registry(new TxnSinks().bind("orders", orders));
        RegisteredQuery query = registry.registerWritingTo("spend", SQL, List.of(0), DANA, "orders");
        feed(query, "u1", 300L);
        query.commit();

        registry.drop("spend");
        assertThat(orders.visible())
                .as("nothing will restore a dropped name, so nothing would ever replay its open transaction")
                .containsExactly("+[u1, 300]");
    }

    @Test
    void theGuaranteeIsStatedPerKindOfSink() {
        TxnSinks sinks = new TxnSinks();
        sinks.bind("txn_sink", new Database(root));
        sinks.bindPlain("upsert_sink", new SinkCapabilities(EnumSet.of(EmitMode.UPSERT), false, true, 0));
        sinks.bindPlain("append_sink", SinkCapabilities.appendOnly());
        QueryRegistry registry = registry(sinks);
        registry.registerWritingTo("spend_txn", SQL, List.of(0), DANA, "txn_sink");
        registry.registerWritingTo("spend_upsert", SQL, List.of(0), DANA, "upsert_sink");
        registry.registerWritingTo("spend_append", SQL, List.of(0), DANA, "append_sink");

        assertThat(registry.sinkGuarantee("spend_txn").orElseThrow()).startsWith("exactly-once");
        assertThat(registry.sinkGuarantee("spend_upsert").orElseThrow()).startsWith("effectively-once");
        assertThat(registry.sinkGuarantee("spend_append").orElseThrow())
                .startsWith("at-least-once")
                .contains("no sequence");
    }

    // ---------------------------------------------------------------------------------------

    private QueryRegistry registry(SinkFactory sinks) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(sinks)
                // Checkpoints are taken by hand, so every one is where the test says it is.
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    /** The process dies: every connection it held is gone, and nothing it does afterwards arrives. */
    private void crash(QueryRegistry registry) {
        TxnSink.killAll();
        registry.close();
        registries.remove(registry);
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void feed(RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    /**
     * The external system: what is committed, what is prepared, what was abandoned. Outlives every
     * sink instance, as a database outlives the processes that write to it.
     */
    static final class Database {
        private final Path checkpoints;
        private final List<String> written = new CopyOnWriteArrayList<>();
        private final List<String> visible = new CopyOnWriteArrayList<>();
        private final Map<String, Prepared> prepared = new LinkedHashMap<>();
        private final Set<String> committed = new java.util.HashSet<>();
        private final List<String> aborted = new CopyOnWriteArrayList<>();
        private int commitsChecked;
        private int commitsBeforeDurable;

        Database(Path checkpoints) {
            this.checkpoints = checkpoints;
        }

        record Prepared(long label, List<String> rows) {}

        synchronized void prepare(String handle, long label, List<String> rows) {
            prepared.put(handle, new Prepared(label, List.copyOf(rows)));
        }

        synchronized void commit(String handle) {
            if (committed.contains(handle)) {
                return; // idempotent, as the SPI requires
            }
            Prepared transaction = prepared.remove(handle);
            if (transaction == null) {
                throw new IllegalStateException("no prepared transaction " + handle);
            }
            commitsChecked++;
            if (!aDurableCheckpointRecords(handle)) {
                commitsBeforeDurable++;
            }
            visible.addAll(transaction.rows());
            committed.add(handle);
        }

        synchronized void abortAfter(long label) {
            prepared.entrySet().removeIf(entry -> {
                if (entry.getValue().label() > label) {
                    aborted.add(entry.getKey());
                    return true;
                }
                return false;
            });
        }

        /** Whether a checkpoint that can be loaded from disk right now carries this handle. */
        private boolean aDurableCheckpointRecords(String handle) {
            try (java.util.stream.Stream<Path> dirs = Files.list(checkpoints)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    FileCheckpointStore store = new FileCheckpointStore(dir);
                    for (long id : store.availableIds()) {
                        var loaded = store.load(id);
                        if (loaded.isPresent()
                                && loaded.get().operatorState().values().stream()
                                        .anyMatch(bytes ->
                                                new String(bytes, StandardCharsets.ISO_8859_1).contains(handle))) {
                            return true;
                        }
                    }
                }
            } catch (java.io.IOException e) {
                return false;
            }
            return false;
        }

        List<String> written() {
            return List.copyOf(written);
        }

        List<String> visible() {
            return List.copyOf(visible);
        }

        synchronized Map<String, Prepared> prepared() {
            return Map.copyOf(prepared);
        }

        List<String> aborted() {
            return List.copyOf(aborted);
        }

        synchronized int commitsChecked() {
            return commitsChecked;
        }

        synchronized int commitsBeforeDurable() {
            return commitsBeforeDurable;
        }
    }

    /** One process's connection to a {@link Database}, holding the open transaction. */
    static final class TxnSink implements StreamSinkPlugin {
        private static final List<TxnSink> LIVE = new CopyOnWriteArrayList<>();
        private static final AtomicInteger INSTANCES = new AtomicInteger();

        private final Database database;
        private final int instance = INSTANCES.incrementAndGet();
        private final List<String> open = new ArrayList<>();
        private long label;
        private volatile boolean dead;
        private volatile boolean dieInsteadOfCommitting;

        TxnSink(Database database) {
            this.database = database;
            LIVE.add(this);
        }

        static void killAll() {
            LIVE.forEach(sink -> sink.dead = true);
            LIVE.clear();
        }

        void dieInsteadOfCommitting() {
            dieInsteadOfCommitting = true;
        }

        @Override
        public SinkCapabilities capabilities() {
            return TRANSACTIONAL;
        }

        @Override
        public synchronized int write(List<RowView> batch) {
            if (dead) {
                return 0;
            }
            for (RowView row : batch) {
                String rendered = (row.weight() < 0 ? "-" : "+") + "[" + row.getString(0) + ", " + row.getLong(1) + "]";
                open.add(rendered);
                database.written.add(rendered);
            }
            return batch.size();
        }

        @Override
        public void flush() {}

        @Override
        public synchronized void beginTransaction(long checkpointId) {
            if (dead) {
                return;
            }
            label = checkpointId;
            open.clear();
        }

        @Override
        public synchronized String prepare(long checkpointId) {
            if (dead) {
                return "";
            }
            String handle = "txn-" + instance + "-" + label + "-at-" + checkpointId;
            database.prepare(handle, label, open);
            open.clear();
            return handle;
        }

        @Override
        public synchronized void commit(String handle) {
            if (dead) {
                return;
            }
            if (dieInsteadOfCommitting) {
                dead = true;
                return;
            }
            database.commit(handle);
        }

        @Override
        public void abortAfter(long checkpointId) {
            if (!dead) {
                database.abortAfter(checkpointId);
            }
        }

        @Override
        public String name() {
            return "txn-recording";
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
        public synchronized void close() {
            // The connection closes and its open transaction goes with it.
            open.clear();
        }
    }

    /** Opens a fresh connection per registration, to a database per sink name. */
    static final class TxnSinks implements SinkFactory {
        private final Map<String, Database> databases = new HashMap<>();
        private final Map<String, SinkCapabilities> plain = new HashMap<>();
        private final Map<String, TxnSink> opened = new HashMap<>();

        TxnSinks bind(String name, Database database) {
            databases.put(name, database);
            return this;
        }

        TxnSinks bindPlain(String name, SinkCapabilities capabilities) {
            plain.put(name, capabilities);
            return this;
        }

        TxnSink sink(String name) {
            return opened.get(name);
        }

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            if (databases.containsKey(sinkName)) {
                return TRANSACTIONAL;
            }
            SinkCapabilities capabilities = plain.get(sinkName);
            if (capabilities == null) {
                throw new IllegalArgumentException("no sink named '" + sinkName + "' is bound");
            }
            return capabilities;
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            if (databases.containsKey(sinkName)) {
                TxnSink sink = new TxnSink(databases.get(sinkName));
                opened.put(sinkName, sink);
                return sink;
            }
            return new SinkDeliveryTest.RecordingSink(sinkName, capabilitiesOf(sinkName));
        }
    }
}

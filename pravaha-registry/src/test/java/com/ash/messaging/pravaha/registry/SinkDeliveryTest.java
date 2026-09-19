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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A registered query's changes reaching the sink its registration named (ADR-043, ADR-039 item 5).
 *
 * <p>Until this, negotiation worked and delivery did not exist: {@code checkAgainst} refused a bad
 * pair at registration, and a good pair was acknowledged and then written to nothing. Every test
 * here reads what the sink actually received, not what the registry says it attached.
 */
class SinkDeliveryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final SinkCapabilities UPSERT =
            new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0);

    private RecordingSinks sinks;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        sinks = new RecordingSinks();
        registry = new QueryRegistry(new ViewCatalog(), TXN).writingTo(sinks);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    @Test
    void committedRowsReachTheSinkAndNothingArrivesBeforeTheCommit() {
        sinks.bind("orders", SinkCapabilities.appendOnly());
        RegisteredQuery query =
                registry.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "orders");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        assertThat(sinks.sink("orders").rows())
                .as("a sink receives whole commits; between them the view holds a half-applied batch")
                .isEmpty();

        query.commit();

        assertThat(sinks.sink("orders").rows()).containsExactly("+[u1, 300]", "+[u2, 50]");
        assertThat(sinks.sink("orders").flushes())
                .as("flushed at the commit, not on a batch size")
                .isPositive();
        assertThat(registry.sinkOf("q")).contains("orders");
        assertThat(registry.rowsWrittenToSink("q")).isEqualTo(2);
    }

    @Test
    void aRevisedAnswerReachesTheSinkAsARetractionThenTheCorrection() throws Exception {
        sinks.bind("totals", UPSERT);
        try (QueryRegistry ticking = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(sinks)
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))) {
            RegisteredQuery query =
                    ticking.registerWritingTo("live_count", "SELECT COUNT(*) FROM txn", List.of(0), DANA, "totals");

            feed(query, "u1", 1L);
            commitUntil(query, () -> sinks.sink("totals").rows().contains("+[1]"));
            feed(query, "u2", 2L);
            commitUntil(query, () -> sinks.sink("totals").rows().contains("+[2]"));

            // The weight is carried, not interpreted: the old count is withdrawn before the new one
            // is written, and a sink maintaining its own total arrives at 2 rather than 3.
            List<String> rows = sinks.sink("totals").rows();
            assertThat(rows.subList(rows.indexOf("+[1]"), rows.size())).containsExactly("+[1]", "-[1]", "+[2]");
        }
    }

    @Test
    void aRevisingQueryAgainstAnAppendOnlySinkIsRefusedBeforeTheSinkIsOpened() {
        sinks.bind("log", SinkCapabilities.appendOnly());

        assertThatThrownBy(() -> registry.registerWritingTo("c", "SELECT COUNT(*) FROM txn", List.of(0), DANA, "log"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2041");

        assertThat(sinks.opened())
                .as("refused as a pair, before a connection was paid for")
                .isZero();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aSinkConfiguredForADifferentRowShapeIsRefusedBeforeItIsOpened() {
        // Swapped columns: the sink would read `amount` where the query put `user_id` and write
        // plausible nonsense with nothing failing.
        StreamSchema swapped = StreamSchema.builder("out")
                .field("amount", Types.int64())
                .field("user_id", Types.string())
                .build();
        sinks.bind("orders", SinkCapabilities.appendOnly(), swapped, List.of());

        assertThatThrownBy(() ->
                        registry.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "orders"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8010")
                .hasMessageContaining("amount:INT64, user_id:STRING");
        assertThat(sinks.opened()).isZero();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aSinkWhoseSchemaMatchesIsAcceptedWhateverTheCaseOfItsColumnNames() {
        StreamSchema same = StreamSchema.builder("out")
                .field("USER_ID", Types.string())
                .field("Amount", Types.int64())
                .build();
        sinks.bind("orders", SinkCapabilities.appendOnly(), same, List.of());

        assertThat(registry.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "orders"))
                .isNotNull();
    }

    @Test
    void aKeyedSinkMustBeKeyedByExactlyTheViewsKey() {
        StreamSchema shape = StreamSchema.builder("out")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
        sinks.bind("by_amount", UPSERT, shape, List.of("amount"));
        sinks.bind("by_user", UPSERT, shape, List.of("user_id"));

        // Keyed by amount while the view is keyed by user_id: two users with one amount would share a
        // record, and retracting one would delete the other's.
        assertThatThrownBy(() -> registry.registerWritingTo(
                        "keyed_bad", "SELECT user_id, amount FROM txn", List.of(0), DANA, "by_amount"))
                .hasMessageContaining("PRV-8010")
                .hasMessageContaining("[amount]")
                .hasMessageContaining("[user_id]");
        assertThat(registry.registerWritingTo(
                        "keyed_ok", "SELECT user_id, amount FROM txn", List.of(0), DANA, "by_user"))
                .isNotNull();
    }

    @Test
    void aSinkNobodyBoundIsRefusedAndNothingIsLeftRegistered() {
        assertThatThrownBy(() ->
                        registry.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "nowhere"))
                .hasMessageContaining("nowhere");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aSecondNameWithItsOwnSinkIsSeededWithWhatTheSharedViewAlreadyHolds() {
        sinks.bind("late", SinkCapabilities.appendOnly());
        RegisteredQuery first = registry.register("early", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        feed(first, "u1", 300L);
        first.commit();

        RegisteredQuery second =
                registry.registerWritingTo("joined", "SELECT user_id, amount FROM txn", List.of(0), DANA, "late");
        assertThat(second).as("one computation, two names (ADR-043 fan-out)").isSameAs(first);

        feed(first, "u2", 50L);
        first.commit();

        // u1 committed before this sink existed. A sink that started at the next change would never
        // hold it, and a keyed table missing a row that never changes again is wrong for ever.
        assertThat(sinks.sink("late").rows()).containsExactlyInAnyOrder("+[u1, 300]", "+[u2, 50]");

        feed(first, "u3", 7L);
        first.commit();
        assertThat(sinks.sink("late").rows())
                .as("then changes only, once seeded")
                .endsWith("+[u3, 7]");
        assertThat(sinks.sink("late").rows()).hasSize(3);
    }

    @Test
    void droppingOneNameReleasesItsSinkAndLeavesTheOtherNamesSinkWriting() {
        sinks.bind("a", SinkCapabilities.appendOnly());
        sinks.bind("b", SinkCapabilities.appendOnly());
        RegisteredQuery query =
                registry.registerWritingTo("qa", "SELECT user_id, amount FROM txn", List.of(0), DANA, "a");
        registry.registerWritingTo("qb", "SELECT user_id, amount FROM txn", List.of(0), DANA, "b");

        registry.drop("qa");
        assertThat(sinks.sink("a").closed())
                .as("the dropped name's sink is let go of")
                .isTrue();
        assertThat(sinks.released()).containsExactly("a");

        feed(query, "u1", 1L);
        query.commit();
        assertThat(sinks.sink("a").rows()).isEmpty();
        assertThat(sinks.sink("b").rows()).containsExactly("+[u1, 1]");
        assertThat(registry.sinkOf("qa")).isEmpty();
    }

    @Test
    void aSinkThatFailsIsDetachedWithPrv8009AndTheQueryKeepsServing() {
        sinks.bind("flaky", SinkCapabilities.appendOnly());
        RegisteredQuery query =
                registry.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "flaky");
        List<Object> subscribed = new CopyOnWriteArrayList<>();
        Subscription subscription = query.subscribe(subscribed::addAll);

        sinks.sink("flaky").failNextWrite();
        feed(query, "u1", 1L);
        query.commit();
        feed(query, "u2", 2L);
        query.commit();

        assertThat(registry.sinkFailure("q"))
                .hasValueSatisfying(failure -> assertThat(failure.getMessage()).contains("PRV-8009", "flaky"));
        assertThat(sinks.sink("flaky").rows())
                .as("nothing is written over the gap a failed batch left")
                .isEmpty();
        assertThat(query.state()).isEqualTo(QueryState.RUNNING);
        assertThat(query.view().size()).isEqualTo(2);
        assertThat(subscribed).as("the view's subscribers carry on").hasSize(2);
        assertThat(subscription.failure()).isEmpty();
        subscription.close();
    }

    @Test
    void aSinkIsNotCountedAsASubscriber() {
        sinks.bind("orders", SinkCapabilities.appendOnly());
        RegisteredQuery query =
                registry.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "orders");

        assertThat(query.subscriberCount()).isZero();
        try (Subscription subscription = query.subscribe(changes -> {})) {
            assertThat(query.subscriberCount()).isEqualTo(1);
        }
    }

    @Test
    void aRestartReattachesTheSinkTheJournalRecorded(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");
        sinks.bind("orders", SinkCapabilities.appendOnly());
        try (QueryRegistry first = journalled(journal, sinks)) {
            first.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "orders");
            first.register("plain", "SELECT user_id FROM txn", List.of(0), DANA);
        }

        RecordingSinks afterRestart = new RecordingSinks();
        afterRestart.bind("orders", SinkCapabilities.appendOnly());
        try (QueryRegistry second = journalled(journal, afterRestart)) {
            QueryRegistry.Recovery recovery = second.recover(id -> Optional.of(DANA));
            assertThat(recovery.recovered()).containsExactly("q", "plain");
            assertThat(second.sinkOf("q"))
                    .as("the sink came back with the query")
                    .contains("orders");
            assertThat(second.sinkOf("plain")).isEmpty();

            RegisteredQuery query = second.require("q");
            feed(query, "u9", 9L);
            query.commit();
            assertThat(afterRestart.sink("orders").rows()).containsExactly("+[u9, 9]");
        }
    }

    @Test
    void aRestartWhoseSinkIsNoLongerBoundRefusesTheQueryByNameRatherThanRecoveringItWithout(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");
        sinks.bind("orders", SinkCapabilities.appendOnly());
        try (QueryRegistry first = journalled(journal, sinks)) {
            first.registerWritingTo("q", "SELECT user_id, amount FROM txn", List.of(0), DANA, "orders");
        }

        try (QueryRegistry second = journalled(journal, new RecordingSinks())) {
            QueryRegistry.Recovery recovery = second.recover(id -> Optional.of(DANA));
            // Recovering it without the sink would look like a successful restart while the table
            // it fed stopped moving.
            assertThat(recovery.recovered()).isEmpty();
            assertThat(recovery.refused())
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.toString()).contains("q", "orders"));
        }
    }

    @Test
    void aJournalWrittenBeforeSinksStillReplays(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");
        new RegistryJournal(journal)
                .recordRegistration(
                        "old",
                        "SELECT user_id, amount FROM txn",
                        List.of(0),
                        "dana",
                        com.ash.messaging.pravaha.serving.Retention.forever(),
                        List.of());

        List<RegistryJournal.Entry> entries = new RegistryJournal(journal).replay();
        assertThat(entries)
                .singleElement()
                .satisfies(entry -> assertThat(entry.sinkName()).isEmpty());
    }

    private static QueryRegistry journalled(Path journal, SinkFactory sinks) {
        return new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .writingTo(sinks)
                .journalTo(new RegistryJournal(journal));
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

    /** An unwindowed aggregate publishes on the lane's thread, so one commit may only schedule it. */
    private static void commitUntil(RegisteredQuery query, java.util.function.BooleanSupplier done)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && !done.getAsBoolean()) {
            query.commit();
            Thread.sleep(20);
        }
        assertThat(done.getAsBoolean())
                .as("the sink received the expected row in time")
                .isTrue();
    }

    /** A {@link SinkFactory} whose sinks record what they were given, rendered as text. */
    static final class RecordingSinks implements SinkFactory {
        private final Map<String, SinkCapabilities> bound = new HashMap<>();
        private final Map<String, StreamSchema> schemas = new HashMap<>();
        private final Map<String, List<String>> keys = new HashMap<>();
        private final Map<String, RecordingSink> byName = new HashMap<>();
        private final List<String> released = new ArrayList<>();
        private int opened;

        void bind(String name, SinkCapabilities capabilities) {
            bound.put(name, capabilities);
        }

        void bind(String name, SinkCapabilities capabilities, StreamSchema schema, List<String> keyColumns) {
            bound.put(name, capabilities);
            schemas.put(name, schema);
            keys.put(name, keyColumns);
        }

        @Override
        public Description describe(String sinkName) {
            return new Description(
                    capabilitiesOf(sinkName),
                    Optional.ofNullable(schemas.get(sinkName)),
                    keys.getOrDefault(sinkName, List.of()));
        }

        RecordingSink sink(String name) {
            return byName.computeIfAbsent(name, n -> new RecordingSink(n, bound.get(n)));
        }

        int opened() {
            return opened;
        }

        List<String> released() {
            return released;
        }

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            SinkCapabilities capabilities = bound.get(sinkName);
            if (capabilities == null) {
                throw new IllegalArgumentException("no sink named '" + sinkName + "' is bound");
            }
            return capabilities;
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            capabilitiesOf(sinkName);
            opened++;
            return sink(sinkName);
        }

        @Override
        public void release(StreamSinkPlugin sink) {
            released.add(((RecordingSink) sink).sinkName);
            sink.close();
        }
    }

    static final class RecordingSink implements StreamSinkPlugin {
        private final String sinkName;
        private final SinkCapabilities capabilities;
        private final List<String> rows = new CopyOnWriteArrayList<>();
        private volatile int flushes;
        private volatile boolean closed;
        private volatile boolean failNext;

        RecordingSink(String sinkName, SinkCapabilities capabilities) {
            this.sinkName = sinkName;
            this.capabilities = capabilities == null ? SinkCapabilities.appendOnly() : capabilities;
        }

        void failNextWrite() {
            failNext = true;
        }

        List<String> rows() {
            return List.copyOf(rows);
        }

        int flushes() {
            return flushes;
        }

        boolean closed() {
            return closed;
        }

        @Override
        public SinkCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public int write(List<RowView> batch) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("the sink's connection was reset");
            }
            for (RowView row : batch) {
                List<Object> values = new ArrayList<>();
                for (int ordinal = 0; ordinal < row.schema().fieldCount(); ordinal++) {
                    values.add(
                            row.isNull(ordinal)
                                    ? null
                                    : switch (row.schema().field(ordinal).type().typeName()) {
                                        case STRING -> row.getString(ordinal);
                                        case INT64 -> row.getLong(ordinal);
                                        default -> "?";
                                    });
                }
                rows.add((row.weight() < 0 ? "-" : "+") + values);
            }
            return batch.size();
        }

        @Override
        public void flush() {
            flushes++;
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
        public void close() {
            closed = true;
        }
    }
}

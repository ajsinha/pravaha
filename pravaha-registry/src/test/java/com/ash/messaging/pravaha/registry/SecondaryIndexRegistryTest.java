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
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code INDEX (column)} end to end (ADR-055): declared on a registration, kept in the view's own
 * commit through updates and retractions that move a key between values of the indexed column,
 * written down with the registration so a restart brings it back, rebuilt over a restored
 * checkpoint -- and at every step a read by the indexed column answers exactly what the scan
 * answers, and the view's counters show it probed rather than scanned.
 */
class SecondaryIndexRegistryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    /**
     * A view of each user's latest payment, keyed by user and indexed by region -- a column a
     * user's next payment can change, and a retraction can change back.
     */
    private static final String LATEST = "CREATE CONTINUOUS QUERY latest KEYED BY (user_id) INDEX (region) "
            + "AS SELECT user_id, region, amount FROM txn";

    private static final int REGION = 1;

    private static final List<String> REGIONS = List.of("eu", "us", "ap");

    @TempDir
    Path root;

    private RowArena arena;
    private long sequence;
    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    // ------------------------------------------------------------------ the equivalence, across a restart

    @Test
    void aReadByTheIndexedColumnIsTheScansAnswerThroughRetractionsAndARestart() {
        ViewCatalog firstViews = new ViewCatalog();
        QueryRegistry first = journalled(firstViews);
        run(first, LATEST);
        RegisteredQuery query = first.require("latest");
        assertThat(query.view().indexedColumns()).containsExactly(REGION);

        txn(query, "u1", "eu", 10, 1);
        txn(query, "u2", "us", 20, 1);
        txn(query, "u3", "eu", 30, 1);
        txn(query, "u4", "ap", 40, 1);
        query.commit();
        assertIndexAnswersAsTheScanDoes(firstViews, query.view());
        assertThat(ids(firstViews, "SELECT user_id FROM latest WHERE region = 'eu'"))
                .containsExactly("u1", "u3");

        // u1 moves from eu to us; u3 is retracted and leaves the view, so it must leave the index.
        txn(query, "u1", "us", 11, 1);
        txn(query, "u3", "eu", 30, -1);
        query.commit();
        assertIndexAnswersAsTheScanDoes(firstViews, query.view());
        assertThat(ids(firstViews, "SELECT user_id FROM latest WHERE region = 'eu'"))
                .isEmpty();
        assertThat(ids(firstViews, "SELECT user_id FROM latest WHERE region = 'us'"))
                .containsExactly("u1", "u2");
        assertThat(query.view().indexEntries(REGION)).isEqualTo(query.view().size());

        checkpointerOf(query).checkpointNow();
        restart(first);

        ViewCatalog secondViews = new ViewCatalog();
        QueryRegistry second = journalled(secondViews);
        assertThat(second.recover(id -> Optional.of(DANA)).recovered()).containsExactly("latest");
        RegisteredQuery restarted = second.require("latest");
        assertThat(restarted.view().indexedColumns())
                .as("the index came back from the journal, not from a second declaration")
                .containsExactly(REGION);
        assertThat(restarted.view().indexEntries(REGION))
                .as("kept over the restored rows before the first read")
                .isEqualTo(3);
        assertIndexAnswersAsTheScanDoes(secondViews, restarted.view());

        txn(restarted, "u2", "eu", 21, 1);
        txn(restarted, "u5", "ap", 50, 1);
        txn(restarted, "u4", "ap", 40, -1);
        restarted.commit();
        assertIndexAnswersAsTheScanDoes(secondViews, restarted.view());
        assertThat(ids(secondViews, "SELECT user_id FROM latest WHERE region = 'ap'"))
                .containsExactly("u5");
        assertThat(ids(secondViews, "SELECT user_id FROM latest WHERE region IN ('eu', 'us')"))
                .containsExactly("u1", "u2");
    }

    /**
     * For every region, the index read and a read the index cannot serve -- the same predicate
     * widened by an {@code OR} on another column that is never true -- return the same rows, and
     * the view's counters show which one probed.
     */
    private static void assertIndexAnswersAsTheScanDoes(ViewCatalog views, ServedView view) {
        for (String region : REGIONS) {
            long probes = view.indexLookups();
            long scans = view.scans();
            List<String> probed = ids(views, "SELECT user_id FROM latest WHERE region = '" + region + "'");
            assertThat(view.indexLookups()).as("%s probed the index", region).isEqualTo(probes + 1);
            assertThat(view.scans()).as("%s did not scan", region).isEqualTo(scans);

            List<String> scanned =
                    ids(views, "SELECT user_id FROM latest WHERE region = '" + region + "' OR amount < 0");
            assertThat(view.scans()).as("the widened read scanned").isEqualTo(scans + 1);
            assertThat(probed).as(region).isEqualTo(scanned);
        }
        assertThat(ids(views, "SELECT user_id FROM latest WHERE region IN ('eu', 'ap')"))
                .isEqualTo(ids(views, "SELECT user_id FROM latest WHERE region IN ('eu', 'ap') OR amount < 0"));
    }

    // ------------------------------------------------------------------ the journal

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void theJournalKeepsTheIndexWithTheRegistrationThroughACompactionAndAReRegistrationClearsIt() {
        RegistryJournal journal = new RegistryJournal(root.resolve("indexes.journal"));
        journal.recordRegistration(
                "a", "SELECT 1", List.of(0), "dana", null, BoundParameters.none(), null, List.of(2, 3));
        journal.recordRegistration("b", "SELECT 2", List.of(0), "dana", null, List.of());
        assertThat(journal.replay().get(0).indexed()).containsExactly(2, 3);
        assertThat(journal.replay().get(1).indexed()).isEmpty();

        journal.compact(journal.replay());
        assertThat(journal.replay().get(0).indexed()).as("compaction keeps it").containsExactly(2, 3);

        journal.recordRegistration("a", "SELECT 1", List.of(0), "dana", null, List.of());
        journal.recordIndexes("gone", List.of(1));
        assertThat(journal.replay())
                .as("a name registered again starts without indexes, and an index for no live name is nobody's")
                .allSatisfy(entry -> assertThat(entry.indexed()).isEmpty());
    }

    // ------------------------------------------------------------------ the spellings, and the refusals

    @Test
    void theWithOptionIsTheSameIndexAsTheClause() {
        QueryRegistry registry = plain();
        run(
                registry,
                "CREATE CONTINUOUS QUERY by_option KEYED BY (user_id) WITH (index = 'region') "
                        + "AS SELECT user_id, region, amount FROM txn");
        assertThat(registry.require("by_option").view().indexedColumns()).containsExactly(REGION);
    }

    @Test
    void anIndexThisEngineCannotKeepIsRefusedByName() {
        QueryRegistry registry = plain();
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY f KEYED BY (user_id) INDEX (ratio) "
                                + "AS SELECT user_id, CAST(amount AS DOUBLE) AS ratio FROM txn"))
                .hasMessageContaining("PRV-2074")
                .hasMessageContaining("NaN");
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY d KEYED BY (user_id) INDEX (price) "
                                + "AS SELECT user_id, CAST(amount AS DECIMAL(21, 2)) AS price FROM txn"))
                .hasMessageContaining("PRV-2074")
                .hasMessageContaining("1.00");
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY k KEYED BY (user_id) INDEX (user_id) "
                                + "AS SELECT user_id, amount FROM txn"))
                .hasMessageContaining("PRV-2074")
                .hasMessageContaining("whole key");
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY u KEYED BY (user_id) INDEX (nothing) "
                                + "AS SELECT user_id, amount FROM txn"))
                .hasMessageContaining("PRV-2071");
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY two KEYED BY (user_id) INDEX (amount, region) "
                                + "AS SELECT user_id, region, amount FROM txn"))
                .hasMessageContaining("PRV-2070")
                .hasMessageContaining("composite");
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY list KEYED BY (user_id) WITH (index = 'amount, region') "
                                + "AS SELECT user_id, region, amount FROM txn"))
                .hasMessageContaining("PRV-8017");
        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE CONTINUOUS QUERY twice KEYED BY (user_id) INDEX (amount) WITH (index = 'amount') "
                                + "AS SELECT user_id, amount FROM txn"))
                .hasMessageContaining("PRV-8017")
                .hasMessageContaining("twice");
        assertThat(registry.names()).as("nothing refused was registered").isEmpty();
    }

    @Test
    void anIndexOnOneColumnOfAWiderKeyIsKept() {
        // A key of (user_id, region) is not probed by user_id alone -- that read is a scan -- so an
        // index over one of its columns is a real path, not a second copy of the key's.
        QueryRegistry registry = plain();
        run(
                registry,
                "CREATE CONTINUOUS QUERY pairs KEYED BY (user_id, region) INDEX (user_id) "
                        + "AS SELECT user_id, region, amount FROM txn");
        assertThat(registry.require("pairs").view().indexedColumns()).containsExactly(0);
    }

    @Test
    void twoNamesSharingAComputationShareTheViewAndBothIndexes() {
        QueryRegistry registry = plain();
        String select = "AS SELECT user_id, region, amount FROM txn";
        run(registry, "CREATE CONTINUOUS QUERY first_name KEYED BY (user_id) INDEX (region) " + select);
        run(registry, "CREATE CONTINUOUS QUERY second_name KEYED BY (user_id) INDEX (amount) " + select);

        assertThat(registry.require("first_name").view())
                .isSameAs(registry.require("second_name").view());
        assertThat(registry.require("first_name").view().indexedColumns()).containsExactly(1, 2);
    }

    /**
     * IDXSHR-1: dropping one name of a shared computation lets go of the index only that name
     * declared, at once; an index another name declared stays, and so does one both declared.
     */
    @Test
    void droppingOneNameOfASharedComputationDropsTheIndexOnlyItDeclared() {
        ViewCatalog views = new ViewCatalog();
        QueryRegistry registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        registries.add(registry);
        String select = "AS SELECT user_id, region, amount FROM txn";
        run(registry, "CREATE CONTINUOUS QUERY first_name KEYED BY (user_id) INDEX (region) " + select);
        run(registry, "CREATE CONTINUOUS QUERY second_name KEYED BY (user_id) INDEX (amount) " + select);
        run(registry, "CREATE CONTINUOUS QUERY third_name KEYED BY (user_id) INDEX (region) " + select);
        ServedView view = registry.require("first_name").view();
        RegisteredQuery shared = registry.require("first_name");
        txn(shared, "u1", "eu", 10, 1);
        shared.commit();
        assertThat(view.indexedColumns()).containsExactly(1, 2);

        run(registry, "DROP CONTINUOUS QUERY second_name");
        assertThat(view.indexedColumns())
                .as("amount was declared by second_name alone")
                .containsExactly(1);
        assertThat(view.indexEntries(2)).isZero();

        run(registry, "DROP CONTINUOUS QUERY first_name");
        assertThat(view.indexedColumns())
                .as("region is still declared by third_name")
                .containsExactly(1);
        assertThat(ids(views, "SELECT user_id FROM third_name WHERE region = 'eu'"))
                .containsExactly("u1");
    }

    // ------------------------------------------------------------------ a replacement

    @Test
    void aReplacementCarriesTheIndexToTheNewVersionByNameAndRefusesToChangeIt() {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < 9; i++) {
            log.append("u" + (i % 3), REGIONS.get(i % 3), (long) (i + 1));
        }
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .feedingFrom(log)
                .journalTo(new RegistryJournal(root.resolve("replace.journal")));
        registries.add(registry);
        run(registry, LATEST);
        await(() -> registry.require("latest").view().size() == 3);

        assertThatThrownBy(() -> run(
                        registry,
                        "CREATE OR REPLACE CONTINUOUS QUERY latest KEYED BY (user_id) INDEX (amount) "
                                + "AS SELECT user_id, amount, region FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2072");

        run(
                registry,
                "CREATE OR REPLACE CONTINUOUS QUERY latest KEYED BY (user_id) WITH (cutover = 'manual') "
                        + "AS SELECT user_id, amount, region FROM txn");
        await(() -> registry.replacements().of("latest").orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
        registry.replacements().cutOver("latest", DANA);

        ServedView now = registry.require("latest").view();
        assertThat(now.schema().field(2).name()).isEqualTo("region");
        assertThat(now.indexedColumns())
                .as("by name: region moved from column 1 to 2")
                .containsExactly(2);
        assertThat(java.util.Objects.requireNonNull(registry.journal())
                        .replay()
                        .get(0)
                        .indexed())
                .containsExactly(2);

        registry.replacements().rollBack("latest", DANA);
        assertThat(registry.require("latest").view().indexedColumns()).containsExactly(REGION);
        assertThat(registry.journal().replay().get(0).indexed()).containsExactly(REGION);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> ids(ViewCatalog views, String sql) {
        List<String> ids = new ArrayList<>();
        for (Object[] row : new ViewQuery(views).execute(sql).rows()) {
            ids.add((String) row[0]);
        }
        ids.sort(null);
        return ids;
    }

    private static ViewQuery.Result run(QueryRegistry registry, String sql) {
        return new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(ContinuousStatements.recognize(sql).orElseThrow(), DANA);
    }

    private QueryRegistry plain() {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        registries.add(registry);
        return registry;
    }

    private QueryRegistry journalled(ViewCatalog views) {
        QueryRegistry registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .journalTo(new RegistryJournal(root.resolve("registry.journal")))
                .checkpointingTo(
                        root.resolve("checkpoints"),
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private void restart(QueryRegistry registry) {
        registry.close();
        registries.remove(registry);
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    private void txn(RegisteredQuery query, String user, String region, long amount, long weight) {
        long at = ++sequence;
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, region).setLong(2, amount);
        writer.weight(weight).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
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
        throw new AssertionError("timed out");
    }
}

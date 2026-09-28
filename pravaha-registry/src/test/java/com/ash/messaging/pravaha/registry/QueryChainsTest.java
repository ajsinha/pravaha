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
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Queries over queries (ADR-056): a query whose FROM names another query's view follows that
 * query's answer -- its snapshot, then every change -- and stays exactly the aggregate of it through
 * updates, deletes, a restart of both, and a chain of three.
 */
@Timeout(120)
class QueryChainsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String CLEANED = "SELECT user_id, region, amount FROM txn WHERE amount > 0";
    private static final String BY_REGION =
            "SELECT region, SUM(amount) AS total, COUNT(*) AS n FROM cleaned GROUP BY region";

    @TempDir
    Path root;

    private RowArena arena;
    private final List<QueryRegistry> registries = new ArrayList<>();
    private long clock = 1_000_000_000L;

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
    void anAggregateOverAFilterViewFollowsInsertsUpdatesAndDeletes() {
        QueryRegistry registry = registry(false);
        RegisteredQuery cleaned = registry.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery byRegion = registry.register("by_region", BY_REGION, List.of(0), DANA);

        txn(cleaned, "u1", "eu", 100, 1);
        txn(cleaned, "u2", "eu", 50, 1);
        txn(cleaned, "u3", "us", 70, 1);
        txn(cleaned, "u4", "us", -5, 1); // filtered out upstream: never reaches the downstream
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(150L, 2L), "us", List.of(70L, 1L)));

        // An upsert: the upstream's view REPLACES u1's row. Its changelog carries only the +1, and a
        // downstream summing that would say eu = 180; the answer's change is -100 then +30.
        txn(cleaned, "u1", "eu", 30, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(80L, 2L), "us", List.of(70L, 1L)));

        // A delete: u2's row retracted upstream, so it leaves the downstream's group.
        txn(cleaned, "u2", "eu", 50, -1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(30L, 1L), "us", List.of(70L, 1L)));

        // u3 moves region: us empties and is retracted from the downstream's view, not left at zero.
        txn(cleaned, "u3", "eu", 5, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(35L, 2L)));

        assertThat(registry.readsFrom("by_region")).containsExactly("cleaned");
        assertThat(registry.dependantsOf("cleaned")).containsExactly("by_region");
        assertThat(byRegion.feedStatus().sources())
                .singleElement()
                .satisfies(source -> assertThat(source.stream()).isEqualTo("cleaned"));
    }

    @Test
    void aPausedDownstreamHoldsItsAnswerAndCatchesUpWhenResumed() {
        QueryRegistry registry = registry(false);
        RegisteredQuery cleaned = registry.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery byRegion = registry.register("by_region", BY_REGION, List.of(0), DANA);
        txn(cleaned, "u1", "eu", 10, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(10L, 1L)));

        registry.pause("by_region");
        txn(cleaned, "u2", "eu", 5, 1);
        txn(cleaned, "u1", "eu", 10, -1);
        cleaned.commit();
        java.util.concurrent.locks.LockSupport.parkNanos(300_000_000L);
        assertThat(byRegion.view().scan())
                .singleElement()
                .satisfies(row -> assertThat(row[1]).isEqualTo(10L));
        assertThat(byRegion.feedStatus().sources())
                .singleElement()
                .satisfies(source -> assertThat(source.state()).isEqualTo(FeedStatus.SourceState.PAUSED));

        registry.resume("by_region");
        awaitRows(byRegion, Map.of("eu", List.of(5L, 1L)));

        // Pausing the upstream stops its answer moving, so the downstream keeps the one it has.
        registry.pause("cleaned");
        assertThat(cleaned.accept("txn", null)).isFalse();
        registry.resume("cleaned");
        txn(cleaned, "u3", "us", 1, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(5L, 1L), "us", List.of(1L, 1L)));
    }

    @Test
    void aThreeLevelChainAnswersItsAlertCondition() {
        QueryRegistry registry = registry(false);
        RegisteredQuery cleaned = registry.register("cleaned", CLEANED, List.of(0), DANA);
        registry.register("by_region", BY_REGION, List.of(0), DANA);
        RegisteredQuery alert = registry.register(
                "big_regions", "SELECT region, total FROM by_region WHERE total > 100", List.of(0), DANA);

        txn(cleaned, "u1", "eu", 60, 1);
        txn(cleaned, "u2", "us", 500, 1);
        cleaned.commit();
        awaitRows(alert, Map.of("us", List.of(500L)));

        txn(cleaned, "u3", "eu", 70, 1);
        cleaned.commit();
        awaitRows(alert, Map.of("eu", List.of(130L), "us", List.of(500L)));

        txn(cleaned, "u2", "us", 500, -1);
        cleaned.commit();
        awaitRows(alert, Map.of("eu", List.of(130L)));

        assertThat(registry.readsFrom("big_regions")).containsExactly("by_region");
        // What GET /api/v1/queries/{name} reports, through the listing's own visibility rules.
        QueryListing listing = new QueryListing(
                registry, com.ash.messaging.pravaha.security.SecurityPolicy.PERMISSIVE, AuditSink.NONE);
        QueryListing.Entry middle = listing.find(DANA, "by_region", "test").orElseThrow();
        assertThat(listing.readsFrom(DANA, middle, "test")).containsExactly("cleaned");
        assertThat(listing.dependants(DANA, middle, "test")).containsExactly("big_regions");
        assertThat(alert.view().derivedFrom())
                .as("authorization follows the data down the chain to the stream")
                .contains("by_region", "cleaned", "txn");
    }

    @Test
    void aRestartOfBothRestoresTheDownstreamExactlyWhicheverCheckpointedLater() {
        QueryRegistry first = registry(true);
        RegisteredQuery cleaned = first.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery byRegion = first.register("by_region", BY_REGION, List.of(0), DANA);
        txn(cleaned, "u1", "eu", 100, 1);
        txn(cleaned, "u2", "us", 40, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(100L, 1L), "us", List.of(40L, 1L)));
        checkpointerOf(cleaned).checkpointNow();

        // The downstream checkpoints AHEAD of the upstream: it has consumed u3, which the upstream's
        // checkpoint does not hold. After the restart the upstream's answer lacks u3 until its
        // source replays it -- here, never -- and the downstream must agree with the upstream.
        txn(cleaned, "u3", "eu", 7, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(107L, 2L), "us", List.of(40L, 1L)));
        checkpointerOf(byRegion).checkpointNow();
        restart(first);

        QueryRegistry second = registry(true);
        RegisteredQuery cleanedAgain = second.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery byRegionAgain = second.register("by_region", BY_REGION, List.of(0), DANA);
        awaitRows(byRegionAgain, Map.of("eu", List.of(100L, 1L), "us", List.of(40L, 1L)));

        txn(cleanedAgain, "u1", "eu", 10, 1);
        txn(cleanedAgain, "u4", "us", 2, 1);
        cleanedAgain.commit();
        awaitRows(byRegionAgain, Map.of("eu", List.of(10L, 1L), "us", List.of(42L, 2L)));
    }

    @Test
    void aRestartRestoresTheDownstreamWhenTheUpstreamCheckpointedLater() {
        QueryRegistry first = registry(true);
        RegisteredQuery cleaned = first.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery byRegion = first.register("by_region", BY_REGION, List.of(0), DANA);
        txn(cleaned, "u1", "eu", 100, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(100L, 1L)));
        checkpointerOf(byRegion).checkpointNow();

        txn(cleaned, "u2", "eu", 20, 1);
        txn(cleaned, "u1", "eu", 50, 1);
        cleaned.commit();
        awaitRows(byRegion, Map.of("eu", List.of(70L, 2L)));
        checkpointerOf(cleaned).checkpointNow();
        restart(first);

        QueryRegistry second = registry(true);
        second.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery byRegionAgain = second.register("by_region", BY_REGION, List.of(0), DANA);
        awaitRows(byRegionAgain, Map.of("eu", List.of(70L, 2L)));
    }

    @Test
    void droppingAnUpstreamThatOthersReadIsRefusedNamingThem() {
        QueryRegistry registry = registry(false);
        registry.register("cleaned", CLEANED, List.of(0), DANA);
        registry.register("by_region", BY_REGION, List.of(0), DANA);
        registry.register("big_eu", "SELECT user_id, amount FROM cleaned WHERE region = 'eu'", List.of(0), DANA);

        assertThatThrownBy(() -> registry.drop("cleaned"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.QUERY_HAS_DEPENDANTS))
                .hasMessageContaining("[big_eu, by_region]");
        assertThat(registry.find("cleaned")).isPresent();

        registry.drop("by_region");
        registry.drop("big_eu");
        registry.drop("cleaned");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aReplacementThatWouldReadItsOwnAnswerIsRefusedAsACycle() {
        QueryRegistry registry = registry(false);
        registry.register("cleaned", CLEANED, List.of(0), DANA);
        registry.register("regions", "SELECT user_id, region FROM cleaned", List.of(0), DANA);

        assertThatThrownBy(() -> registry.replacements()
                        .replace(
                                "cleaned",
                                "SELECT user_id, region FROM regions",
                                List.of(0),
                                DANA,
                                ReplacementOptions.defaults()))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.QUERY_CYCLE))
                .hasMessageContaining("cleaned reads regions reads cleaned");
        assertThatThrownBy(() -> registry.replacements()
                        .replace(
                                "cleaned",
                                CLEANED + " AND amount < 1000",
                                List.of(0),
                                DANA,
                                ReplacementOptions.defaults()))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.CHAIN_UNSUPPORTED))
                .hasMessageContaining("[regions]");
    }

    @Test
    void whatCannotBeMaintainedOverAViewIsRefusedByName() {
        QueryRegistry registry = registry(false);
        registry.register("cleaned", CLEANED, List.of(0), DANA);

        assertThatThrownBy(() -> registry.register(
                        "highest", "SELECT region, MAX(amount) AS top FROM cleaned GROUP BY region", List.of(0), DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.VIEW_INPUT_UNSUPPORTED))
                .hasMessageContaining("MAX");
        assertThatThrownBy(() -> registry.register(
                        "distinct_users",
                        "SELECT region, COUNT(DISTINCT user_id) AS users FROM cleaned GROUP BY region",
                        List.of(0),
                        DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.VIEW_INPUT_UNSUPPORTED));
        assertThatThrownBy(() -> registry.register(
                        "joined",
                        "SELECT c.user_id, t.amount FROM cleaned c JOIN txn t ON c.user_id = t.user_id",
                        List.of(0),
                        DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.VIEW_INPUT_UNSUPPORTED))
                .hasMessageContaining("a join");
        assertThatThrownBy(() -> registry.register(
                        "kept_a_day",
                        "SELECT user_id, amount FROM cleaned",
                        List.of(0),
                        DANA,
                        Retention.ofAge(Duration.ofDays(1))))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.CHAIN_UNSUPPORTED));
        assertThat(registry.names()).containsExactly("cleaned");
    }

    @Test
    void aChainDeeperThanEightIsRefused() {
        QueryRegistry registry = registry(false);
        registry.register("level0", CLEANED, List.of(0), DANA);
        for (int level = 1; level <= QueryChains.MAX_DEPTH; level++) {
            registry.register("level" + level, "SELECT user_id, amount FROM level" + (level - 1), List.of(0), DANA);
        }
        assertThatThrownBy(() -> registry.register("level9", "SELECT user_id, amount FROM level8", List.of(0), DANA))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RegistryErrors.CHAIN_TOO_DEEP));
    }

    @Test
    void anotherTenantsViewIsANameThatDoesNotExistAndProvenanceIsAuthorized() {
        QueryRegistry registry = new QueryRegistry(
                new ViewCatalog(),
                (principal, view) -> principal.id().equals("eve") && view.equals("txn")
                        ? AccessDecision.deny("eve may not read txn")
                        : AccessDecision.allow(),
                AuditSink.NONE,
                TXN);
        registries.add(registry);
        registry.register("cleaned", CLEANED, List.of(0), DANA);

        Principal other = new Principal("olga", "globex", Set.of("analyst"), Map.of());
        assertThatThrownBy(() -> registry.register("theirs", "SELECT user_id FROM cleaned", List.of(0), other))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("cleaned");
        assertThat(registry.find("theirs")).isEmpty();

        Principal eve = new Principal("eve", "acme", Set.of("analyst"), Map.of());
        assertThatThrownBy(() -> registry.register("eves", "SELECT user_id FROM cleaned", List.of(0), eve))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.errorCode()).isEqualTo(SecurityErrors.FORBIDDEN))
                .hasMessageContaining("'txn'");
    }

    @Test
    void identicalSqlOverOneViewSharesOneComputation() {
        QueryRegistry registry = registry(false);
        registry.register("cleaned", CLEANED, List.of(0), DANA);
        RegisteredQuery a = registry.register("by_region", BY_REGION, List.of(0), DANA);
        RegisteredQuery b = registry.register("by_region_too", BY_REGION, List.of(0), DANA);
        assertThat(b).isSameAs(a);
        assertThat(registry.dependantsOf("cleaned")).containsExactly("by_region", "by_region_too");
    }

    // ------------------------------------------------------------------ helpers

    private void restart(QueryRegistry registry) {
        registry.close();
        registries.remove(registry);
    }

    private QueryRegistry registry(boolean checkpointed) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN);
        if (checkpointed) {
            registry.checkpointingTo(
                    root,
                    Configuration.builder()
                            .set("pravaha.checkpoint.interval", "1h")
                            .set("pravaha.checkpoint.timeout", "10s")
                            .build());
        }
        registries.add(registry);
        return registry;
    }

    /** Waits until the view holds exactly {@code expected}: key to the remaining columns. */
    private static void awaitRows(RegisteredQuery query, Map<String, List<Object>> expected) {
        Supplier<Map<String, List<Object>>> actual = () -> {
            Map<String, List<Object>> rows = new TreeMap<>();
            for (Object[] row : query.view().scan()) {
                List<Object> rest = new ArrayList<>(List.of(row).subList(1, row.length));
                rows.put(String.valueOf(row[0]), rest);
            }
            return rows;
        };
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!actual.get().equals(new TreeMap<>(expected)) && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        }
        assertThat(actual.get()).isEqualTo(new TreeMap<>(expected));
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

    private void txn(RegisteredQuery query, String user, String region, long amount, long weight) {
        long ts = clock += 1_000_000L;
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, region).setLong(2, amount).setLong(3, ts);
        writer.weight(weight).eventTimestampNanos(ts).sequence(ts).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}

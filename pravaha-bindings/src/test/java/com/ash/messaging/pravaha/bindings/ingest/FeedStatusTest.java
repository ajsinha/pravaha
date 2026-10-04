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
package com.ash.messaging.pravaha.bindings.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEED-1: a source that fails mid-read stops its feed, and the stop is data an operator can read.
 *
 * <p>The feed has always stopped rather than spun, on purpose, and the query has always stayed
 * {@code RUNNING} with its view answering at the frontier it reached. What was missing was anybody
 * being able to tell: the record lived in a sentence of {@code describe()} that nothing an operator
 * uses called. These pin the record itself -- which source, which partition, which code, when, and
 * whether this partition's own read raised it -- for a reader of the query's own and for one shared
 * with other queries.
 */
class FeedStatusTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA_SPEC = "id:INT64,user_id:STRING,amount:INT64";

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    /** A credential the counting-scan binding is configured with, which a failure then quotes. */
    private static final String SECRET = "hunter2-long-enough-to-be-a-secret";

    @BeforeEach
    void reset() {
        CountingScanPlugin.reset();
    }

    @Test
    void aFileThatFailsMidReadStopsTheFeedWithTheSourcesOwnCodeAndSaysWhere(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("txn.csv");
        Files.writeString(data, "1,ann,100\n2,bob,250\n");
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC, "follow", "true")));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register("live", "SELECT id, amount FROM txn", List.of(0), DANA);
            awaitView(views, "SELECT id, amount FROM live", 2);

            FeedStatus reading = query.feedStatus();
            assertThat(reading.state()).isEqualTo(FeedStatus.State.RUNNING);
            assertThat(reading.stopped()).isFalse();
            assertThat(reading.sources()).singleElement().satisfies(source -> {
                assertThat(source.where()).isEqualTo("txn#0");
                assertThat(source.state()).isEqualTo(FeedStatus.SourceState.RUNNING);
                assertThat(source.shared()).isFalse();
                assertThat(source.stop()).isNull();
            });

            // Mid-read: the query is running and has read two rows when the third cannot be decoded.
            Files.writeString(data, "3,cat,not-a-number\n", StandardOpenOption.APPEND);
            FeedStatus stopped = awaitStopped(query);

            assertThat(stopped.state()).isEqualTo(FeedStatus.State.STOPPED);
            assertThat(stopped.description()).isEqualTo("reading txn (1 partition)");
            assertThat(stopped.failures()).isEqualTo(1);
            FeedStatus.Source source = stopped.firstStopped().orElseThrow();
            assertThat(source.where()).isEqualTo("txn#0");
            assertThat(source.state()).isEqualTo(FeedStatus.SourceState.STOPPED);
            assertThat(stopOf(source).code())
                    .as("the source's own code, not the generic feed failure")
                    .isEqualTo("PRV-5040");
            assertThat(stopOf(source).origin())
                    .as("this partition's own read raised it")
                    .isTrue();
            assertThat(stopOf(source).at()).isNotNull();

            // The state keeps its meaning: RUNNING, and the view answers at the frontier it reached.
            assertThat(query.state()).isEqualTo(QueryState.RUNNING);
            assertThat(new ViewQuery(views)
                            .execute("SELECT id, amount FROM live")
                            .size())
                    .isEqualTo(2);
            // And the old sentence still says so, for whoever reads it.
            assertThat(query.feed().describe()).contains("stopped:").contains("PRV-5040");
        }
    }

    @Test
    void aHealthyFeedIsRunningAndPausedWhileItsQueryIs(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("txn.csv");
        Files.writeString(data, "1,ann,100\n");
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register("calm", "SELECT id, amount FROM txn", List.of(0), DANA);
            awaitView(views, "SELECT id, amount FROM calm", 1);
            assertThat(query.feedStatus().state()).isEqualTo(FeedStatus.State.RUNNING);

            registry.pause("calm");
            assertThat(query.feedStatus().state()).isEqualTo(FeedStatus.State.PAUSED);
            assertThat(query.feedStatus().sources())
                    .allSatisfy(source -> assertThat(source.state()).isEqualTo(FeedStatus.SourceState.PAUSED));

            registry.resume("calm");
            assertThat(query.feedStatus().state()).isEqualTo(FeedStatus.State.RUNNING);
            assertThat(query.feedStatus().failures()).isZero();
        }
    }

    @Test
    void aQueryWithNothingBoundHasNoFeedRatherThanARunningOne() {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(new PluginSourceFeeds())) {
            RegisteredQuery query = registry.register("pushed", "SELECT id, amount FROM txn", List.of(0), DANA);
            assertThat(query.feedStatus().state()).isEqualTo(FeedStatus.State.NONE);
            assertThat(query.feedStatus().sources()).isEmpty();
            assertThat(query.feedStatus().stopped()).isFalse();
        }
    }

    @SuppressWarnings("StaticAssignmentOfThrowable") // a test fixture's one-shot failure hook
    @Test
    void aSharedReaderThatStopsStopsEveryQueryItFeedsAndItsCredentialIsStruckOut() throws Exception {
        PluginSourceFeeds feeds =
                new PluginSourceFeeds().bind(new SourceBinding("shared", "counting-scan", Map.of("password", SECRET)));
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds)) {
            RegisteredQuery one = registry.register(
                    "first_q", "SELECT user_id, amount FROM shared WHERE amount > 0", List.of(0), DANA);
            RegisteredQuery another = registry.register(
                    "second_q", "SELECT user_id, amount FROM shared WHERE amount > 1", List.of(0), DANA);
            awaitView(views, "SELECT user_id, amount FROM first_q", 1);
            awaitView(views, "SELECT user_id, amount FROM second_q", 1);
            assertThat(one.feedStatus().sources())
                    .singleElement()
                    .satisfies(source -> assertThat(source.shared()).isTrue());

            CountingScanPlugin.failNextScan = new PravahaException(
                    new ErrorCode(5080, "AEROSPIKE_CONNECT_FAILED"),
                    "the store refused the login for password " + SECRET);
            CountingScanPlugin.append(2, 200);

            for (RegisteredQuery query : List.of(one, another)) {
                FeedStatus stopped = awaitStopped(query);
                FeedStatus.Source source = stopped.firstStopped().orElseThrow();
                assertThat(source.where()).isEqualTo("shared#0");
                assertThat(source.shared())
                        .as("one reader, so one stop for every query on it")
                        .isTrue();
                assertThat(stopOf(source).origin()).isTrue();
                assertThat(stopOf(source).code()).isEqualTo("PRV-5080");
                assertThat(stopOf(source).message())
                        .as("a binding's option values never leave in a failure's text")
                        .doesNotContain(SECRET)
                        .contains("[redacted password]");
                assertThat(query.state()).isEqualTo(QueryState.RUNNING);
            }
            assertThat(stopOf(one.feedStatus().firstStopped().orElseThrow()).failure())
                    .as("the same recorded failure, not a copy per query")
                    .isSameAs(stopOf(another.feedStatus().firstStopped().orElseThrow())
                            .failure());
        }
    }

    @SuppressWarnings("StaticAssignmentOfThrowable") // a test fixture's one-shot failure hook
    @Test
    void aReaderOfItsOwnThatThrowsSomethingUncodedStopsWithTheFeedsCode() throws Exception {
        // Exactly-once keeps a reader per query, so this is PumpingFeed's path, not the shared one.
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("shared", "counting-scan", Map.of("guarantee", "EXACTLY_ONCE")));
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds)) {
            RegisteredQuery query =
                    registry.register("own", "SELECT user_id, amount FROM shared WHERE amount > 0", List.of(0), DANA);
            awaitView(views, "SELECT user_id, amount FROM own", 1);
            assertThat(query.feedStatus().sources())
                    .singleElement()
                    .satisfies(source -> assertThat(source.shared()).isFalse());

            CountingScanPlugin.failNextScan = new IllegalStateException("the set was dropped");
            FeedStatus stopped = awaitStopped(query);

            FeedStatus.Source source = stopped.firstStopped().orElseThrow();
            assertThat(stopOf(source).code()).isEqualTo("PRV-5092");
            assertThat(stopOf(source).message()).contains("the set was dropped");
            assertThat(stopOf(source).origin()).isTrue();
            assertThat(stopped.failures()).isEqualTo(1);
        }
    }

    private static FeedStatus awaitStopped(RegisteredQuery query) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            FeedStatus status = query.feedStatus();
            if (status.stopped()) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the feed did not stop in fifteen seconds: " + query.feedStatus());
    }

    private static void awaitView(ViewCatalog views, String sql, int expected) throws InterruptedException {
        ViewQuery reader = new ViewQuery(views);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int size = -1;
        while (System.nanoTime() < deadline) {
            size = reader.execute(sql).size();
            if (size >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(size).as("rows in the view after fifteen seconds").isGreaterThanOrEqualTo(expected);
    }

    /** A stopped source's stop, which its record requires. */
    private static FeedStatus.Stop stopOf(FeedStatus.Source source) {
        return java.util.Objects.requireNonNull(source.stop(), "a stopped source says why");
    }
}

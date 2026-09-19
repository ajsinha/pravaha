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
package com.ash.messaging.pravaha.sdk.flight;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEED-1 over Flight: {@code pravaha.list} carries each query's feed, and the Java SDK reads it.
 *
 * <p>The feed is a stand-in here, stopped on demand, so the test is about the wire and the SDK; that
 * a real source stops its feed and records why is {@code FeedStatusTest}'s, and that a node reports
 * it on HTTP is {@code FeedStatusSurfacesTest}'s.
 */
@Timeout(90)
class JavaSdkFeedStatusTest {

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Instant AT = Instant.parse("2026-09-19T08:00:00Z");

    /** Feeds by the query that opened them, so a test can stop one. */
    private final Map<String, StubFeed> feeds = new ConcurrentHashMap<>();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private PravahaFlightClient client;

    @BeforeEach
    void start() {
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE)
                .feedingFrom((name, execution, streams, afterDelivery, resumeFrom) ->
                        feeds.computeIfAbsent(name, ignored -> new StubFeed()));
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
        registry.close();
    }

    @Test
    void aRunningFeedIsListedAsRunningWithNoStop() {
        client.register("healthy", "SELECT trade_id, amount FROM trade", List.of(0));

        RegisteredQueryInfo listed = client.queries().get(0);
        assertThat(listed.feed()).isEqualTo("RUNNING");
        assertThat(listed.isSourceStopped()).isFalse();
        assertThat(listed.feedStop()).isNull();
    }

    @Test
    void aStoppedFeedIsListedWithItsCodeWhereAndWhenAndTheQueryStillRunning() {
        client.register("stalled", "SELECT trade_id, amount FROM trade", List.of(0));
        feeds.get("stalled").stopped = new PravahaException(
                new ErrorCode(5107, "KAFKA_READ_FAILED"), "reading trades-3 failed: the topic was deleted");

        RegisteredQueryInfo listed = client.queries().get(0);
        assertThat(listed.state()).isEqualTo("RUNNING");
        assertThat(listed.isRunning()).isTrue();
        assertThat(listed.feed()).isEqualTo("STOPPED");
        assertThat(listed.isSourceStopped()).isTrue();
        assertThat(listed.feedStop().code()).isEqualTo("PRV-5107");
        assertThat(listed.feedStop().message()).endsWith("reading trades-3 failed: the topic was deleted");
        assertThat(listed.feedStop().where()).isEqualTo("trade#3");
        assertThat(listed.feedStop().at()).isEqualTo("2026-09-19T08:00:00Z");
    }

    /** A feed reading trade#3, stopped once {@link #stopped} is set. */
    private static final class StubFeed implements SourceFeed {

        private volatile PravahaException stopped;

        @Override
        public FeedStatus status() {
            PravahaException failure = stopped;
            FeedStatus.Source source = failure == null
                    ? new FeedStatus.Source("trade", 3, false, FeedStatus.SourceState.RUNNING, null)
                    : new FeedStatus.Source(
                            "trade", 3, false, FeedStatus.SourceState.STOPPED, new FeedStatus.Stop(failure, AT, true));
            return FeedStatus.of(describe(), List.of(source));
        }

        @Override
        public String describe() {
            return "reading trade (1 partition)";
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public long rowsFed() {
            return 0;
        }

        @Override
        public void close() {}
    }
}

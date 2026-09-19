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
package com.ash.messaging.pravaha.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEED-1 in {@code pravaha queries}: a query whose source stopped says so in its state cell, and
 * the listing says with which code, where and when -- without being asked for {@code --verbose}.
 */
@Timeout(90)
class CliFeedStatusTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("amount", Types.int64())
            .build();

    private final Map<String, StubFeed> feeds = new ConcurrentHashMap<>();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private String url;

    @BeforeEach
    void start() {
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .feedingFrom((name, execution, streams, afterDelivery, resumeFrom) ->
                        feeds.computeIfAbsent(name, ignored -> new StubFeed()));
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        url = "grpc://localhost:" + server.port();
        registry.register("stalled", "SELECT id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
        registry.register("healthy", "SELECT amount, id FROM txn", List.of(0), Principal.ANONYMOUS);
    }

    @AfterEach
    void stop() {
        server.close();
        registry.close();
    }

    @Test
    void aStoppedSourceIsMarkedAndExplainedAndAHealthyOneIsNot() {
        feeds.get("stalled").stopped =
                new PravahaException(new ErrorCode(5040, "FILESYSTEM_DECODE_FAILED"), "line 3: 'abc' is not an INT64");

        String out = plain(cli("queries", "--url", url));

        assertThat(out).contains("stalled\tRUNNING (source stopped)\t");
        assertThat(out).contains("healthy\tRUNNING\t");
        assertThat(out)
                .contains("stalled: source stopped with PRV-5040 reading txn#0 at 2026-09-19T08:00:00Z: ")
                .contains("line 3: 'abc' is not an INT64");
        assertThat(out).contains("is not retried").contains("https://docs.pravaha.io/errors/<code>");
        assertThat(out).doesNotContain("healthy: source stopped");
    }

    @Test
    void verboseAddsTheFeedOfEveryQuery() {
        String out = plain(cli("queries", "--verbose", "--url", url));

        assertThat(out).contains("NAME\tSTATE\tFINGERPRINT\tROWS IN\tFEED");
        assertThat(out).containsPattern("healthy\tRUNNING\t[0-9a-f]+\t0\tRUNNING");
        assertThat(out).doesNotContain("source stopped");
    }

    @Test
    void withoutVerboseTheHeaderIsTheOneTheDocumentationShows() {
        assertThat(plain(cli("queries", "--url", url))).contains("NAME\tSTATE\tFINGERPRINT\tROWS IN\n");
    }

    private static String cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            new PravahaCli(outStream, errStream).run(args);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String plain(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /** A feed reading txn#0, stopped once {@link #stopped} is set. */
    private static final class StubFeed implements SourceFeed {

        private volatile PravahaException stopped;

        @Override
        public FeedStatus status() {
            PravahaException failure = stopped;
            return FeedStatus.of(
                    describe(),
                    List.of(
                            failure == null
                                    ? new FeedStatus.Source("txn", 0, false, FeedStatus.SourceState.RUNNING, null)
                                    : new FeedStatus.Source(
                                            "txn",
                                            0,
                                            false,
                                            FeedStatus.SourceState.STOPPED,
                                            new FeedStatus.Stop(
                                                    failure, Instant.parse("2026-09-19T08:00:00Z"), true))));
        }

        @Override
        public String describe() {
            return "reading txn (1 partition)";
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

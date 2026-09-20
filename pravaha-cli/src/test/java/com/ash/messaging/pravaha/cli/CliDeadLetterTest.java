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
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetter;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention;
import com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue;
import com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterStore;
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.DeadLetterInfo;
import com.ash.messaging.pravaha.sdk.flight.DeadLetterPageInfo;
import com.ash.messaging.pravaha.sdk.flight.DeadLetterReplayInfo;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 over the wire: the Flight actions, the Java SDK on top of them, and {@code pravaha dlq}.
 *
 * <p>Three surfaces in one test on purpose. They are one stack -- the CLI calls the SDK, the SDK
 * speaks the control wire, the server answers from {@link com.ash.messaging.pravaha.registry.DeadLetters}
 * -- and a bug in the framing shows as a missing field two layers up. Testing them together is
 * what catches a field added at one end and read at the wrong index at the other, which is the
 * failure a hand-framed wire actually has.
 */
@Timeout(90)
class CliDeadLetterTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("amount", Types.int64())
            .build();

    private final Map<String, StubFeed> feeds = new ConcurrentHashMap<>();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private FileDeadLetterStore store;
    private String url;
    private Path directory;

    @BeforeEach
    void start(@TempDir Path dir) throws IOException {
        directory = dir;
        store = new FileDeadLetterStore(dir, DeadLetterRetention.unbounded());
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .feedingFrom((name, execution, streams, afterDelivery, resumeFrom) ->
                        feeds.computeIfAbsent(name, ignored -> new StubFeed()));
        server = new PravahaFlightServer(views)
                .hosting(registry)
                .withDeadLetters(store)
                .start("localhost", 0);
        url = "grpc://localhost:" + server.port();
        registry.register("big_txn", "SELECT id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
        write("big_txn", "corr-1", "line 1", "1,not-a-number");
        write("big_txn", "corr-2", "line 2", "2,also-not");
    }

    @AfterEach
    void stop() {
        server.close();
        registry.close();
    }

    @Test
    void theSdkReadsAPageNewestFirstWithTheQueuesTotals() {
        try (PravahaFlightClient client = connect()) {
            DeadLetterPageInfo page = client.deadLetters("big_txn", 0, 10);

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.configured()).isTrue();
            assertThat(page.retention()).isEqualTo("unbounded");
            assertThat(page.entries()).extracting(DeadLetterInfo::id).containsExactly("corr-2", "corr-1");
            DeadLetterInfo newest = page.entries().get(0);
            assertThat(newest.stream()).isEqualTo("txn");
            assertThat(newest.offset()).isEqualTo("line 2");
            assertThat(newest.code()).isEqualTo("PRV-5040");
            assertThat(newest.reason()).contains("cannot read");
            assertThat(newest.rejectedAt()).isPresent();
            assertThat(newest.isWithheld()).isFalse();
            assertThat(new String(newest.raw(), StandardCharsets.UTF_8)).isEqualTo("2,also-not");
            assertThat(newest.replay()).isEqualTo("NEW");
            assertThat(page.hasMore()).isFalse();
        }
    }

    @Test
    void theSdkFetchesOneWholeById() {
        try (PravahaFlightClient client = connect()) {
            DeadLetterInfo entry = client.deadLetter("big_txn", "corr-1");

            assertThat(entry.offset()).isEqualTo("line 1");
            assertThat(new String(entry.raw(), StandardCharsets.UTF_8)).isEqualTo("1,not-a-number");
            assertThat(entry.size()).isEqualTo(14);
        }
    }

    @Test
    void theSdkReplaysAndTheOutcomeSaysWhatItMeant() {
        try (PravahaFlightClient client = connect()) {
            DeadLetterReplayInfo one = client.replayDeadLetter("big_txn", "corr-1");

            // The stub feed accepts the record, which is what a corrected one does.
            assertThat(one.succeeded()).isTrue();
            assertThat(one.id()).isEqualTo("corr-1");
            assertThat(one.detail()).contains("at the frontier the query has reached");
            assertThat(feeds.get("big_txn").replayed).containsExactly("corr-1");
            assertThat(store.find("big_txn", "corr-1").orElseThrow().replay())
                    .isEqualTo(DeadLetterEntry.Replay.REPLAYED);
        }
    }

    @Test
    void theCliListsTheQueueAndSaysWhatRetentionIs() {
        String out = plain(cli("dlq", "list", "--name", "big_txn", "--url", url));

        assertThat(out).contains("ID\tWHEN\tCODE\tSTREAM\tOFFSET\tBYTES\tSTATE\tREASON");
        assertThat(out).contains("corr-2\t").contains("PRV-5040").contains("line 2");
        assertThat(out.indexOf("corr-2")).as("newest first").isLessThan(out.indexOf("corr-1"));
        assertThat(out).contains("2 dead letters").contains("retention unbounded");
    }

    @Test
    void theCliPrintsOneWholeWithItsBytesAsTheyArrived() {
        String out = plain(cli("dlq", "show", "--name", "big_txn", "--id", "corr-1", "--url", url));

        assertThat(out).contains("id       corr-1");
        assertThat(out).contains("offset   line 1");
        assertThat(out).contains("code     PRV-5040");
        // DOCX-21. No pravaha.docs.base-url here, so the line beside the code says where to look
        // it up rather than naming a host that does not resolve.
        assertThat(out)
                .doesNotContain("docs.pravaha.io")
                .contains("look PRV-5040 up in the console's help under Errors, or in docs/TROUBLESHOOTING.md");
        assertThat(out).contains("1,not-a-number");
    }

    @Test
    void aConfiguredHelpBasePutsThisDeploymentsUrlBesideTheCode() {
        com.ash.messaging.pravaha.api.HelpUrls.configure("https://help.example.test/errors");
        try {
            String out = plain(cli("dlq", "show", "--name", "big_txn", "--id", "corr-1", "--url", url));
            assertThat(out).contains("https://help.example.test/errors/PRV-5040");
        } finally {
            com.ash.messaging.pravaha.api.HelpUrls.configure(null);
        }
    }

    @Test
    void theCliReplaysAndSaysTheSemanticsWhereSomebodyWillReadThem() {
        String out = plain(cli("dlq", "replay", "--name", "big_txn", "--id", "corr-1,corr-2", "--url", url));

        assertThat(out).contains("replayed corr-1").contains("replayed corr-2");
        assertThat(out).contains("a new row at the query's current frontier, not a rewind");
        assertThat(feeds.get("big_txn").replayed).containsExactly("corr-1", "corr-2");
    }

    @Test
    void aRecordThatFailsAgainIsReportedAsSuchAndTheRunFails() {
        feeds.get("big_txn").failsAgain = true;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = run(out, err, "dlq", "replay", "--name", "big_txn", "--id", "corr-1", "--url", url);

        assertThat(exit)
                .as("a script replaying a corrected batch has to learn that some of it is still wrong")
                .isEqualTo(PravahaCli.EXIT_FAILED);
        assertThat(plain(err.toString(StandardCharsets.UTF_8))).contains("failed again corr-1");
        assertThat(store.find("big_txn", "corr-1").orElseThrow().replay())
                .isEqualTo(DeadLetterEntry.Replay.FAILED_AGAIN);
    }

    @Test
    void anEmptyQueueSaysSoAndAnUnconfiguredNodeSaysSomethingElse() {
        registry.register("quiet", "SELECT amount, id FROM txn", List.of(0), Principal.ANONYMOUS);

        assertThat(plain(cli("dlq", "list", "--name", "quiet", "--url", url))).contains("quiet has no dead letters");

        try (PravahaFlightServer bare =
                new PravahaFlightServer(new ViewCatalog()).hosting(registry).start("localhost", 0)) {
            String out = plain(cli("dlq", "list", "--name", "big_txn", "--url", "grpc://localhost:" + bare.port()));
            assertThat(out)
                    .as("no directory is not an empty queue, and the CLI does not let it read as one")
                    .contains("has no pravaha.dlq.directory")
                    .contains("That is not an empty queue");
        }
    }

    @Test
    void anUnknownSubcommandIsAUsageError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(run(out, err, "dlq", "purge", "--name", "big_txn")).isEqualTo(PravahaCli.EXIT_USAGE);
        assertThat(plain(err.toString(StandardCharsets.UTF_8))).contains("it is list, show or replay");
    }

    @Test
    void theHelpNamesTheSubcommandsAndTheSemantics() {
        String usage = plain(cli("help"));

        assertThat(usage).contains("dlq list").contains("dlq show").contains("dlq replay");
        assertThat(usage).contains("not a rewind");
    }

    private void write(String query, String id, String offset, String record) throws IOException {
        try (FileDeadLetterQueue queue =
                new FileDeadLetterQueue(DeadLetterFiles.letters(directory, query), DeadLetterRetention.unbounded())) {
            queue.accept(new DeadLetter(
                    query,
                    "column 'amount' (INT64): cannot read it as a number",
                    "PRV-5040",
                    "txn",
                    "txn/1(id INT64,amount INT64)",
                    offset,
                    record.getBytes(StandardCharsets.UTF_8),
                    id,
                    1L,
                    1_700_000_000_000L));
        }
    }

    private PravahaFlightClient connect() {
        return PravahaFlightClient.connect(ClientOptions.builder(url).build());
    }

    private static String cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        run(out, new ByteArrayOutputStream(), args);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int run(ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            return new PravahaCli(outStream, errStream).run(args);
        }
    }

    /** Without the colour, so an assertion is about the words. */
    private static String plain(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /**
     * A feed that records what was replayed into it and can be told to reject it again.
     *
     * <p>Standing in for a real source's decoder, because what is under test here is the wire and
     * the three surfaces on it: whether a record decodes is {@code DeadLetterSurfacesTest}'s
     * question, against a real filesystem source.
     */
    private static final class StubFeed implements SourceFeed {

        private final List<String> replayed = new java.util.ArrayList<>();
        private boolean failsAgain;

        @Override
        public DeadLetterEntry.Replay replay(String stream, byte[] raw, String sourceOffset, String schema, String id) {
            replayed.add(id);
            return failsAgain ? DeadLetterEntry.Replay.FAILED_AGAIN : DeadLetterEntry.Replay.REPLAYED;
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
        public String describe() {
            return "reading txn (1 partition)";
        }

        @Override
        public FeedStatus status() {
            return FeedStatus.of(describe(), List.of());
        }

        @Override
        public void close() {}
    }
}

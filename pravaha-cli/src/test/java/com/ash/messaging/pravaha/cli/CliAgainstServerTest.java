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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI against a real server.
 *
 * <p>The commands documented in {@code docs/QUICKSTART.md} and the case studies are run here, so a
 * command that stops working fails the build rather than the first person who copies it out of a
 * README. The CLI goes through the published SDK, which makes this an acceptance test of the client
 * API as much as of the CLI.
 *
 * <p>It lives in this module rather than in {@code pravaha-it} because it needs no Docker and no
 * external anything -- the server runs in-process. Keeping it here means the module that owns these
 * commands owns their proof, and that the coverage gate sees it.
 */
@Timeout(120)
class CliAgainstServerTest {

    private static final String SQL = "SELECT trade_id, product_type, trade_json FROM trade";

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .field("trade_json", Types.string())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private PravahaFlightServer server;
    private RowArena arena;
    private String url;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE)
                // A source with nothing in it, so the replacement commands have something to drive:
                // a backfill over an empty stream is caught up at its first poll. Rows still arrive
                // by being pushed, exactly as the rest of this class feeds them.
                .feedingFrom(new QuietSource());
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        url = "grpc://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        registry.close();
        arena.close();
    }

    private CliResult cli(String... args) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code;
        try (java.io.PrintStream outStream =
                        new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);
                java.io.PrintStream errStream =
                        new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8)) {
            code = new PravahaCli(outStream, errStream).run(args);
        }
        return new CliResult(
                code,
                out.toString(java.nio.charset.StandardCharsets.UTF_8),
                err.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record CliResult(int code, String out, String err) {
        CliResult {
            System.out.println("CLI[" + code + "] out=" + out.replace('\n', '|') + " err=" + err.replace('\n', '|'));
        }
    }

    /** A source with no records at all: enough for a replacement, nothing for the data. */
    private static final class QuietSource implements com.ash.messaging.pravaha.registry.SourceFeedFactory {
        @Override
        public com.ash.messaging.pravaha.registry.SourceFeed open(
                String queryName,
                com.ash.messaging.pravaha.runtime.exec.QueryExecution execution,
                java.util.List<String> sourceStreams,
                Runnable afterDelivery,
                java.util.Map<String, String> resumeFrom) {
            return com.ash.messaging.pravaha.registry.SourceFeed.NONE;
        }

        @Override
        public com.ash.messaging.pravaha.registry.SourceFeed openBackfill(
                String queryName,
                com.ash.messaging.pravaha.runtime.exec.QueryExecution execution,
                java.util.List<String> sourceStreams,
                Runnable afterDelivery,
                java.util.Map<String, String> resumeFrom,
                com.ash.messaging.pravaha.backfill.BackfillPlan plan) {
            com.ash.messaging.pravaha.backfill.OffsetSplicedReader reader =
                    new com.ash.messaging.pravaha.backfill.OffsetSplicedReader(
                            at -> new Empty(),
                            com.ash.messaging.pravaha.api.plugin.SourceOffset.BEGINNING,
                            null,
                            plan.job(),
                            false);
            // Until the empty history is behind it: one poll is usually enough, and a throttled
            // backfill has no budget in the instant it is created.
            for (int attempt = 0;
                    attempt < 400 && reader.phase() != com.ash.messaging.pravaha.backfill.BackfillPhase.LIVE;
                    attempt++) {
                reader.poll(
                        () -> {
                            throw new IllegalStateException("an empty partition writes no rows");
                        },
                        64);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return com.ash.messaging.pravaha.registry.SourceFeed.NONE;
        }

        @Override
        public java.util.Optional<String> backfillRefusal(String stream) {
            return java.util.Optional.empty();
        }
    }

    private static final class Empty implements com.ash.messaging.pravaha.api.plugin.PartitionReader {
        @Override
        public int poll(RecordSink sink, int maxRecords) {
            return 0;
        }

        @Override
        public com.ash.messaging.pravaha.api.plugin.SourceOffset position() {
            return com.ash.messaging.pravaha.api.plugin.SourceOffset.BEGINNING;
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    private void feed(String tradeId, String product) {
        feed(tradeId, product, 1L);
    }

    private void feed(String tradeId, String product, long weight) {
        var query = registry.require("trade_feed");
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tradeId);
        writer.setString(1, product);
        writer.setString(2, "{}");
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        // Rows are applied on the lane's thread, so the commit waits for this one to land.
        query.awaitApplied(java.time.Duration.ofSeconds(10));
        query.commit();
    }

    @Test
    void registerListAndQuery() {
        CliResult registered = cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");
        System.out.println(
                "CLIOUT[" + registered.out() + "] CLIERR[" + registered.err() + "] registry=" + registry.names());
        assertThat(registered.code()).as(registered.err()).isZero();
        assertThat(registered.out()).contains("registered", "trade_feed");

        CliResult listed = cli("queries", "--url", url);
        assertThat(listed.code()).isZero();
        assertThat(listed.out()).contains("trade_feed").contains("RUNNING");

        feed("T-1", "SWAP");
        feed("T-2", "EQUITY");

        CliResult queried = cli("query", "--url", url, "--sql", "SELECT trade_id FROM trade_feed");
        assertThat(queried.code()).as(queried.err()).isZero();
        assertThat(queried.out()).contains("T-1").contains("T-2").contains("2 rows");
    }

    @Test
    void retainSetsTheViewsRetentionAndAnUnreadableOneIsRefused() {
        CliResult registered =
                cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0", "--retain", "PT6H");
        assertThat(registered.code()).as(registered.err()).isZero();
        assertThat(registered.out()).contains("retain=PT6H");
        assertThat(registry.require("trade_feed").view().retention().maxAge()).isEqualTo(java.time.Duration.ofHours(6));

        CliResult refused =
                cli("register", "--url", url, "--name", "other", "--sql", SQL, "--keys", "0", "--retain", "six hours");
        assertThat(refused.code()).isNotZero();
        assertThat(refused.err()).contains("is not a retention");
        assertThat(registry.names()).doesNotContain("other");
    }

    @Test
    void aParameterisedQueryBindsFromTheCommandLine() {
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");
        feed("T-1", "SWAP");
        feed("T-2", "EQUITY");

        CliResult queried = cli(
                "query",
                "--url",
                url,
                "--sql",
                "SELECT trade_id FROM trade_feed WHERE product_type = ?",
                "--params",
                "SWAP");

        assertThat(queried.code()).as(queried.err()).isZero();
        assertThat(queried.out()).contains("T-1").doesNotContain("T-2");
    }

    @Test
    void aPlaceholderInARegistrationIsRefusedBecauseNothingOnTheWireBindsIt() {
        // HLP-12. CONTINUOUS_QUERIES §9 documented `pravaha register --param`, which never existed:
        // the Flight register action carries no values, so only an embedded QueryRegistry binds
        // them. What a `?` registered over the wire meets is a refusal, and this pins which one.
        CliResult refused = cli(
                "register", "--url", url, "--name", "swaps", "--sql", SQL + " WHERE product_type = ?", "--keys", "0");

        assertThat(refused.code()).isEqualTo(PravahaCli.EXIT_FAILED);
        assertThat(refused.err()).contains("PRV-2060");
        assertThat(registry.names()).doesNotContain("swaps");
    }

    @Test
    void lifecycleCommandsWork() {
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");

        CliResult paused = cli("pause", "--url", url, "--name", "trade_feed");
        assertThat(paused.code()).isZero();
        // HLP-10: these printed "pauseped" and "resumeped".
        assertThat(paused.out()).contains("paused trade_feed").doesNotContain("pauseped");
        assertThat(cli("queries", "--url", url).out()).contains("PAUSED");
        CliResult resumed = cli("resume", "--url", url, "--name", "trade_feed");
        assertThat(resumed.code()).isZero();
        assertThat(resumed.out()).contains("resumed trade_feed").doesNotContain("resumeped");
        assertThat(cli("queries", "--url", url).out()).contains("RUNNING");
        CliResult dropped = cli("drop", "--url", url, "--name", "trade_feed");
        assertThat(dropped.code()).isZero();
        assertThat(dropped.out()).contains("dropped trade_feed");
        assertThat(cli("queries", "--url", url).out()).contains("no continuous queries");
    }

    @Test
    void subscribeStreamsUntilItsLimitAndReleasesTheServersSide() throws Exception {
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");

        Thread feeder = Thread.ofVirtual().start(() -> {
            try {
                // The subscription has to attach before anything is committed: it starts from now,
                // not from the beginning of time.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline
                        && registry.require("trade_feed").subscriberCount() == 0) {
                    Thread.sleep(20);
                }
                feed("T-1", "SWAP");
                feed("T-2", "SWAP");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CliResult streamed = cli("subscribe", "--url", url, "--view", "trade_feed", "--limit", "2");
        feeder.join(5_000);

        // The client going away must release the *server's* side of it: the attached listener on
        // the query and the Arrow buffers that subscription was holding. The server learns from the
        // cancellation and unwinds on its next poll, so this waits for the fact rather than assuming
        // it -- and the allocator check at teardown is what makes the assumption expensive.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline
                && registry.find("trade_feed").map(q -> q.subscriberCount()).orElse(0) > 0) {
            Thread.sleep(20);
        }
        assertThat(registry.require("trade_feed").subscriberCount())
                .as("closing the client must detach the subscription on the server")
                .isZero();

        assertThat(streamed.code()).as(streamed.err()).isZero();
        assertThat(streamed.out()).contains("T-1", "T-2");
        // A batch is a commit, and the output says so rather than leaving it to be inferred.
        assertThat(streamed.out()).contains("commit");
    }

    @Test
    void subscribePrintsEachChangesWeight() throws Exception {
        // HLP-11. A retraction and the insert it withdraws carry identical columns; printed without
        // the weight they are the same line, and whoever reads the stream sees a row arrive twice.
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");

        Thread feeder = Thread.ofVirtual().start(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline
                        && registry.require("trade_feed").subscriberCount() == 0) {
                    Thread.sleep(20);
                }
                feed("T-1", "SWAP");
                feed("T-1", "SWAP", -1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CliResult streamed = cli("subscribe", "--url", url, "--view", "trade_feed", "--limit", "2");
        feeder.join(5_000);

        assertThat(streamed.code()).as(streamed.err()).isZero();
        String plain = streamed.out().replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(plain).contains("WEIGHT\ttrade_id\tproduct_type\ttrade_json");
        assertThat(plain).contains("+1\tT-1\tSWAP\t{}");
        assertThat(plain).contains("-1\tT-1\tSWAP\t{}");
        assertThat(plain.indexOf("+1\tT-1")).isLessThan(plain.indexOf("-1\tT-1"));
    }

    @Test
    void subscribeWithSnapshotPrintsTheViewFirstThenTheCommitsAfterIt() throws Exception {
        // SUB-1. A row committed before the subscriber attached reaches a plain subscription by no
        // path; --snapshot prints it, marked as the snapshot, and then the commit that follows.
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");
        feed("T-1", "SWAP");

        Thread feeder = Thread.ofVirtual().start(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline
                        && registry.require("trade_feed").subscriberCount() == 0) {
                    Thread.sleep(20);
                }
                feed("T-2", "SWAP");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CliResult streamed = cli("subscribe", "--url", url, "--view", "trade_feed", "--snapshot", "--limit", "2");
        feeder.join(5_000);

        assertThat(streamed.code()).as(streamed.err()).isZero();
        String plain = streamed.out().replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(plain).contains("+1\tT-1\tSWAP", "-- snapshot at frontier", "+1\tT-2\tSWAP", "-- commit, 1 row");
        assertThat(plain.indexOf("T-1"))
                .isLessThan(plain.indexOf("-- snapshot"))
                .isLessThan(plain.indexOf("T-2"));
    }

    @Test
    void aFailureKeepsTheServersOwnDiagnosis() {
        CliResult failed = cli("query", "--url", url, "--sql", "SELECT * FROM nowhere");

        assertThat(failed.code()).isEqualTo(1);
        // "PRV-4023 ... this server serves []" is actionable; "query failed" is not.
        assertThat(failed.err()).contains("PRV-");
    }

    @Test
    void theUsageTextListsTheServerCommands() {
        CliResult help = cli("--help");

        assertThat(help.out())
                .contains("query", "register", "queries", "subscribe", "drop", "replace", "cutover", "rollback");
    }

    @Test
    void aQueryIsReplacedWatchedCutOverAndRolledBackFromTheCommandLine(@TempDir java.nio.file.Path directory)
            throws Exception {
        java.nio.file.Path sql = directory.resolve("v2.sql");
        java.nio.file.Files.writeString(sql, SQL + " WHERE product_type <> 'NONE'");
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");

        CliResult started = cli(
                "replace",
                "--url",
                url,
                "--name",
                "trade_feed",
                "--sql-file",
                sql.toString(),
                "--keys",
                "0",
                "--rate-limit",
                "500",
                "--wait");
        assertThat(started.code()).as(started.err()).isZero();
        String plain = plain(started.out());
        assertThat(plain).contains("caught up trade_feed").contains("history 0 rows");
        assertThat(plain)
                .as("the name has not moved yet, and the output says so")
                .contains("still answers");

        assertThat(plain(cli("replacements", "--url", url).out()))
                .contains("NAME\tSTATE")
                .contains("trade_feed");

        assertThat(plain(cli("throttle", "--url", url, "--name", "trade_feed", "--rate", "50")
                        .out()))
                .contains("limit 50");
        assertThat(plain(cli("pause-backfill", "--url", url, "--name", "trade_feed")
                        .out()))
                .contains("paused");
        cli("resume-backfill", "--url", url, "--name", "trade_feed");

        assertThat(plain(cli("cutover", "--url", url, "--name", "trade_feed").out()))
                .contains("cut over trade_feed")
                .contains("retained until");
        assertThat(registry.require("trade_feed").sql()).contains("WHERE");

        assertThat(plain(cli("rollback", "--url", url, "--name", "trade_feed").out()))
                .contains("rolled back trade_feed");
        assertThat(registry.require("trade_feed").sql()).isEqualTo(SQL);
    }

    @Test
    void aCutoverOfSomethingNobodyIsReplacingKeepsTheServersOwnRefusal() {
        cli("register", "--url", url, "--name", "trade_feed", "--sql", SQL, "--keys", "0");
        CliResult refused = cli("cutover", "--url", url, "--name", "trade_feed");

        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("PRV-4016");
    }

    private static String plain(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}

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
package com.ash.messaging.pravaha.cli.qa.errc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERRC-097 .. ERRC-103 -- PRV-8xxx, the query registry, which {@code ErrorCode.category()} has no
 * entry for (E5: since commit e0b6395, {@code category()} no longer throws for 8xxx -- see
 * {@code docs/qa/logs/ERRC.md} for the reconfirmation).
 *
 * <p>Surface: the CLI against a real, in-process {@link PravahaFlightServer}/{@link QueryRegistry}
 * (ERRC-097..100), and {@link QueryRegistry}/{@link RegistryJournal} directly for the journal cases
 * (ERRC-101..103), which are startup/recovery concerns with no Flight surface at all.
 */
@Timeout(120)
class ErrcRegistryTest extends ErrcServerSupport {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("usr", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            // Declared, not merely present: without it no watermark advances over this stream and
            // no window a query opens over it can ever close (TIME-6), so a fixture whose cases
            // window would be describing the refusals of a node nobody should be running.
            .eventTime("event_time")
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private PravahaFlightServer server;
    private RowArena arena;
    private String url;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
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

    private void feed(String name, long id, String usr, long amount, long weight) {
        var query = registry.require(name);
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id);
        writer.setString(1, usr);
        writer.setLong(2, amount);
        writer.setLong(3, id);
        writer.weight(weight).eventTimestampNanos(id).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }

    // ------------------------------------------------------------ ERRC-097 -- PRV-8001

    @Test
    void reRegisteringANameUnderDifferentSqlIsRefusedButIdenticalSqlIsIdempotent() {
        String sql = "SELECT usr, amount FROM txn";
        ErrcServerSupport.CliResult first = cli("register", "--url", url, "--name", "v1", "--sql", sql, "--keys", "0");
        assertThat(first.code()).as(first.err()).isZero();

        // Same name, identical SQL: the case's own "interesting" question -- registrations sharing a
        // fingerprint share one computation, so this may be idempotent rather than a conflict.
        ErrcServerSupport.CliResult sameSql =
                cli("register", "--url", url, "--name", "v1", "--sql", sql, "--keys", "0");
        // Whichever it is, the original query must be unharmed -- the vacuity check.
        assertThat(registry.names()).contains("v1");
        assertThat(registry.require("v1").state().toString()).isEqualTo("RUNNING");

        // Same name, different SQL: unambiguously a conflict.
        ErrcServerSupport.CliResult differentSql =
                cli("register", "--url", url, "--name", "v1", "--sql", "SELECT usr FROM txn", "--keys", "0");
        assertThat(differentSql.code()).isEqualTo(1);
        // INVERTED for S-4. This read `.contains("PRV-1041").contains("PRV-8001")` -- it recorded the
        // double-stamping as the expected output: the SDK wrapped every server refusal in its own
        // PRV-1041 CLIENT_QUERY_REFUSED and the real code survived only inside the text, so an
        // operator read "PRV-1041  PRV-8001  ..." and had to know which of the two to look up. The
        // SDK now rebuilds the server's own ErrorCode from the wire, so PRV-8001 arrives as the
        // code, once, and PRV-1041 means only what its name says.
        assertThat(differentSql.err()).contains("PRV-8001").doesNotContain("PRV-1041");
        // E3: names the existing query.
        assertThat(differentSql.err()).contains("v1");

        // Recorded, not asserted either way in the identical-SQL case's own exit code, since the case
        // only requires the original to survive: record what actually happened.
        System.out.println("ERRC-097 same-SQL re-registration: exit=" + sameSql.code() + " out=" + sameSql.out()
                + " err=" + sameSql.err());
    }

    // ------------------------------------------------------------ ERRC-098 -- PRV-8002

    @Test
    void operationsOnAnUnknownNameAreRefusedWithoutListingWhatDoesExist() {
        cli("register", "--url", url, "--name", "v1", "--sql", "SELECT usr, amount FROM txn", "--keys", "0");

        for (String verb : List.of("drop", "pause", "resume")) {
            ErrcServerSupport.CliResult r = cli(verb, "--url", url, "--name", "nosuch");
            assertThat(r.code()).as(verb).isEqualTo(1);
            // INVERTED for S-4, same reason as the PRV-8001 assertion above: the server's own code
            // now reaches the caller as the code rather than as a substring of a PRV-1041 message.
            assertThat(r.err()).as(verb).contains("PRV-8002").doesNotContain("PRV-1041");
            // The case's E3 expectation -- "lists what does exist", as PRV-4023 does for views --
            // is withdrawn, and deliberately: STRM-9 reproduced one principal, denied read on every
            // view, learning the node's whole catalogue by misspelling a single name. QueryRegistry
            // is below the policy and holds no principal, so it cannot decide which names a caller
            // may be told about, and a refusal that enumerates unconditionally is the wrong default
            // for the one that cannot ask.
            //
            // The actionable content is not gone, it moved to where it can be authorized: `pravaha
            // queries` and the Flight LIST action both go through policy.mayRead.
            assertThat(r.err()).as(verb).doesNotContain("v1");
        }
    }

    // ------------------------------------------------------------ ERRC-099 -- PRV-8003

    @Test
    void sameStateReRequestsAreSilentlyAcceptedAndOnlyTerminalStateTransitionsAreIllegal() {
        // Correction to the case, found by reading RegisteredQuery.pause()/resume() rather than
        // assuming: `resume()`'s only guard is `state().isTerminal()` (FAILED or DROPPED), and
        // `pause()`'s `requireLive` is the same guard. Neither checks "already in the state being
        // requested." So the case's own two named "illegal transitions" -- resume a RUNNING query,
        // pause a PAUSED one -- are NOT refused: they are silent no-ops. Confirmed both ways below,
        // then the transitions that genuinely are illegal (anything on a FAILED or DROPPED query).
        cli("register", "--url", url, "--name", "v1", "--sql", "SELECT usr, amount FROM txn", "--keys", "0");

        ErrcServerSupport.CliResult resumeRunning = cli("resume", "--url", url, "--name", "v1");
        assertThat(resumeRunning.code())
                .as("finding: resume on RUNNING is accepted, not PRV-8003")
                .isZero();
        assertThat(cli("queries", "--url", url).out()).contains("RUNNING");

        assertThat(cli("pause", "--url", url, "--name", "v1").code()).isZero();
        ErrcServerSupport.CliResult pausePaused = cli("pause", "--url", url, "--name", "v1");
        assertThat(pausePaused.code())
                .as("finding: pause on PAUSED is accepted, not PRV-8003")
                .isZero();
        assertThat(cli("resume", "--url", url, "--name", "v1").code()).isZero();

        // The genuinely illegal transitions: pause/resume a DROPPED query.
        assertThat(cli("drop", "--url", url, "--name", "v1").code()).isZero();
        ErrcServerSupport.CliResult pauseDropped = cli("pause", "--url", url, "--name", "v1");
        // Dropped removes the name entirely, so this is PRV-8002 (no such query), not PRV-8003 --
        // there is no query left to be in an illegal state. Recorded, not asserted as a defect: the
        // case's own "anything on a dropped one" candidate is real, just a different code than 8003.
        assertThat(pauseDropped.code()).isEqualTo(1);
        assertThat(pauseDropped.err()).contains("PRV-8002");

        // A query genuinely in a terminal state (FAILED) IS refused with PRV-8003 -- confirmed
        // separately and precisely in the QUERY_FAILED test below, which drives a query to FAILED
        // first; not duplicated here since constructing a failure needs row-level access this CLI
        // round-trip does not have.
    }

    // ------------------------------------------------------------ ERRC-100 -- PRV-8004

    @Test
    void subscribingToAnAlreadyFailedQueryIsPrv8003NotPrv8004AndAPlainReadDoesNotEvenNotice() {
        // Drives a windowed MIN to FAILED with a retraction it cannot invert -- the same technique
        // LifecycleTestSupport uses for LIFE-126, reached here through the registry directly (no CLI
        // verb pushes rows; the CLI's own --params path only binds SELECT parameters).
        registry.register(
                "v1",
                "SELECT usr, MIN(amount) AS lo FROM txn GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), usr",
                List.of(0),
                Principal.ANONYMOUS);
        feed("v1", 1, "ann", 100, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> feed("v1", 1, "ann", 100, -1))
                .hasMessageContaining("MIN cannot handle a retraction");

        // A plain SELECT goes through ViewQuery, which never consults RegisteredQuery.failure() --
        // it just reads whatever the view last held, exactly LIFE's "a failed query keeps answering"
        // finding (ViewQuery has no reference to QUERY_FAILED at all, confirmed by grep).
        ErrcServerSupport.CliResult read = cli("query", "--url", url, "--sql", "SELECT lo FROM v1");
        assertThat(read.code())
                .as("a plain read of a failed query's view is not itself refused")
                .isZero();

        // Major correction to the case, found by tracing every one of PRV-8004's four throw sites
        // (grep, exhaustive): Subscription.java:103,134 are NOT "the underlying query's lane failed,
        // read/subscribe it afterward" -- they fire for SUBSCRIBER-side failures (the consumer
        // callback itself throwing, or a subscriber falling behind with the FAIL overflow policy).
        // RegisteredQuery.java:189 is inside RegisteredQuery.failure()'s own body, and that getter
        // has ZERO callers anywhere in main sources (grepMainSourcesFor confirms) -- dead code.
        // RegisteredQuery.java:238 fires synchronously to whoever calls accept(), and only for an
        // unexpected *non*-PravahaException during row processing (a genuine engine bug), not for a
        // later reader. So subscribing to an *already*-failed query -- the case's own Setup -- does
        // NOT reach PRV-8004 at all: it is refused earlier, by RegisteredQuery's own state guard, as
        // PRV-8003 ILLEGAL_TRANSITION ("cannot subscribe to 'v1': it is FAILED"). Confirmed below.
        ErrcServerSupport.CliResult subscribed = cli("subscribe", "--url", url, "--view", "v1", "--limit", "1");
        assertThat(subscribed.code()).isEqualTo(1);
        assertThat(subscribed.err())
                .as("finding: subscribing to an already-failed query is PRV-8003, not PRV-8004")
                .contains("PRV-8003")
                .doesNotContain("PRV-8004");

        // And now a query genuinely in a terminal state (FAILED) IS refused pause/resume with
        // PRV-8003, completing ERRC-099's terminal-state half.
        ErrcServerSupport.CliResult pauseFailed = cli("pause", "--url", url, "--name", "v1");
        assertThat(pauseFailed.code()).isEqualTo(1);
        assertThat(pauseFailed.err()).contains("PRV-8003");
        ErrcServerSupport.CliResult resumeFailed = cli("resume", "--url", url, "--name", "v1");
        assertThat(resumeFailed.code()).isEqualTo(1);
        assertThat(resumeFailed.err()).contains("PRV-8003");
    }

    // ------------------------------------------------------------ ERRC-101 -- PRV-8005, ERRC-102 -- PRV-8006

    @Test
    void aCorruptJournalRefusesReplayButATruncatedTailIsKeptNotSkipped(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog v = new ViewCatalog();
        try (QueryRegistry reg = new QueryRegistry(v, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            RegistryJournal journal = new RegistryJournal(journalFile);
            reg.journalTo(journal);
            reg.register("v1", "SELECT usr, amount FROM txn", List.of(0), Principal.ANONYMOUS);
            reg.register("v2", "SELECT usr FROM txn", List.of(0), Principal.ANONYMOUS);
        }

        // Clean replay first (the vacuity control): both names come back.
        byte[] clean = Files.readAllBytes(journalFile);
        assertCleanReplayRecovers(dir, clean, "clean-control.journal", "v1", "v2");

        // Surgical corruption, not a blind "middle of the file" flip: the journal's own format is
        // [4-byte record length][ControlWire-framed payload], and ControlWire's payload begins with
        // a 4-byte magic number. A byte flipped inside record 2's magic number reliably breaks
        // ControlWire.decode's own check (a RuntimeException replay() explicitly catches and wraps as
        // PRV-8005) -- a blind middle-of-file flip risks landing in a text/count field that a
        // different, uncaught path (e.g. decodeRetention's Long.parseLong) turns into a raw
        // NumberFormatException instead, which is itself worth recording (see FINDINGS) but is not
        // what this assertion is about.
        java.nio.ByteBuffer probe = java.nio.ByteBuffer.wrap(clean);
        int record1PayloadLength = probe.getInt();
        int record2MagicOffset = 4 + record1PayloadLength + 4;
        byte[] corrupted = clean.clone();
        corrupted[record2MagicOffset] ^= 0x7F;
        Path corruptFile = dir.resolve("corrupt.journal");
        Files.write(corruptFile, corrupted);
        ViewCatalog v2 = new ViewCatalog();
        try (QueryRegistry reg2 = new QueryRegistry(v2, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            RegistryJournal corruptJournal = new RegistryJournal(corruptFile);
            reg2.journalTo(corruptJournal);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reg2.recover(id -> Optional.of(Principal.ANONYMOUS)))
                    .as("mid-file corruption is refused, PRV-8005")
                    .hasMessageContaining("PRV-8005");
        }

        // Truncated final record: must NOT fail -- replay keeps everything before it.
        byte[] truncated = new byte[clean.length - 3];
        System.arraycopy(clean, 0, truncated, 0, truncated.length);
        Path truncatedFile = dir.resolve("truncated.journal");
        Files.write(truncatedFile, truncated);
        ViewCatalog v3 = new ViewCatalog();
        try (QueryRegistry reg3 = new QueryRegistry(v3, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            RegistryJournal truncatedJournal = new RegistryJournal(truncatedFile);
            reg3.journalTo(truncatedJournal);
            QueryRegistry.Recovery recovery = reg3.recover(id -> Optional.of(Principal.ANONYMOUS));
            assertThat(recovery.refused())
                    .as("a truncated tail is not a refusal -- the failure this test exists to catch")
                    .isEmpty();
        }
    }

    private void assertCleanReplayRecovers(Path dir, byte[] bytes, String fileName, String... expectedNames)
            throws Exception {
        Path file = dir.resolve(fileName);
        Files.write(file, bytes);
        ViewCatalog v = new ViewCatalog();
        try (QueryRegistry reg = new QueryRegistry(v, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            reg.journalTo(new RegistryJournal(file));
            QueryRegistry.Recovery recovery = reg.recover(id -> Optional.of(Principal.ANONYMOUS));
            assertThat(recovery.recovered()).contains(expectedNames);
            assertThat(recovery.refused()).isEmpty();
        }
    }

    @Test
    void anUnwritableJournalRefusesTheRegistrationRatherThanAcknowledgingIt(@TempDir Path dir) throws Exception {
        // `chmod` alone cannot simulate CFG-100 here: RegistryJournal.append calls
        // SensitiveFiles.createOwnerOnly on every single append, which unconditionally resets the
        // file to rw------- (and its parent directory to rwx------) *before* opening it for writing
        // -- so a file chmod'd read-only beforehand is silently re-opened for write regardless.
        // Confirmed by trying it: no exception was raised. The genuinely deterministic, permission-
        // free way to force the same I/O failure append() itself catches is to make the journal
        // *path* a directory rather than a regular file -- FileChannel.open(..., WRITE, ...) on a
        // directory throws IOException ("Is a directory") the same way a permission failure would,
        // exercising the identical catch block and the identical PRV-8006 throw.
        Path journalFile = dir.resolve("readonly.journal");
        Files.createDirectory(journalFile);
        ViewCatalog v = new ViewCatalog();
        try (QueryRegistry reg = new QueryRegistry(v, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            reg.journalTo(new RegistryJournal(journalFile));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reg.register("v1", "SELECT usr FROM txn", List.of(0), Principal.ANONYMOUS))
                    .hasMessageContaining("PRV-8006");
            // Vacuity: the registration must actually be refused, not merely throw and also land.
            assertThat(reg.names())
                    .as("a refused registration must not be acknowledged")
                    .doesNotContain("v1");
        }
    }

    // ------------------------------------------------------------ ERRC-103 -- PRV-8007

    @Test
    void aRefusedRecoveryCarriesPrv8007(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("orphaned.journal");
        ViewCatalog v = new ViewCatalog();
        try (QueryRegistry reg = new QueryRegistry(v, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            reg.journalTo(new RegistryJournal(journalFile));
            reg.register("v1", "SELECT usr FROM txn", List.of(0), Principal.of("ann"));
        }

        // "ann" no longer resolves -- the genuine condition the case describes.
        ViewCatalog v2 = new ViewCatalog();
        try (QueryRegistry reg2 = new QueryRegistry(v2, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            reg2.journalTo(new RegistryJournal(journalFile));
            QueryRegistry.Recovery recovery = reg2.recover(id -> Optional.empty());
            assertThat(recovery.recovered()).isEmpty();
            assertThat(recovery.refused()).hasSize(1);
            // Recovery.refused() is now a List<Recovery.Refusal>, structured rather than plain text,
            // and this is the throw site: an unauthorized replay carries its code where a caller --
            // an operator's log line, or this assertion -- can see it, rather than only in prose.
            assertThat(recovery.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
            assertThat(recovery.refused().get(0).reason()).contains("not a principal this deployment knows");
            // The plain-text form recovery has always logged is still there for a reader who only
            // wants the sentence, unchanged in wording.
            assertThat(recovery.refused().get(0).toString()).contains("not a principal this deployment knows");
        }

        // Vacuity: the same query recovers cleanly when "ann" still resolves.
        ViewCatalog v3 = new ViewCatalog();
        try (QueryRegistry reg3 = new QueryRegistry(v3, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            reg3.journalTo(new RegistryJournal(journalFile));
            QueryRegistry.Recovery recovery = reg3.recover(id -> Optional.of(Principal.of("ann")));
            assertThat(recovery.recovered()).containsExactly("v1");
            assertThat(recovery.refused()).isEmpty();
        }

        // grep confirmation, exhaustive: REPLAY_UNAUTHORIZED now has a real throw site in
        // QueryRegistry.recover(), in addition to its own declaration.
        assertThat(grepMainSourcesFor("REPLAY_UNAUTHORIZED")).isGreaterThan(1);
    }

    private static long grepMainSourcesFor(String literal) throws Exception {
        // Nested checkouts are excluded relative to the root actually found, not by matching
        // "/.claude/" as a substring. Both halves matter and each was got wrong once: from a main
        // checkout the agents' worktrees under .claude/ are copies of this repository and inflate
        // every count by one per running agent; from inside one of those worktrees the root's own
        // path contains "/.claude/", so a substring test discards the whole tree and the check
        // passes on nothing.
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/adr"))) {
            root = root.getParent();
        }
        Path finalRoot = root;
        Path nested = finalRoot.resolve(".claude");
        try (var files = Files.walk(finalRoot)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.startsWith(nested))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(literal);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
        }
    }
}

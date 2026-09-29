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
package com.ash.messaging.pravaha.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.api.DeadLetterController;
import com.ash.messaging.pravaha.server.api.DeadLetterDtos;
import com.ash.messaging.pravaha.server.api.RegistryAccess;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5 on a running node: an undecodable record reaches every surface, and can be put back.
 *
 * <p>Design 23.18's fourth journey, end to end against a real node with a real
 * {@code pravaha.dlq.directory}: a followed CSV gains a line the decoder cannot read, the source
 * keeps reading, and the record is then listed, fetched whole, counted, measured and replayed --
 * with the row appearing in the view -- through the surfaces an operator actually has.
 *
 * <p>The authorization tests build their own registry with their own policy, because the rules are
 * read from the registry the query is in: they are the view's rules and not the endpoint's. The
 * one that matters most is the row filter, because the bytes of a dead letter are a row of the
 * source and a record that failed to decode has no row for a filter to be evaluated against.
 */
class DeadLetterSurfacesTest {

    private static final String SCHEMA_SPEC = "id:INT64,user_id:STRING,amount:INT64";

    private static final String SQL = "SELECT id, amount FROM txn";

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private static final Principal SLICED = new Principal("bob", "public", Set.of("sliced"), Map.of());

    /** Allows everything, except that a "sliced" principal reads every view through a row filter. */
    private static final SecurityPolicy ROW_FILTERED = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return principal.hasRole("sliced")
                    ? AccessDecision.allowWithRowFilter("amount > 0")
                    : AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return AccessDecision.allow();
        }
    };

    private PravahaNode node;
    private SimpleMeterRegistry meters;
    private PravahaMetrics metrics;
    private Path txn;
    private Path dlqDirectory;
    private RecordingAudit audit;

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        txn = dir.resolve("txn.csv");
        Files.writeString(txn, "1,ann,100\n");
        dlqDirectory = dir.resolve("dlq");

        StreamCatalog catalog = new StreamCatalog();
        catalog.register(schema("txn"));
        SourceBindingProperties sources = new SourceBindingProperties();
        sources.getSources().put("txn", followed(txn));

        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        persistence.getDlq().setDirectory(dlqDirectory.toString());
        node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(sources)
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("dlq-node")
                .build();
        node.start();
        meters = new SimpleMeterRegistry();
        metrics = new PravahaMetrics(meters, node);
        audit = new RecordingAudit();
    }

    @AfterEach
    void stop() {
        metrics.close();
        meters.close();
        node.stop();
    }

    @Test
    void anUndecodableRecordIsListedFetchedCountedAndMeasured() throws Exception {
        RegisteredQuery query = register("big_txn");
        awaitRows(query, 1);

        Files.writeString(txn, "2,bob,not-a-number\n", StandardOpenOption.APPEND);
        awaitDeadLetters("big_txn", 1);

        assertThat(query.feedStatus().stopped())
                .as("the whole point: one bad record does not stop the feed")
                .isFalse();

        DeadLetterController api = api();
        DeadLetterDtos.Page page = api.list("big_txn", "0", "50", as(DANA));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.configured()).isTrue();
        assertThat(page.retention()).contains("bytes");
        DeadLetterDtos.DeadLetter entry = page.entries().get(0);
        assertThat(entry.query()).isEqualTo("big_txn");
        assertThat(entry.stream()).isEqualTo("txn");
        assertThat(entry.offset()).isEqualTo("line 2");
        assertThat(entry.code()).isEqualTo("PRV-5040");
        assertThat(entry.reason()).contains("amount");
        assertThat(entry.at()).isNotNull();
        assertThat(entry.replay()).isEqualTo("NEW");
        assertThat(entry.withheld()).isNull();
        assertThat(new String(Base64.getDecoder().decode(entry.raw()), StandardCharsets.UTF_8))
                .isEqualTo("2,bob,not-a-number");

        // One whole, by its id, which is the correlation id the node's log line carries.
        assertThat(api.show("big_txn", entry.id(), as(DANA)).raw()).isEqualTo(entry.raw());

        // The count, without any of the records: what a dashboard polls.
        DeadLetterDtos.Count count = api.count("big_txn", as(DANA));
        assertThat(count.total()).isEqualTo(1);
        assertThat(count.evicted()).isZero();
        assertThat(count.bytes()).isPositive();

        // Prometheus. DeadLetterRate was wired to nothing before this.
        metrics.sync();
        assertThat(gauge("pravaha.query.dead.letters", "big_txn")).isEqualTo(1);
        assertThat(gauge("pravaha.query.dead.letters.bytes", "big_txn")).isPositive();
        assertThat(gauge("pravaha.query.dead.letters.degraded", "big_txn"))
                .as("one rejection is not a rate; the minimum sample exists so alerts survive week two")
                .isZero();
        assertThat(meters.find("pravaha.query.dead.letters.evicted")
                        .tag("query", "big_txn")
                        .functionCounter()
                        .count())
                .isZero();

        // The actuator's health indicator counts what is waiting.
        assertThat(new EngineHealthIndicator(node).health().getDetails())
                .containsEntry("deadLetters", 1L)
                .containsEntry("deepestDeadLetterQueue", "big_txn (1)");
    }

    @Test
    void aRecordThatFailsAgainGoesBackOnTheQueueRatherThanLooping() throws Exception {
        RegisteredQuery query = register("big_txn");
        awaitRows(query, 1);
        Files.writeString(txn, "2,bob,not-a-number\n", StandardOpenOption.APPEND);
        awaitDeadLetters("big_txn", 1);

        DeadLetterController api = api();
        String id = api.list("big_txn", "0", "10", as(DANA)).entries().get(0).id();
        DeadLetterDtos.ReplayResult again =
                api.replay("big_txn", new DeadLetterDtos.ReplayRequest(List.of(id)), as(DANA));

        assertThat(again.failedAgain())
                .as("the same bytes decode the same way: a second failure, not a loop")
                .isEqualTo(1);
        assertThat(again.results().get(0).outcome()).isEqualTo("FAILED_AGAIN");
        assertThat(again.results().get(0).newId())
                .as("it went back as a new entry, which is what a client replays next")
                .isNotEmpty();
        assertThat(api.count("big_txn", as(DANA)).total())
                .as("the original is kept beside the new one, so a twice-tried record is visibly that")
                .isEqualTo(2);
        assertThat(api.show("big_txn", id, as(DANA)).replay()).isEqualTo("FAILED_AGAIN");
        assertThat(api.show("big_txn", again.results().get(0).newId(), as(DANA)).reason())
                .contains("replay of " + id);
    }

    @Test
    void replayPutsACorrectedRecordThroughTheQueryAndTheViewChanges() throws Exception {
        RegisteredQuery query = register("big_txn");
        awaitRows(query, 1);
        Files.writeString(txn, "2,bob,not-a-number\n", StandardOpenOption.APPEND);
        awaitDeadLetters("big_txn", 1);

        // Correcting a dead letter is what the console's screen offers: the bytes are edited and
        // put back. Done here by rewriting the recorded bytes, which is the same thing.
        correct(dlqDirectory, "big_txn", "2,bob,not-a-number", "2,bob,250");

        DeadLetterController api = api();
        String id = api.list("big_txn", "0", "10", as(DANA)).entries().get(0).id();
        long before = query.view().size();

        DeadLetterDtos.ReplayResult replayed =
                api.replay("big_txn", new DeadLetterDtos.ReplayRequest(List.of(id)), as(DANA));

        assertThat(replayed.replayed()).isEqualTo(1);
        assertThat(replayed.results().get(0).outcome()).isEqualTo("REPLAYED");
        assertThat(replayed.results().get(0).detail())
                .as("the semantics, stated where somebody will read them")
                .contains("at the frontier the query has reached");
        awaitViewSize(query, before + 1);
        assertThat(api.show("big_txn", id, as(DANA)).replay()).isEqualTo("REPLAYED");
    }

    @Test
    void aRowFilteredPrincipalIsRefusedTheBytesAndTheReplay(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("txn.csv");
        Files.writeString(file, "1,ann,100\n");
        Path directory = dir.resolve("dlq");
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .deadLetteringTo(directory)
                .bind(new SourceBinding(
                        "txn", "filesystem", Map.of("path", file.toString(), "schema", SCHEMA_SPEC, "follow", "true")));
        try (QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), ROW_FILTERED, audit, schema("txn")).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register("big_txn", SQL, List.of(0), DANA);
            awaitRows(query, 1);
            Files.writeString(file, "2,bob,not-a-number\n", StandardOpenOption.APPEND);
            awaitDeadLetters(feeds, "big_txn", 1);

            DeadLetterController api = new DeadLetterController(
                    new HttpAuthorizer(ROW_FILTERED, audit), new RegistryAccess(registry, null, feeds, audit));
            DeadLetterDtos.Page page = api.list("big_txn", "0", "50", as(SLICED));

            assertThat(page.total()).as("the count is operational, not data").isEqualTo(1);
            DeadLetterDtos.DeadLetter entry = page.entries().get(0);
            assertThat(entry.raw()).as("the record is a row of the source").isNull();
            assertThat(entry.reason())
                    .as("the decoder's sentence quotes the value it choked on, which is a row's content")
                    .isEmpty();
            assertThat(entry.withheld()).contains("row-filtered").contains("no row for that filter");
            assertThat(entry.offset())
                    .as("where it came from is not what was in it")
                    .isEqualTo("line 2");
            assertThat(entry.code()).isEqualTo("PRV-5040");
            assertThat(entry.size()).as("a length is not a row").isPositive();
            assertThat(api.show("big_txn", entry.id(), as(SLICED)).raw()).isNull();
            assertThat(api.list("big_txn", "0", "50", as(DANA)).entries().get(0).raw())
                    .as("the control: an unrestricted reader gets the record")
                    .isNotNull();

            assertThatThrownBy(() ->
                            api.replay("big_txn", new DeadLetterDtos.ReplayRequest(List.of(entry.id())), as(SLICED)))
                    .as("a replay changes the view every other reader sees")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("may not dlq.replay")
                    .extracting(
                            failure -> ((PravahaException) failure).errorCode().code())
                    .isEqualTo("PRV-7002");
            assertThat(audit.actions).contains("dlq.list", "dlq.show", "dlq.replay");
        }
    }

    @Test
    void aQueryThePolicyDeniesHasNoDeadLettersHere(@TempDir Path dir) throws Exception {
        // Denies the view's name and allows the stream behind it, so the query can be registered
        // and is then invisible by name -- which is the shape the listing rules refuse on.
        SecurityPolicy denies = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return view.equals("big_txn") ? AccessDecision.deny("big_txn is not yours") : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        Path file = dir.resolve("txn.csv");
        Files.writeString(file, "1,ann,100\n");
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .deadLetteringTo(dir.resolve("dlq"))
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", file.toString(), "schema", SCHEMA_SPEC)));
        try (QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), denies, audit, schema("txn")).feedingFrom(feeds)) {
            registry.register("big_txn", SQL, List.of(0), DANA);
            DeadLetterController api = new DeadLetterController(
                    new HttpAuthorizer(denies, audit), new RegistryAccess(registry, null, feeds, audit));

            assertThatThrownBy(() -> api.list("big_txn", "0", "10", as(DANA)))
                    .as("refused by name whether or not it exists: not an existence oracle")
                    .isInstanceOf(PravahaException.class)
                    .extracting(
                            failure -> ((PravahaException) failure).errorCode().code())
                    .isEqualTo("PRV-7002");
            assertThat(audit.actions).contains("dlq.list");
        }
    }

    @Test
    void anIdThatIsNotInTheQueueIsItsOwnRefusal() throws Exception {
        register("big_txn");

        assertThatThrownBy(() -> api().show("big_txn", "not-an-id", as(DANA)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("retention has evicted it")
                .extracting(failure -> ((PravahaException) failure).errorCode().code())
                .isEqualTo("PRV-4091");
    }

    @Test
    void replayingNothingSaysSoRatherThanReplayingEverything() throws Exception {
        register("big_txn");

        assertThatThrownBy(() -> api().replay("big_txn", new DeadLetterDtos.ReplayRequest(List.of()), as(DANA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("say which dead letters to replay");
    }

    /** Rewrites one recorded record's bytes, which is what "correct it and put it back" means. */
    private static void correct(Path directory, String query, String was, String now) throws Exception {
        Path file = DeadLetterFiles.letters(directory, query);
        String encodedWas = Base64.getEncoder().encodeToString(was.getBytes(StandardCharsets.UTF_8));
        String encodedNow = Base64.getEncoder().encodeToString(now.getBytes(StandardCharsets.UTF_8));
        Files.writeString(file, Files.readString(file).replace(encodedWas, encodedNow));
    }

    private RegisteredQuery register(String name) {
        return node.registry().orElseThrow().register(name, SQL, List.of(0), DANA);
    }

    /** The endpoints over the running node, with the node's own policy. */
    private DeadLetterController api() {
        return new DeadLetterController(
                new HttpAuthorizer(node.registry().orElseThrow().policy(), audit),
                new RegistryAccess(
                        node.registry().orElseThrow(), null, node.sources().orElse(null), audit));
    }

    private double gauge(String meter, String query) {
        return meters.find(meter).tag("query", query).gauge().value();
    }

    private static StreamSchema schema(String name) {
        return StreamSchema.builder(name)
                .field("id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    private static SourceBindingProperties.Spec followed(Path path) {
        SourceBindingProperties.Spec spec = new SourceBindingProperties.Spec();
        spec.setPlugin("filesystem");
        spec.getOptions().put("path", path.toString());
        spec.getOptions().put("schema", SCHEMA_SPEC);
        spec.getOptions().put("follow", "true");
        return spec;
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    private static void awaitRows(RegisteredQuery query, long rows) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (query.rowsIn() < rows && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(query.rowsIn()).isGreaterThanOrEqualTo(rows);
    }

    private static void awaitViewSize(RegisteredQuery query, long size) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (query.view().size() < size && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat((long) query.view().size())
                .as("the replayed row is in the view")
                .isGreaterThanOrEqualTo(size);
    }

    private void awaitDeadLetters(String query, long count) throws InterruptedException {
        awaitDeadLetters(node.sources().orElseThrow(), query, count);
    }

    private static void awaitDeadLetters(PluginSourceFeeds feeds, String query, long count)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (feeds.deadLetters().counts(query).entries() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(feeds.deadLetters().counts(query).entries()).isGreaterThanOrEqualTo(count);
    }

    /** Records the verbs, so the audit trail can be shown to name the dead-letter surfaces. */
    private static final class RecordingAudit implements AuditSink {

        private final List<String> actions = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void record(AuditEvent event) {
            actions.add(event.action());
        }
    }
}

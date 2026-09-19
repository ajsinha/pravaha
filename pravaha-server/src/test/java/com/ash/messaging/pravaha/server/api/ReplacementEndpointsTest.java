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
package com.ash.messaging.pravaha.server.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP surface of a blue/green replacement, which is what the console's cutover screen is built
 * on (design section 23.10, ADR-046).
 *
 * <p>Against a real source -- a file with records in it -- because the endpoint an operator watches
 * during a backfill is worth nothing if the numbers in it are always zero.
 */
class ReplacementEndpointsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String V1 = "SELECT 'all' AS bucket, SUM(amount) AS total FROM txn";
    private static final String V2 = "SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn";

    private static final Principal OPERATOR = new Principal("dana", "acme", Set.of("operator"), Map.of());

    /** Allowed to read everything and to administer nothing. */
    private static final Principal READER = new Principal("ray", "acme", Set.of("reader"), Map.of());

    private static final SecurityPolicy POLICY = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }

        @Override
        public AccessDecision mayAdminister(Principal principal, String view) {
            return principal.roles().contains("operator")
                    ? AccessDecision.allow()
                    : AccessDecision.deny("only an operator may administer '" + view + "'");
        }
    };

    @TempDir
    Path directory;

    private QueryRegistry registry;
    private ReplacementController controller;
    private Path data;

    @BeforeEach
    void start() throws IOException {
        data = directory.resolve("txn.csv");
        append(1, 200);
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of(
                                "path", data.toString(),
                                "schema", "user_id:STRING,amount:INT64",
                                "follow", "true")));
        registry = new QueryRegistry(new ViewCatalog(), POLICY, AuditSink.NONE, TXN).feedingFrom(feeds);
        controller = new ReplacementController(
                new RegistryAccess(registry, null, AuditSink.NONE), new HttpAuthorizer(POLICY, AuditSink.NONE));
        registry.register("orders", V1, List.of(0), OPERATOR);
        await(() -> registry.require("orders").view().size() == 1);
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void aReplacementIsStartedWatchedCutOverAndRolledBackOverHttp() {
        ApiDtos.ReplacementStatus started = controller.start(
                "orders",
                new ReplacementController.StartReplacement(V2, List.of(0), "history", 2_000L, "manual", "PT10M"),
                as(OPERATOR));
        assertThat(started.state()).isIn("BACKFILLING", "CAUGHT_UP");
        assertThat(started.sql()).isEqualTo(V2);
        assertThat(started.backfill().rateLimit()).isEqualTo(2_000);

        await(() -> "CAUGHT_UP".equals(controller.get("orders", as(OPERATOR)).state()));
        ApiDtos.BackfillProgress progress = controller.backfill("orders", as(OPERATOR));
        assertThat(progress.historyRows())
                .as("the records that existed when the replacement started, read once")
                .isGreaterThanOrEqualTo(200);
        assertThat(progress.historyComplete()).isTrue();
        assertThat(progress.partitionsLive()).isEqualTo(progress.partitions());

        assertThat(controller.list(as(OPERATOR)))
                .singleElement()
                .satisfies(status -> assertThat(status.name()).isEqualTo("orders"));

        ApiDtos.ReplacementStatus cut = controller.cutOver("orders", as(OPERATOR));
        assertThat(cut.state()).isEqualTo("CUT_OVER");
        assertThat(cut.rollbackAvailable()).isTrue();
        assertThat(cut.history())
                .as("who served this name from which seam, oldest first")
                .hasSize(2)
                .last()
                .asString()
                .contains(cut.candidate());
        assertThat(registry.require("orders").sql()).isEqualTo(V2);

        assertThat(controller.rollBack("orders", as(OPERATOR)).state()).isEqualTo("ROLLED_BACK");
        assertThat(registry.require("orders").sql()).isEqualTo(V1);
    }

    @Test
    void theBackfillIsThrottledPausedAndResumedOverHttp() {
        controller.start(
                "orders", new ReplacementController.StartReplacement(V2, null, null, 100L, null, null), as(OPERATOR));

        assertThat(controller.throttle("orders", 10, as(OPERATOR)).backfill().rateLimit())
                .isEqualTo(10);
        assertThat(controller.pause("orders", as(OPERATOR)).backfill().paused()).isTrue();
        assertThat(controller.resume("orders", as(OPERATOR)).backfill().paused())
                .isFalse();
        assertThat(controller.abandon("orders", as(OPERATOR)).state()).isEqualTo("ABANDONED");
    }

    @Test
    void replacingIsAdministeringAndAReaderMayNotEvenWatchOne() {
        controller.start(
                "orders", new ReplacementController.StartReplacement(V2, null, null, null, null, null), as(OPERATOR));

        assertThatThrownBy(() -> controller.start(
                        "orders",
                        new ReplacementController.StartReplacement(V2, null, null, null, null, null),
                        as(READER)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
        assertThatThrownBy(() -> controller.cutOver("orders", as(READER)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
        assertThatThrownBy(() -> controller.get("orders", as(READER)))
                .as("a candidate's SQL and progress describe a query a reader may not be told about")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002");
        assertThat(controller.list(as(READER)))
                .as("a listing filters rather than refuses: to a reader there is nothing to see")
                .isEmpty();
    }

    @Test
    void whatIsRefusedOverHttpKeepsItsCode() {
        assertThatThrownBy(() -> controller.get("orders", as(OPERATOR)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4016");
        assertThatThrownBy(() -> controller.start(
                        "orders",
                        new ReplacementController.StartReplacement(null, null, null, null, null, null),
                        as(OPERATOR)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1050");
        assertThatThrownBy(() -> controller.start(
                        "orders",
                        new ReplacementController.StartReplacement(V2, null, "yesterday", null, null, null),
                        as(OPERATOR)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018");
    }

    private MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    private void append(int from, int to) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            text.append("u").append(i % 5).append(',').append(i).append('\n');
        }
        Files.writeString(
                data, text.toString(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
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
        assertThat(condition.getAsBoolean())
                .as("the condition did not hold in 30 seconds")
                .isTrue();
    }
}

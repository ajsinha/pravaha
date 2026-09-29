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

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDXVIS-1: which access path a view's reads took is visible -- in {@code GET /api/v1/queries/{name}}
 * as {@code accessPaths}, and so on the console's query page -- where it was only in the view's own
 * counters, which nothing a user can reach read.
 */
class AccessPathsVisibleTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private ViewCatalog views;
    private QueryRegistry registry;
    private QueryController queries;
    private RowArena arena;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);
        queries = new QueryController(
                catalog,
                new DtoMapper(),
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE),
                new RegistryAccess(registry, null, AuditSink.NONE));
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stop() {
        registry.close();
        arena.close();
    }

    @Test
    void theQueryDetailSaysHowItsViewsReadsFoundTheirRows() {
        new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(
                        ContinuousStatements.recognize("CREATE CONTINUOUS QUERY latest KEYED BY (user_id) "
                                        + "INDEX (region) AS SELECT user_id, region, amount FROM txn")
                                .orElseThrow(),
                        DANA);
        RegisteredQuery latest = registry.require("latest");
        txn(latest, "u1", "eu", 10);
        txn(latest, "u2", "us", 20);
        latest.commit();

        ApiDtos.AccessPaths before = queries.get("latest", as(DANA)).accessPaths();
        assertThat(before.point() + before.index()).isZero();

        ViewQuery reads = new ViewQuery(views);
        reads.execute("SELECT amount FROM latest WHERE user_id = 'u1'");
        reads.execute("SELECT user_id FROM latest WHERE region = 'eu'");
        reads.execute("SELECT user_id FROM latest WHERE region IN ('eu', 'us')");
        reads.execute("SELECT user_id FROM latest WHERE amount > 5");

        ApiDtos.AccessPaths after = queries.get("latest", as(DANA)).accessPaths();
        assertThat(after.point() - before.point()).as("by the whole key").isEqualTo(1);
        assertThat(after.index() - before.index()).as("by the equality index").isEqualTo(2);
        assertThat(after.scan() - before.scan()).as("a column nobody indexed").isEqualTo(1);
        assertThat(after.range()).isZero();
        assertThat(after.indexes()).containsExactly(Map.entry("region", 2L));
    }

    private void txn(RegisteredQuery query, String user, String region, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, region).setLong(2, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static MockHttpServletRequest as(Principal who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return request;
    }
}

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOOLATE-1: rows too late to correct any window were counted and published nowhere, so an operator
 * could not tell whether a stream's allowed lateness was set right. The count is in
 * {@code GET /api/v1/queries/{name}} as {@code lateRecords}, and in the plan's query telemetry.
 */
class LateRecordsVisibleTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .allowedLateness(Duration.ofSeconds(5))
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private QueryRegistry registry;
    private QueryController queries;
    private RowArena arena;

    @BeforeEach
    void start() {
        registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
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
    void rowsPastTheAllowedLatenessAreCountedInTheQueryDetailAndItsPlan() {
        RegisteredQuery query = registry.register(
                "spend",
                "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)",
                List.of(0),
                DANA);
        txn(query, "u1", 10, 1 * SECOND);
        query.advanceWatermark(12 * SECOND); // [0,10) fires, and is correctable until 15s
        txn(query, "u1", 5, 2 * SECOND); // within the five seconds: a correction, not late
        assertThat(queries.get("spend", as(DANA)).lateRecords()).isZero();

        query.advanceWatermark(30 * SECOND); // [0,10) is past its lateness and released
        txn(query, "u1", 7, 3 * SECOND);
        txn(query, "u1", 9, 4 * SECOND);

        assertThat(query.lateRecords()).isEqualTo(2);
        assertThat(queries.get("spend", as(DANA)).lateRecords()).isEqualTo(2);
        assertThat(Objects.requireNonNull(queries.plan("spend", as(DANA)).query())
                        .lateRecords())
                .isEqualTo(2);
    }

    private void txn(RegisteredQuery query, String user, long amount, long tsNanos) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, tsNanos);
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static MockHttpServletRequest as(Principal who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return request;
    }
}

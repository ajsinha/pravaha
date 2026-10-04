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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.config.Configuration;
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
 * CKPTWHY-1: a failing checkpoint raised {@code pravaha_query_checkpoint_failures_total} and its reason
 * was kept on the query with nothing reading it. The query's description now says why.
 */
class CheckpointFailureVisibleTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    @Test
    void theQueryDetailNamesWhyItsLastCheckpointFailed(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(
                Files.getFileStore(root).supportsFileAttributeView("posix"), "requires POSIX permissions");
        Configuration cfg = Configuration.builder()
                .set("pravaha.checkpoint.interval", "100ms")
                .set("pravaha.checkpoint.keep", "3")
                .build();
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN).checkpointingTo(root, cfg)) {
            StreamCatalog catalog = new StreamCatalog();
            catalog.register(TXN);
            QueryController queries = new QueryController(
                    catalog,
                    new DtoMapper(),
                    new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE),
                    new RegistryAccess(registry, null, AuditSink.NONE));
            RegisteredQuery query = registry.register(
                    "totals",
                    "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)",
                    List.of(0),
                    DANA);

            ApiDtos.QueryCheckpoint healthy = queries.get("totals", as(DANA)).checkpoint();
            assertThat(java.util.Objects.requireNonNull(healthy).enabled()).isTrue();
            assertThat(healthy.lastFailure()).isNull();

            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r--------"));
            try {
                long deadline = System.nanoTime() + 15_000_000_000L;
                while (query.checkpointFailures() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
            } finally {
                Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            }

            ApiDtos.QueryCheckpoint failing = queries.get("totals", as(DANA)).checkpoint();
            assertThat(java.util.Objects.requireNonNull(failing).failures()).isPositive();
            assertThat(failing.lastFailure()).contains("checkpoint failed (");
        }
    }

    private static MockHttpServletRequest as(Principal who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return request;
    }
}

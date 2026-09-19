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

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HLP-4: {@code GET /api/v1/sinks} states the guarantee this node would give, not the SPI's best
 * case.
 *
 * <p>It printed {@code SinkCapabilities.guarantee()}, which folds idempotent upsert into {@code
 * EXACTLY_ONCE} and knows nothing of checkpoints. So {@code aerospike-sink} was listed exactly once
 * where the registration log says effectively once, and a transactional {@code jdbc-sink} on a node
 * with no checkpoint directory was listed exactly once where each commit is its own transaction and
 * a restart repeats it.
 */
class SinkGuaranteeListingTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal ROOT = new Principal("root", "acme", Set.of("analyst"), Map.of());

    private static PluginSinks sinks(Path dir) {
        return new PluginSinks()
                .bind(new SinkBinding("upserts", "guarantee-probe", Map.of("idempotent", "true")))
                .bind(new SinkBinding("staged", "guarantee-probe", Map.of("transactional", "true")))
                // jdbc-sink's default: a staging table, and an upsert into the target.
                .bind(new SinkBinding(
                        "staged_upserts", "guarantee-probe", Map.of("transactional", "true", "idempotent", "true")))
                .bind(new SinkBinding(
                        "file",
                        "filesystem",
                        Map.of("path", dir.resolve("o.csv").toString(), "schema", "user_id:STRING,amount:INT64")));
    }

    private static Map<String, String> guarantees(QueryRegistry registry, PluginSinks sinks) {
        AuditSink audit = new AuditSink.InMemory();
        SinkController controller = new SinkController(
                new DtoMapper(),
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, audit),
                new RegistryAccess(registry, sinks, audit));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, ROOT);
        Map<String, String> out = new TreeMap<>();
        controller.list(request).forEach(sink -> out.put(sink.name(), sink.guarantee()));
        return out;
    }

    @Test
    void withoutCheckpointsNothingIsListedExactlyOnce(@TempDir Path dir) {
        PluginSinks sinks = sinks(dir);
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN).writingTo(sinks)) {
            assertThat(guarantees(registry, sinks))
                    .containsEntry("upserts", "EFFECTIVELY_ONCE")
                    .as("transactional, but nothing to tie a transaction to")
                    .containsEntry("staged", "AT_LEAST_ONCE")
                    .as("each commit its own transaction, and the upsert makes the repeat harmless")
                    .containsEntry("staged_upserts", "EFFECTIVELY_ONCE")
                    .containsEntry("file", "AT_LEAST_ONCE");
        } finally {
            sinks.close();
        }
    }

    @Test
    void aTransactionalSinkOnACheckpointedNodeIsExactlyOnce(@TempDir Path dir) {
        PluginSinks sinks = sinks(dir);
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(sinks)
                .checkpointingTo(dir.resolve("checkpoints"), null)) {
            assertThat(guarantees(registry, sinks))
                    .containsEntry("upserts", "EFFECTIVELY_ONCE")
                    .containsEntry("staged", "EXACTLY_ONCE")
                    .containsEntry("staged_upserts", "EXACTLY_ONCE")
                    .containsEntry("file", "AT_LEAST_ONCE");
        } finally {
            sinks.close();
        }
    }
}

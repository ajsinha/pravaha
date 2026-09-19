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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HLP-8: {@code registeredQueries} on {@code /api/v1/status} counts registered queries.
 *
 * <p>It was {@code catalog.size()}, the number of declared streams, and the HTML page built from the
 * same DTO labelled the number "Streams" -- so the page was right and the JSON field was not. A node
 * with twenty streams and two queries said twenty.
 */
class StatusCountsTest {

    private static StreamSchema stream(String name) {
        return StreamSchema.builder(name)
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    @Test
    void registeredQueriesCountsQueriesAndStreamsCountsStreams() throws Exception {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(stream("a"));
        catalog.register(stream("b"));
        catalog.register(stream("c"));
        Principal dana = new Principal("dana", "acme", Set.of("analyst"), Map.of());
        try (PravahaEngine engine = PravahaEngine.createDefault();
                QueryRegistry registry = new QueryRegistry(new ViewCatalog(), stream("a"))) {
            registry.register("first", "SELECT user_id, amount FROM a", List.of(0), dana);
            StatusController controller =
                    new StatusController(engine, catalog, new RegistryAccess(registry, null, AuditSink.NONE));

            ApiDtos.NodeStatus status = controller.status();

            assertThat(status.registeredQueries()).as("one query registered").isEqualTo(1);
            assertThat(status.streams()).as("three streams declared").isEqualTo(3);
            assertThat(controller.statusPage())
                    .contains("<th>Registered queries</th><td>1</td>")
                    .contains("<th>Streams</th><td>3</td>");
        }
    }
}

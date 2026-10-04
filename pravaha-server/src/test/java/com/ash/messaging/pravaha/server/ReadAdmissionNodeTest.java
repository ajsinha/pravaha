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

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.ReadLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * READADMIT-1: {@code pravaha.serving.read.*} reaches the node's gateways. A node used to run every
 * read through {@code ReadAdmission.UNLIMITED} with no deadline and no setting for either, so the
 * refusals {@code PRV-4026} to {@code PRV-4029} could not happen on one.
 */
class ReadAdmissionNodeTest {

    private static final Principal ANN = new Principal("ann", "public", Set.of(), Map.of());

    private static PravahaNode node() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn").field("id", Types.int64()).build());
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("read-admission-node")
                .build();
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    private static void read(PravahaNode node, String sql) throws Exception {
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightSqlClient client = new FlightSqlClient(FlightClient.builder(
                                allocator,
                                Location.forGrpcInsecure(
                                        "127.0.0.1", node.flightPort().orElseThrow()))
                        .build())) {
            FlightInfo info = client.execute(sql);
            try (FlightStream stream =
                    client.getStream(info.getEndpoints().get(0).getTicket())) {
                while (stream.next()) {
                    // the read is what is admitted
                }
            }
        }
    }

    @Test
    void aNodeAtItsReadLimitRefusesWithTheDocumentedCode() throws Exception {
        ReadLimitsProperties limits = new ReadLimitsProperties();
        limits.setMaxConcurrent(1);
        PravahaNode node = node();
        node.setReadLimits(limits);
        node.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PravahaMetrics metrics = new PravahaMetrics(meters, node);
        try {
            node.registry().orElseThrow().register("ids", "SELECT id FROM txn", List.of(0), ANN);
            ReadAdmission admission = node.readAdmission();
            assertThat(admission).isNotSameAs(ReadAdmission.UNLIMITED);
            read(node, "SELECT id FROM ids"); // admitted while nothing else is running

            // Another tenant's read holds the node's one permit, and no read may queue.
            try (ReadAdmission.Lease _ = admission.acquire(new Principal("bob", "other", Set.of(), Map.of()))) {
                assertThatThrownBy(() -> read(node, "SELECT id FROM ids"))
                        .isInstanceOf(FlightRuntimeException.class)
                        .hasMessageContaining("PRV-4026");
            }
            assertThat(meters.get("pravaha.read.refused")
                            .tag("reason", "rejected")
                            .functionCounter()
                            .count())
                    .isEqualTo(1.0);
            read(node, "SELECT id FROM ids"); // the permit is back
        } finally {
            metrics.close();
            meters.close();
            node.stop();
        }
    }

    @Test
    void byDefaultEveryReadIsAdmittedAsBefore() {
        PravahaNode node = node();
        node.start();
        try {
            assertThat(node.readAdmission()).isSameAs(ReadAdmission.UNLIMITED);
        } finally {
            node.stop();
        }
    }

    @Test
    void aValueOutOfRangeStopsTheNodeBeforeItStarts() {
        ReadLimitsProperties limits = new ReadLimitsProperties();
        limits.setTenantShare(0);

        assertThatThrownBy(() -> node().setReadLimits(limits))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("pravaha.serving.read.tenant-share");
        assertThat(ReadLimits.NONE.maxConcurrent()).isZero();
    }
}

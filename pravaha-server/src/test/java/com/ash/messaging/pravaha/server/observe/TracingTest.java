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
package com.ash.messaging.pravaha.server.observe;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.ash.messaging.pravaha.common.observe.EngineSpans;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code pravaha.tracing.enabled}: a REST request, a registration and a Flight SQL query each
 * produce the spans they should, named and attributed as the observability topic says, and a Flight
 * call continues the trace its client sent in {@code traceparent}. Spans go to an in-memory exporter
 * standing where the OTLP one would.
 */
@SpringBootTest(
        properties = {
            "pravaha.security.allow-anonymous=true",
            "pravaha.flight.port=0",
            "pravaha.flight.host=127.0.0.1",
            "pravaha.tracing.enabled=true",
            "pravaha.streams.orders.schema=user_id:STRING,amount:INT64"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability
// Closed after the class: the engine's span facade is process-wide, and a cached tracing context would
// leave the next test class's engine traced.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TracingTest {

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter spans() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PravahaNode node;

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private SdkTracerProvider tracer;

    private List<SpanData> finished() {
        tracer.forceFlush().join(10, TimeUnit.SECONDS);
        return spans.getFinishedSpanItems();
    }

    private Optional<SpanData> named(String name) {
        return finished().stream().filter(s -> s.getName().equals(name)).findFirst();
    }

    @Test
    void aRestRequestARegistrationAndAFlightQueryAreSpansAndATraceparentIsContinued() throws Exception {
        assertThat(EngineSpans.tracing())
                .as("the engine's spans go to the tracer")
                .isTrue();

        mvc.perform(get("/api/v1/streams")).andExpect(status().isOk());

        node.registry().orElseThrow().register("spend", "SELECT user_id, amount FROM orders", List.of(0), DANA);

        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightSqlClient client = new FlightSqlClient(FlightClient.builder(
                                allocator,
                                Location.forGrpcInsecure(
                                        "127.0.0.1", node.flightPort().orElseThrow()))
                        .build())) {
            FlightInfo info = client.execute("SELECT user_id FROM spend", new HeaderCallOption(headers));
            try (FlightStream stream =
                    client.getStream(info.getEndpoints().get(0).getTicket())) {
                while (stream.next()) {
                    // an empty view; the call is what is traced
                }
            }
        }

        assertThat(finished())
                .extracting(SpanData::getName)
                .contains("pravaha.query.register", "pravaha.flight.query.plan", "pravaha.flight.query")
                .anySatisfy(name -> assertThat(name).startsWith("http get /api/v1/streams"));

        SpanData registration = named("pravaha.query.register").orElseThrow();
        assertThat(registration.getAttributes().get(AttributeKey.stringKey("pravaha.query")))
                .isEqualTo("spend");

        SpanData plan = named("pravaha.flight.query.plan").orElseThrow();
        assertThat(plan.getAttributes().get(AttributeKey.stringKey("operation")))
                .isEqualTo("query.plan");
        assertThat(plan.getTraceId())
                .as("the client's traceparent is continued")
                .isEqualTo(TRACE_ID);
        assertThat(named("pravaha.flight.query").orElseThrow().getAttributes().get(AttributeKey.stringKey("operation")))
                .isEqualTo("query");
    }
}

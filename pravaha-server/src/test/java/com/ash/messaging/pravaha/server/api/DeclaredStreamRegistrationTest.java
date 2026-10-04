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

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.server.PravahaNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DECLSTREAM-1: a stream declared over {@code POST /api/v1/streams} -- what {@code pravaha streams
 * declare} and {@code Client.declare_stream} send -- can be registered over by Flight's {@code
 * pravaha.register}, which is what {@code pravaha register} sends.
 *
 * <p>The HTTP catalogue listed and validated it, and the registry, planning over a copy of the
 * catalogue taken at start, answered {@code PRV-2002 Object 's2' not found}.
 */
@SpringBootTest(properties = {"pravaha.security.allow-anonymous=true", "pravaha.flight.port=0"})
@AutoConfigureMockMvc
class DeclaredStreamRegistrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PravahaNode node;

    private void declare(String name, String schema) throws Exception {
        mvc.perform(post("/api/v1/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new StreamController.RegisterStreamRequest(name, schema))))
                .andExpect(status().isCreated());
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    private List<String> act(String type, String... fields) {
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightClient flight = FlightClient.builder(
                                allocator,
                                Location.forGrpcInsecure(
                                        "127.0.0.1", node.flightPort().orElseThrow()))
                        .build()) {
            List<String> answer = new ArrayList<>();
            flight.doAction(new Action(type, ControlWire.encode(List.of(fields))))
                    .forEachRemaining(r -> answer.addAll(ControlWire.decode(r.getBody())));
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aStreamDeclaredOverHttpValidatesAndRegistersOverFlight() throws Exception {
        declare("decl_s2", "k:INT64,v:INT64");
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\": \"SELECT k FROM decl_s2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        List<String> registered = act(ControlWire.REGISTER, "decl_s2v", "SELECT k, v FROM decl_s2", "0");

        assertThat(registered).startsWith("decl_s2v", "RUNNING");
        assertThat(node.registry().orElseThrow().names()).contains("decl_s2v");
        act(ControlWire.DROP, "decl_s2v");
    }

    @Test
    void aStreamNeverDeclaredIsStillUnknownToARegistration() {
        assertThatThrownBy(() -> act(ControlWire.REGISTER, "decl_none", "SELECT k FROM decl_never", "0"))
                .isInstanceOf(FlightRuntimeException.class)
                .hasMessageContaining("PRV-2002");
    }
}

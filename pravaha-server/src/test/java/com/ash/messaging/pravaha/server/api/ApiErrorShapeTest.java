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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One error shape on the way out, over a real container.
 *
 * <p>CFG-20. {@code application.yaml} turns RFC 7807 problem details off with the stated intent
 * that "every non-2xx response is an ApiError and nothing else, because a client that has to parse
 * two error shapes will handle one of them badly". That worked and was never the mechanism that
 * mattered: {@code ApiExceptionHandler} handles {@code PravahaException} and {@code
 * IllegalArgumentException}, and a 405, a 415 and a 404 on an unmapped path are neither -- so they
 * fell through to Spring Boot's {@code BasicErrorController} and came back as
 * {@code {"timestamp":…,"status":405,"error":"Method Not Allowed","path":…}}: a <em>third</em>
 * shape, with no {@code code}, no {@code message} and no {@code helpUrl}.
 *
 * <p><strong>A real servlet container, not MockMvc.</strong> MockMvc does not run the container's
 * error dispatch, so it sees the right status and an empty body -- and a test written on it passes
 * whether the error controller exists or not, which is how the defect survived a module with a
 * {@code /error}-shaped hole in its coverage. This is the one place in {@code pravaha-server} that
 * starts a port, and it starts it for exactly that reason.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"pravaha.security.allow-anonymous=true", "pravaha.flight.port=0"})
class ApiErrorShapeTest {

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        assertThat(response.getBody())
                .as("a non-2xx response with no body is the shape this finding is about")
                .isNotBlank();
        return json.readTree(response.getBody());
    }

    private void assertIsApiError(JsonNode body) {
        assertThat(body.path("code").asText()).startsWith("PRV-");
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.has("helpUrl")).isTrue();
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.has("path")).isTrue();
        // Spring's own shape, which must not be what comes back any more.
        assertThat(body.has("error"))
                .as("Spring's BasicErrorController shape, not this API's")
                .isFalse();
        assertThat(body.has("status")).isFalse();
    }

    @Test
    void aMethodThePathDoesNotSupportIsAnApiError_CFG20() throws Exception {
        ResponseEntity<String> response =
                http.exchange("/api/v1/streams", HttpMethod.DELETE, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        JsonNode body = body(response);
        assertIsApiError(body);
        assertThat(body.path("code").asText()).isEqualTo("PRV-1052");
        assertThat(body.path("path").asText()).isEqualTo("/api/v1/streams");
        assertThat(body.path("message").asText()).contains("405").contains("/api/v1/streams");
    }

    @Test
    void aBodyInAMediaTypeTheEndpointCannotReadIsAnApiError_CFG20() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = http.exchange(
                "/api/v1/queries/validate", HttpMethod.POST, new HttpEntity<>("SELECT 1", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        JsonNode body = body(response);
        assertIsApiError(body);
        assertThat(body.path("path").asText()).isEqualTo("/api/v1/queries/validate");
    }

    @Test
    void anUnmappedPathIsAnApiError_CFG20() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/nosuchpath", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = body(response);
        assertIsApiError(body);
        assertThat(body.path("path").asText()).isEqualTo("/nosuchpath");
    }

    @Test
    void theShapesThatWereAlreadyCorrectStayCorrect_CFG20() throws Exception {
        // V-control. A refusal that DOES reach a handler method has a specific code, and must keep
        // it: a fix that funnelled everything through the fall-through controller would pass every
        // assertion above and be a worse API than the one it replaced.
        ResponseEntity<String> response = http.getForEntity("/api/v1/streams/nosuchstream", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        JsonNode body = body(response);
        assertIsApiError(body);
        assertThat(body.path("code").asText())
                .as("a handled refusal keeps its own code rather than the fall-through's")
                .isNotEqualTo("PRV-1052");
    }

    /**
     * TOMCATHTML-1: requests the container refuses before any servlet runs. These never reach
     * {@code ApiErrorController}; they were Tomcat's HTML page. Sent over a raw socket because an
     * HTTP client normalises or refuses to send exactly these bytes.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/queries/..%2f..%2fetc%2fpasswd", "/api/v1/streams/a%00b", "/api/v1/queries/..%5c..%5cx"
            })
    void aPathTheContainerRefusesIsAnApiError_TOMCATHTML1(String path) throws Exception {
        assertContainerRefusalIsAnApiError("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    }

    @Test
    void anOversizedHeaderIsAnApiError_TOMCATHTML1() throws Exception {
        assertContainerRefusalIsAnApiError("GET /api/v1/status HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(65_536)
                + "\r\nConnection: close\r\n\r\n");
    }

    @Test
    void tooManyHeadersIsAnApiError_TOMCATHTML1() throws Exception {
        StringBuilder request = new StringBuilder("GET /api/v1/status HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < 500; i++) {
            request.append("X-H").append(i).append(": v\r\n");
        }
        assertContainerRefusalIsAnApiError(
                request.append("Connection: close\r\n\r\n").toString());
    }

    private void assertContainerRefusalIsAnApiError(String rawRequest) throws Exception {
        String reply;
        try (java.net.Socket socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(rawRequest.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            reply = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(reply).startsWith("HTTP/1.1 400");
        int split = reply.indexOf("\r\n\r\n");
        String head = reply.substring(0, split).toLowerCase(java.util.Locale.ROOT);
        String content = reply.substring(split + 4);
        if (head.contains("transfer-encoding: chunked")) {
            content = content.substring(content.indexOf("\r\n") + 2, content.lastIndexOf('}') + 1);
        }
        assertThat(head).contains("content-type: application/json");
        assertThat(content).doesNotContain("<html").doesNotContain("Apache Tomcat");
        JsonNode body = json.readTree(content);
        assertIsApiError(body);
        assertThat(body.path("code").asText()).isEqualTo("PRV-1056");
    }
}

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
}

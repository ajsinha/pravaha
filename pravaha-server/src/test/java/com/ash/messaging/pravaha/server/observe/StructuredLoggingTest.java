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

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code pravaha.logging.format: json}: every line the node writes is one JSON object with the time,
 * the level, the logger, the thread and the message, and the logging context -- correlation id, query,
 * trace and span ids -- as fields of their own. And a format that is neither text nor json refuses the
 * start rather than writing something a log pipeline did not ask for.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    /** Nothing: the point is the logging the environment configures, not a context. */
    @Configuration(proxyBeanMethods = false)
    static class Nothing {}

    private static ConfigurableApplicationContext start(String format) {
        // Another test class's context, cached and still open in this JVM, has Logback marked as
        // initialised, and Boot would then keep its configuration rather than apply this one. A node
        // is one application in its JVM; a test suite is many, so this forgets the mark first.
        org.springframework.boot.logging.LoggingSystem.get(StructuredLoggingTest.class.getClassLoader())
                .cleanUp();
        // As arguments, as an operator would pass them: the shipped application.yaml says text, and a
        // default property would lose to it.
        return new SpringApplicationBuilder(Nothing.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--pravaha.logging.format=" + format,
                        "--spring.main.banner-mode=off",
                        "--spring.application.name=pravaha");
    }

    /**
     * Boot hands the chosen format to Logback through a system property, which outlives the context: left
     * set, every later test in this JVM would log JSON. A deployment starts once, so only a test needs this.
     */
    @AfterEach
    void forgetTheFormat() {
        System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT");
        System.clearProperty("FILE_LOG_STRUCTURED_FORMAT");
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void jsonIsOneObjectPerLineCarryingTheLoggingContext(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ignored = start("json")) {
            Logger log = LoggerFactory.getLogger("com.ash.messaging.pravaha.server.Probe");
            MDC.put(NodeFlightObservation.MDC_CORRELATION, "req-42");
            MDC.put(NodeFlightObservation.MDC_QUERY, "hourly_spend");
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("spanId", "00f067aa0ba902b7");
            try {
                log.info("a line with \"quotes\" and a\nnewline in it");
            } finally {
                MDC.clear();
            }
        }
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> lines = new ArrayList<>();
        for (String line : output.getOut().lines().toList()) {
            // Every line the logging system wrote is one JSON object, which a pipeline parses as it comes.
            // Other lines on stdout -- spring-jcl's notice before logging exists, the last words of an
            // earlier test's node still stopping on its own thread -- are not this context's log.
            if (line.startsWith("{")) {
                lines.add(json.readTree(line));
            } else {
                assertThat(line).as("our line was written as text").doesNotContain("a line with");
            }
        }
        JsonNode ours = lines.stream()
                .filter(n -> n.path("message").asText().startsWith("a line with"))
                .findFirst()
                .orElseThrow();
        assertThat(ours.path("message").asText()).isEqualTo("a line with \"quotes\" and a\nnewline in it");
        assertThat(ours.path("@timestamp").asText()).isNotBlank();
        assertThat(ours.path("level").asText()).isEqualTo("INFO");
        assertThat(ours.path("logger_name").asText()).isEqualTo("com.ash.messaging.pravaha.server.Probe");
        assertThat(ours.path("thread_name").asText()).isNotBlank();
        assertThat(ours.path("correlationId").asText()).isEqualTo("req-42");
        assertThat(ours.path("query").asText()).isEqualTo("hourly_spend");
        assertThat(ours.path("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ours.path("spanId").asText()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void textStaysAPatternAPersonReads(CapturedOutput output) {
        try (ConfigurableApplicationContext ignored = start("text")) {
            LoggerFactory.getLogger("com.ash.messaging.pravaha.server.Probe").info("plain words");
        }
        assertThat(output.getOut()).contains("plain words").doesNotContain("\"message\":\"plain words\"");
    }

    @Test
    void aFormatThatIsNeitherTextNorJsonRefusesTheStart() {
        assertThatThrownBy(() -> start("yaml"))
                .hasMessageContaining("pravaha.logging.format is 'yaml', and it is text or json");
    }
}

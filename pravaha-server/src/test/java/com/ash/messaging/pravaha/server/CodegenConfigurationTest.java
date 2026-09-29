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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.ash.messaging.pravaha.codegen.FilterProjectStageGenerator;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CODEGENPROP-1: {@code pravaha.codegen.enabled} in a node's configuration file decides whether its
 * queries run generated code.
 *
 * <p>The key sat in {@code deploy/qa/server.application.yaml} and bound nothing: the node read only
 * the JVM system property. These start the real application with the setting in a YAML file, as a
 * deployment gives it, and read each query's execution lines.
 */
@Timeout(180)
class CodegenConfigurationTest {

    @TempDir
    Path directory;

    /** The generator is process-wide; later tests in this JVM expect the default node's. */
    @AfterEach
    void restoreTheDefault() {
        FilterProjectStageGenerator.install();
    }

    @Test
    void falseInTheConfigurationFileRunsQueriesInterpreted() throws Exception {
        assertThat(executionPaths("pravaha:\n  codegen:\n    enabled: false\n"))
                .isNotEmpty()
                .allMatch(path -> path.startsWith("interpreted:"));
    }

    @Test
    void byDefaultQueriesRunGeneratedCode() throws Exception {
        assertThat(executionPaths("pravaha:\n  node:\n    id: pravaha-node-01\n"))
                .anyMatch(path -> path.startsWith("generated:"));
    }

    @Test
    void trueInTheConfigurationFileRunsGeneratedCode() throws Exception {
        assertThat(executionPaths("pravaha:\n  codegen:\n    enabled: true\n"))
                .anyMatch(path -> path.startsWith("generated:"));
    }

    private List<String> executionPaths(String yaml) throws Exception {
        String previous = System.getProperty(PravahaNode.CODEGEN_PROPERTY);
        System.clearProperty(PravahaNode.CODEGEN_PROPERTY);
        Path file = Files.writeString(directory.resolve("node.yaml"), yaml);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PravahaServerApplication.class)
                .run(
                        "--spring.config.additional-location=file:" + file,
                        "--spring.main.banner-mode=off",
                        "--server.port=0",
                        "--pravaha.flight.port=0",
                        "--pravaha.security.allow-anonymous=true",
                        "--pravaha.streams.txn.schema=user_id:STRING,amount:INT64")) {
            RegisteredQuery query = context.getBean(PravahaNode.class)
                    .registry()
                    .orElseThrow()
                    .register(
                            "big_spend",
                            "SELECT user_id, amount FROM txn WHERE amount > 100",
                            List.of(0),
                            Principal.ANONYMOUS);
            return new ArrayList<>(query.executionPaths());
        } finally {
            if (previous != null) {
                System.setProperty(PravahaNode.CODEGEN_PROPERTY, previous);
            }
        }
    }
}

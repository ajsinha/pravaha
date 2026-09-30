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
package com.ash.messaging.pravaha.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PRAVAHA_HOME layout ({@code pravaha-home.yaml}, named by bin/pravaha-server): every place a node
 * writes resolves under the home, nothing resolves outside it, and a deployment's
 * {@code conf/application.yaml} still wins over the layout.
 *
 * <p>Run through Spring Boot's own config-data processing, with the same
 * {@code spring.config.additional-location} the launcher passes, so what is tested is the precedence a
 * node actually gets rather than a re-implementation of it. No bean is created: the configuration
 * class is empty and logging is left alone.
 */
class PravahaHomeLayoutTest {

    /**
     * Every key the node reads that is a place on disk, and where the layout puts it. A key added to the
     * node that writes a file belongs in this list -- either with a place under the home, or in
     * {@link #LEFT_TO_THE_DEPLOYMENT} with the reason it is not set.
     */
    private static final Map<String, String> PLACED = Map.of(
            "logging.file.name", "logs/pravaha-server.log",
            "pravaha.registry.journal", "data/registry.journal",
            "pravaha.checkpoint.directory", "data/checkpoints",
            "pravaha.identity.store", "data/identity/identity.journal",
            "pravaha.security.audit-file", "logs/audit.jsonl");

    /** Path-valued keys the layout deliberately leaves unset, because setting them changes behaviour. */
    private static final Set<String> LEFT_TO_THE_DEPLOYMENT = Set.of(
            // derived: beside pravaha.registry.journal, so under data/ with nothing set
            "pravaha.catalog.journal",
            "pravaha.alerts.journal",
            // setting one switches a behaviour on
            "pravaha.dlq.directory",
            "pravaha.state.spill.directory",
            // key material and credentials: the deployment's, under secrets/ or conf/
            "pravaha.identity.bootstrap-password-file",
            "pravaha.flight.tls.certificate",
            "pravaha.flight.tls.key");

    @TempDir
    Path home;

    private String loggingSystem;

    @BeforeEach
    void leaveLoggingAlone() {
        loggingSystem = System.getProperty("org.springframework.boot.logging.LoggingSystem");
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
    }

    @AfterEach
    void restoreLogging() {
        if (loggingSystem == null) {
            System.clearProperty("org.springframework.boot.logging.LoggingSystem");
        } else {
            System.setProperty("org.springframework.boot.logging.LoggingSystem", loggingSystem);
        }
    }

    @Test
    void everyPlaceTheNodeWritesResolvesUnderTheHome() throws IOException {
        try (ConfigurableApplicationContext context = start()) {
            ConfigurableEnvironment environment = context.getEnvironment();
            Path root = home.toRealPath();
            for (Map.Entry<String, String> placed : PLACED.entrySet()) {
                String value = environment.getProperty(placed.getKey());
                assertThat(value).as(placed.getKey()).isNotBlank();
                Path resolved = Path.of(value).normalize();
                assertThat(resolved.isAbsolute())
                        .as("%s = %s", placed.getKey(), value)
                        .isTrue();
                assertThat(resolved.startsWith(root))
                        .as("%s = %s is outside %s", placed.getKey(), value, root)
                        .isTrue();
                assertThat(resolved).isEqualTo(root.resolve(placed.getValue()));
            }
        }
    }

    @Test
    void theDeploymentsConfWinsOverTheLayout() throws IOException {
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(
                home.resolve("conf/application.yaml"),
                "pravaha:\n  registry:\n    journal: /srv/elsewhere/registry.journal\n",
                StandardCharsets.UTF_8);
        try (ConfigurableApplicationContext context = start()) {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty("pravaha.registry.journal"))
                    .isEqualTo("/srv/elsewhere/registry.journal");
            // and what it did not name still comes from the layout
            assertThat(environment.getProperty("pravaha.checkpoint.directory"))
                    .isEqualTo(home.toRealPath().resolve("data/checkpoints").toString());
        }
    }

    @Test
    void withoutAHomeTheJarsOwnDefaultsAreUnchanged() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        try (ConfigurableApplicationContext context = application.run()) {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty("pravaha.registry.journal")).isEmpty();
            assertThat(environment.getProperty("pravaha.checkpoint.directory")).isEmpty();
            assertThat(environment.getProperty("logging.file.name")).isNull();
        }
    }

    /**
     * The layout names only locations. A key in it that is not in {@link #PLACED} is either a new place
     * nobody listed here, or a behaviour smuggled into a file that is supposed to say only where things go.
     */
    @Test
    void theLayoutSetsOnlyTheListedPlacesAndTheLogRotation() throws IOException {
        Set<String> keys = keysOf("pravaha-home.yaml");
        Set<String> expected = new TreeSet<>(PLACED.keySet());
        expected.add("logging.logback.rollingpolicy.max-file-size");
        expected.add("logging.logback.rollingpolicy.max-history");
        expected.add("logging.logback.rollingpolicy.total-size-cap");
        assertThat(keys).containsExactlyInAnyOrderElementsOf(expected);
        for (String key : PLACED.keySet()) {
            String raw = rawValue("pravaha-home.yaml", key);
            assertThat(raw).as(key).startsWith("${PRAVAHA_HOME}/");
        }
    }

    /**
     * Every path-valued key the jar's application.yaml declares is accounted for: placed by the layout, or
     * deliberately left to the deployment. A new {@code *.journal}, {@code *.directory}, {@code *.store} or
     * {@code *-file} setting that nobody placed would be a file written wherever the process happened to
     * start -- in a container, outside /opt/pravaha.
     */
    @Test
    void everyPathValuedSettingTheNodeDeclaresIsPlacedOrDeliberatelyLeft() throws IOException {
        List<String> unaccounted = new ArrayList<>();
        for (String key : keysOf("application.yaml")) {
            boolean pathValued = key.endsWith(".journal")
                    || key.endsWith(".directory")
                    || key.endsWith(".store")
                    || key.endsWith("-file")
                    || key.endsWith(".file.name");
            if (pathValued && !PLACED.containsKey(key) && !LEFT_TO_THE_DEPLOYMENT.contains(key)) {
                unaccounted.add(key);
            }
        }
        assertThat(unaccounted)
                .as("path-valued settings neither placed under PRAVAHA_HOME nor left on purpose")
                .isEmpty();
    }

    private ConfigurableApplicationContext start() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(
                "--spring.config.additional-location=optional:classpath:/pravaha-home.yaml,optional:file:"
                        + home.resolve("conf") + "/",
                "--PRAVAHA_HOME=" + realHome());
    }

    private String realHome() {
        try {
            return home.toRealPath().toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> keysOf(String resource) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource))) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                keys.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        return keys;
    }

    private static String rawValue(String resource, String key) throws IOException {
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource))) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {}
}

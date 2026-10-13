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
package com.ash.messaging.pravaha.server.folders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.env.MapPropertySource;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-064, on a running node: a new file declares, a removed file removes, a changed file replaces -- and
 * nothing a registered query uses is changed or removed under it. Each look is taken by calling the
 * watcher, so nothing waits for an interval.
 */
class DeclarationWatcherTest {

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @TempDir
    Path config;

    @TempDir
    Path out;

    private final StreamCatalog catalog = new StreamCatalog();
    private PravahaNode node;

    private void write(String file, String yaml) throws IOException {
        Path path = config.resolve(file);
        Files.createDirectories(Objects.requireNonNull(path.getParent()));
        Files.writeString(path, yaml);
    }

    private void delete(String file) throws IOException {
        Files.delete(config.resolve(file));
    }

    private void look() {
        node.configFolders().watcher().orElseThrow().poll();
    }

    private QueryRegistry registry() {
        return node.registry().orElseThrow();
    }

    private List<String> problems() {
        return node.configFolders().problems().stream()
                .map(problem -> problem.code() + " " + problem.message())
                .toList();
    }

    @BeforeEach
    void start() throws IOException {
        write("streams/a.yaml", "schema: \"k:INT64\"\n");
        write("connectors/fs.yaml", "plugin: filesystem\noptions:\n  schema: \"k:INT64\"\n");
        write(
                "sinks/out.yaml",
                "plugin: filesystem\noptions:\n  path: " + out.resolve("out.csv") + "\n  schema: \"k:INT64\"\n");
        ConfigFolderProperties settings = new ConfigFolderProperties();
        settings.setDir(config.toString());
        settings.setWatchInterval("off");
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("folders-node")
                .build();
        node.setConfigFolders(new ConfigFolders(
                settings, new PropertySourcesPlaceholdersResolver(List.of(new MapPropertySource("env", Map.of())))));
        node.start();
    }

    @AfterEach
    void stop() {
        node.stop();
    }

    @Test
    void aStreamFileReadAtStartIsQueryableAndSaysWhereItCameFrom() {
        registry().register("q_a", "SELECT k FROM a", List.of(0), DANA);
        assertThat(node.configFolders().origin(DeclarationKind.STREAM, "a"))
                .isEqualTo("file:" + config.resolve("streams/a.yaml"));
    }

    @Test
    void aNewFileIsDeclaredLiveAndARemovedOneIsRemoved() throws IOException {
        write("streams/b.yaml", "schema: \"k:INT64,v:STRING\"\n");
        look();
        assertThat(catalog.contains("b")).isTrue();
        registry().register("q_b", "SELECT k, v FROM b", List.of(0), DANA);
        registry().drop(QueryRegistry.engineName(DANA, "q_b"));

        delete("streams/b.yaml");
        look();
        assertThat(catalog.contains("b")).isFalse();
        assertThat(problems()).isEmpty();
    }

    @Test
    void aChangedFileInUseIsRefusedAndAppliedOnceItsQueryIsDropped() throws IOException {
        registry().register("q_a", "SELECT k FROM a", List.of(0), DANA);
        write("streams/a.yaml", "schema: \"k:INT64,extra:STRING\"\n");
        look();
        assertThat(problems()).singleElement().asString().startsWith("PRV-1065").contains("a.yaml", "q_a");
        assertThat(catalog.require("a").fieldCount())
                .as("the running definition is kept")
                .isEqualTo(1);
        assertThat(registry()
                        .find(QueryRegistry.engineName(DANA, "q_a"))
                        .orElseThrow()
                        .failure())
                .isEmpty();

        registry().drop(QueryRegistry.engineName(DANA, "q_a"));
        look();
        assertThat(problems()).isEmpty();
        assertThat(catalog.require("a").fieldCount()).isEqualTo(2);
    }

    @Test
    void aRemovedFileInUseIsKeptUntilItsQueryIsDropped() throws IOException {
        registry().register("q_a", "SELECT k FROM a", List.of(0), DANA);
        delete("streams/a.yaml");
        look();
        assertThat(catalog.contains("a")).isTrue();
        assertThat(problems()).singleElement().asString().contains("PRV-1065", "removed", "q_a");

        registry().drop(QueryRegistry.engineName(DANA, "q_a"));
        look();
        assertThat(catalog.contains("a")).isFalse();
        assertThat(problems()).isEmpty();
    }

    @Test
    void aSinkAQueryWritesIsNotRemovedUnderIt() throws IOException {
        registry().registerWritingTo("q_out", "SELECT k FROM a", List.of(0), DANA, "out");
        delete("sinks/out.yaml");
        look();
        assertThat(problems()).singleElement().asString().contains("PRV-1065", "q_out");
        registry().drop(QueryRegistry.engineName(DANA, "q_out"));
        look();
        assertThat(problems()).isEmpty();
    }

    @Test
    void anInvalidFileWhileRunningIsReportedAndIgnored() throws IOException {
        write("streams/c.yaml", "schema: [\n");
        write("streams/d.yaml", "schema: \"k:INT64\"\nsource:\n  connector: nobody\n");
        look();
        assertThat(problems())
                .anySatisfy(p -> assertThat(p).startsWith("PRV-1064").contains("c.yaml"));
        assertThat(problems())
                .anySatisfy(p -> assertThat(p).startsWith("PRV-1067").contains("nobody"));
        assertThat(catalog.contains("c")).isFalse();
        assertThat(node.isRunning()).isTrue();

        delete("streams/c.yaml");
        delete("streams/d.yaml");
        look();
        assertThat(problems()).isEmpty();
    }

    @Test
    void aConnectionInUseCannotBeRemovedAndOneUnusedCan() throws IOException {
        write(
                "streams/e.yaml",
                "schema: \"k:INT64\"\nsource:\n  connector: fs\n  options:\n    path: " + out.resolve("e.csv") + "\n");
        look();
        assertThat(node.sources().orElseThrow().bindings().get("e").plugin()).isEqualTo("filesystem");

        delete("connectors/fs.yaml");
        look();
        assertThat(node.configFolders().connectors().find("fs")).isPresent();
        assertThat(problems()).singleElement().asString().contains("PRV-1065", "source:e");

        delete("streams/e.yaml");
        look(); // the stream goes; the connection was looked at first, while e still named it
        look();
        assertThat(node.configFolders().connectors().find("fs")).isEmpty();
        assertThat(problems()).isEmpty();
    }
}

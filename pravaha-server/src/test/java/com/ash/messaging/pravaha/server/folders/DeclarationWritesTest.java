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
import java.time.Duration;
import java.util.ArrayList;
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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.api.ApiDtos;
import com.ash.messaging.pravaha.server.api.DtoMapper;
import com.ash.messaging.pravaha.server.api.StreamController;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-064's write path, on a running node: the console, the API and the CLI write the file a person would,
 * it is in force at once, and a refused write writes nothing. Disabling a stream stops its rows and keeps
 * its queries; enabling it resumes them.
 */
class DeclarationWritesTest {

    private static final Principal ADMIN = new Principal("ada", "acme", Set.of("admin"), Map.of());
    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @TempDir
    Path config;

    @TempDir
    Path data;

    private final StreamCatalog catalog = new StreamCatalog();
    private final List<AuditEvent> audited = new ArrayList<>();
    private PravahaNode node;
    private DeclarationWrites writes;

    @BeforeEach
    void start() throws IOException {
        write("streams/a.yaml", "schema: \"k:INT64\"\n");
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
                .withNodeId("writes-node")
                .build();
        node.setConfigFolders(new ConfigFolders(
                settings,
                new PropertySourcesPlaceholdersResolver(
                        List.of(new MapPropertySource("env", Map.of("KAFKA_PASSWORD", "from-the-environment"))))));
        node.start();
        writes = new DeclarationWrites(node.configFolders(), audited::add);
    }

    @AfterEach
    void stop() {
        node.stop();
    }

    private void write(String file, String yaml) throws IOException {
        Path path = config.resolve(file);
        Files.createDirectories(Objects.requireNonNull(path.getParent()));
        Files.writeString(path, yaml);
    }

    private QueryRegistry registry() {
        return node.registry().orElseThrow();
    }

    private String streamYaml(String schema, String csv) {
        return DeclarationYaml.stream(
                "s",
                schema,
                null,
                null,
                null,
                true,
                new DeclarationYaml.Binding(
                        "filesystem", null, Map.of("path", csv, "schema", schema, "follow", "true")),
                "ada");
    }

    @Test
    void aWrittenStreamIsTheFileAPersonWouldWriteAndIsLiveAtOnce() throws IOException {
        String csv = data.resolve("s.csv").toString();
        DeclarationWrites.Outcome done = writes.write(
                ADMIN, DeclarationKind.STREAM, "s", DeclarationWrites.Action.CREATE, streamYaml("k:INT64", csv), false);

        Path file = config.resolve("streams/s.yaml");
        assertThat(done.applied()).isTrue();
        assertThat(done.path()).isEqualTo(file.toString());
        assertThat(catalog.contains("s"))
                .as("in force without waiting for a look")
                .isTrue();
        assertThat(java.util.Objects.requireNonNull(
                                node.sources().orElseThrow().bindings().get("s"))
                        .plugin())
                .isEqualTo("filesystem");

        write(
                "hand/s.yaml",
                "schema: \"k:INT64\"\nsource:\n  plugin: filesystem\n  options:\n    path: " + csv
                        + "\n    schema: \"k:INT64\"\n    follow: true\n");
        var placeholders = node.configFolders().placeholders();
        assertThat(DeclarationFile.read(DeclarationKind.STREAM, file, placeholders)
                        .canonical())
                .as("the same declaration as the file written by hand")
                .isEqualTo(DeclarationFile.read(DeclarationKind.STREAM, config.resolve("hand/s.yaml"), placeholders)
                        .canonical());
        assertThat(audited).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("config.stream.create");
            assertThat(event.principal().id()).isEqualTo("ada");
            assertThat(event.allowed()).isTrue();
        });
        try (java.util.stream.Stream<java.nio.file.Path> left = Files.list(config.resolve("streams"))) {
            assertThat(left.map(p -> p.getFileName().toString()))
                    .as("no temporary or staging file left behind")
                    .containsExactlyInAnyOrder("a.yaml", "s.yaml");
        }
    }

    @Test
    void aDryRunWritesNothingAndSaysWhatItWouldWrite() {
        DeclarationWrites.Outcome preview = writes.write(
                ADMIN,
                DeclarationKind.STREAM,
                "s",
                DeclarationWrites.Action.CREATE,
                streamYaml("k:INT64", data.resolve("s.csv").toString()),
                true);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.applied()).isFalse();
        assertThat(preview.yaml()).contains("plugin: filesystem");
        assertThat(config.resolve("streams/s.yaml")).doesNotExist();
        assertThat(catalog.contains("s")).isFalse();
        assertThat(audited).isEmpty();
    }

    @Test
    void aChangeOrRemovalInUseIsRefusedAndWritesNothing() throws IOException {
        registry().register("q_a", "SELECT k FROM a", List.of(0), DANA);
        String before = Files.readString(config.resolve("streams/a.yaml"));

        assertThatThrownBy(() -> writes.write(
                        ADMIN,
                        DeclarationKind.STREAM,
                        "a",
                        DeclarationWrites.Action.UPDATE,
                        "schema: \"k:INT64,extra:STRING\"\n",
                        false))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1065")
                .hasMessageContaining("q_a");
        assertThatThrownBy(() -> writes.delete(ADMIN, DeclarationKind.STREAM, "a", false))
                .hasMessageContaining("PRV-1065");
        assertThat(Files.readString(config.resolve("streams/a.yaml"))).isEqualTo(before);
        assertThat(catalog.require("a").fieldCount()).isEqualTo(1);

        DeclarationWrites.Outcome dry = writes.delete(ADMIN, DeclarationKind.STREAM, "a", true);
        assertThat(dry.valid()).isFalse();
        assertThat(dry.code()).isEqualTo("PRV-1065");
        assertThat(dry.dependents()).anySatisfy(user -> assertThat(user).contains("q_a"));
        assertThat(audited).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("config.stream.update");
            assertThat(event.allowed()).isFalse();
        });
    }

    @Test
    void aLiteralSecretIsRefusedAndAReferenceIsWritten() {
        assertThatThrownBy(() -> writes.write(
                        ADMIN,
                        DeclarationKind.CONNECTOR,
                        "kafka",
                        DeclarationWrites.Action.CREATE,
                        "plugin: filesystem\noptions:\n  ssl.truststore.password: hunter2\n",
                        false))
                .hasMessageContaining("PRV-1063")
                .hasMessageNotContaining("hunter2");
        assertThat(config.resolve("connectors/kafka.yaml")).doesNotExist();

        writes.write(
                ADMIN,
                DeclarationKind.CONNECTOR,
                "kafka",
                DeclarationWrites.Action.CREATE,
                DeclarationYaml.binding(
                        DeclarationKind.CONNECTOR,
                        "kafka",
                        new DeclarationYaml.Binding(
                                "filesystem", null, Map.of("ssl.truststore.password", "${KAFKA_PASSWORD}")),
                        true,
                        "ada"),
                false);
        assertThat(node.configFolders().connectors().find("kafka")).isPresent();
    }

    @Test
    void anUnknownPluginIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> writes.write(
                        ADMIN,
                        DeclarationKind.STREAM,
                        "s",
                        DeclarationWrites.Action.CREATE,
                        "schema: \"k:INT64\"\nsource:\n  plugin: nonesuch\n",
                        false))
                .hasMessageContaining("nonesuch");
        assertThat(config.resolve("streams/s.yaml")).doesNotExist();
        assertThat(catalog.contains("s")).isFalse();
    }

    @Test
    void disablingAStreamInUseStopsItsRowsKeepsItsQueryAndEnablingResumesThem() throws Exception {
        Path csv = data.resolve("s.csv");
        Files.writeString(csv, "1\n2\n");
        writes.write(
                ADMIN,
                DeclarationKind.STREAM,
                "s",
                DeclarationWrites.Action.CREATE,
                streamYaml("k:INT64", csv.toString()),
                false);
        registry().register("q_s", "SELECT k FROM s", List.of(0), DANA);
        var query = registry().find(QueryRegistry.engineName(DANA, "q_s")).orElseThrow();
        await(() -> query.rowsIn() == 2);

        DeclarationWrites.Outcome off = writes.enable(ADMIN, DeclarationKind.STREAM, "s", false, false);
        assertThat(off.applied()).as("never refused for being in use").isTrue();
        assertThat(Files.readString(config.resolve("streams/s.yaml"))).contains("enabled: false", "# Stream s");
        assertThat(node.sources().orElseThrow().stoppedStreams()).containsExactly("s");
        Files.writeString(csv, "3\n4\n5\n", java.nio.file.StandardOpenOption.APPEND);
        Thread.sleep(400);
        assertThat(query.rowsIn()).as("nothing read while disabled").isEqualTo(2);
        assertThat(query.state()).isEqualTo(QueryState.RUNNING);

        writes.enable(ADMIN, DeclarationKind.STREAM, "s", true, false);
        assertThat(node.sources().orElseThrow().stoppedStreams()).isEmpty();
        await(() -> query.rowsIn() == 5);
        assertThat(Files.readString(config.resolve("streams/s.yaml"))).doesNotContain("enabled:");
        assertThat(audited).extracting(AuditEvent::action).contains("config.stream.disable", "config.stream.enable");
    }

    @Test
    void aDisabledConnectionStopsTheSourcesThatNameIt() {
        writes.write(
                ADMIN,
                DeclarationKind.CONNECTOR,
                "fs",
                DeclarationWrites.Action.CREATE,
                "plugin: filesystem\noptions:\n  schema: \"k:INT64\"\n",
                false);
        writes.write(
                ADMIN,
                DeclarationKind.STREAM,
                "e",
                DeclarationWrites.Action.CREATE,
                "schema: \"k:INT64\"\nsource:\n  connector: fs\n  options:\n    path: " + data.resolve("e.csv") + "\n",
                false);
        writes.enable(ADMIN, DeclarationKind.CONNECTOR, "fs", false, false);
        assertThat(node.sources().orElseThrow().stoppedStreams()).containsExactly("e");
        assertThatThrownBy(() -> writes.delete(ADMIN, DeclarationKind.CONNECTOR, "fs", false))
                .hasMessageContaining("PRV-1065")
                .hasMessageContaining("source:e");
        writes.enable(ADMIN, DeclarationKind.CONNECTOR, "fs", true, false);
        assertThat(node.sources().orElseThrow().stoppedStreams()).isEmpty();
    }

    @Test
    void aCallerWhoMayNotAdministerIsRefusedBeforeAnythingIsWritten() {
        SecurityPolicy adminsOnly = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayAdminister(Principal principal, String what) {
                return principal.hasRole("admin") ? AccessDecision.allow() : AccessDecision.deny("not an admin");
            }
        };
        StreamController controller =
                new StreamController(catalog, new DtoMapper(), new HttpAuthorizer(adminsOnly, AuditSink.NONE));
        controller.setDeclarationWrites(writes);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, DANA);
        ApiDtos.StreamFileRequest body = new ApiDtos.StreamFileRequest("k:INT64", null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.create("s", body, false, request)).hasMessageContaining("not an admin");
        assertThatThrownBy(() -> controller.disable("a", false, request)).hasMessageContaining("not an admin");
        assertThat(config.resolve("streams/s.yaml")).doesNotExist();

        var asAdmin = new org.springframework.mock.web.MockHttpServletRequest();
        asAdmin.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, ADMIN);
        assertThat(controller.create("s", body, false, asAdmin).getStatusCode().value())
                .isEqualTo(200);
        assertThat(catalog.contains("s")).isTrue();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached in 10s");
            }
            Thread.sleep(20);
        }
    }
}

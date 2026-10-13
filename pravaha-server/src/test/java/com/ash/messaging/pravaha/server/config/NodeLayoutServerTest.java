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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.PravahaServerApplication;
import com.ash.messaging.pravaha.server.api.ApiDtos;
import com.ash.messaging.pravaha.server.api.ConnectorController;
import com.ash.messaging.pravaha.server.api.DeclarationController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-064 end to end, on a real node under the {@code dev} profile: the stores land under
 * {@code pravaha.data.dir} by convention and a restarted node gets its query back; the declarations come
 * from files under {@code pravaha.config.dir}; an explicit path beats the convention.
 */
class NodeLayoutServerTest {

    @TempDir
    Path work;

    private ConfigurableApplicationContext start(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=dev",
                "--spring.main.banner-mode=off",
                "--server.port=0",
                "--pravaha.flight.port=0",
                "--pravaha.config.watch-interval=off",
                "--pravaha.data.dir=" + work.resolve("data"),
                "--pravaha.config.dir=" + work.resolve("config")));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(PravahaServerApplication.class).run(args.toArray(String[]::new));
    }

    private void write(String file, String yaml) throws IOException {
        Path path = work.resolve(file);
        Files.createDirectories(Objects.requireNonNull(path.getParent()));
        Files.writeString(path, yaml);
    }

    @Test
    void aNodeWithOnlyADataDirKeepsEverythingUnderItAndGetsItsQueryBack() throws IOException {
        write(
                "config/streams/txn.yaml",
                "schema: \"id:INT64,amount:INT64\"\nsource:\n  connector: files\n" + "  options:\n    path: "
                        + work.resolve("in/txn.csv") + "\n    schema: \"id:INT64,amount:INT64\"\n");
        write(
                "config/connectors/files.yaml",
                "plugin: filesystem\noptions:\n  follow: true\n" + "  sas-token: ${LAYOUT_TEST_TOKEN:dev-only}\n");
        write("in/txn.csv", "");
        try (ConfigurableApplicationContext node = start()) {
            node.getBean(PravahaNode.class)
                    .registry()
                    .orElseThrow()
                    .register("big", "SELECT id, amount FROM txn WHERE amount > 100", List.of(0), Principal.ANONYMOUS);

            MockHttpServletRequest http = new MockHttpServletRequest();
            ApiDtos.Declarations declared =
                    node.getBean(DeclarationController.class).list(http);
            assertThat(declared.declarations())
                    .anySatisfy(d -> assertThat(d.name() + " " + d.origin() + " " + d.state())
                            .isEqualTo("txn file:" + work.resolve("config/streams/txn.yaml") + " applied"));
            ApiDtos.Connector files = node.getBean(ConnectorController.class).get("files", http);
            assertThat(files.options()).containsEntry("sas-token", "****").containsEntry("follow", "true");
            assertThat(files.usedBy()).containsExactly("source:txn");
        }
        assertThat(work.resolve("data/registry.journal")).isNotEmptyFile();
        assertThat(work.resolve("data/checkpoints")).isDirectory();

        try (ConfigurableApplicationContext restarted = start()) {
            assertThat(restarted
                            .getBean(PravahaNode.class)
                            .registry()
                            .orElseThrow()
                            .names())
                    .anySatisfy(name -> assertThat(name).endsWith("big"));
        }
    }

    @Test
    void anExplicitRegistryJournalBeatsTheConvention() throws IOException {
        Path elsewhere = Files.createDirectories(work.resolve("elsewhere")).resolve("registry.journal");
        try (ConfigurableApplicationContext node = start("--pravaha.registry.journal=" + elsewhere)) {
            assertThat(node.getEnvironment().getProperty("pravaha.registry.journal"))
                    .isEqualTo(elsewhere.toString());
            assertThat(node.getEnvironment().getProperty("pravaha.checkpoint.directory"))
                    .isEqualTo(work.resolve("data/checkpoints").toString());
        }
    }
}

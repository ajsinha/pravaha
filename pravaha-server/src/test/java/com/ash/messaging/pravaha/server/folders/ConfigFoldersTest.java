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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-064: what a declaration file declares at start, against the inline blocks it mirrors. */
class ConfigFoldersTest {

    private static final PlaceholdersResolver ENV = new PropertySourcesPlaceholdersResolver(
            List.of(new MapPropertySource("env", Map.of("TXN_DIR", "/srv/in", "KAFKA_PASSWORD", "s3cret"))));

    @TempDir
    Path config;

    private void write(String file, String yaml) throws IOException {
        Path path = config.resolve(file);
        Files.createDirectories(Objects.requireNonNull(path.getParent()));
        Files.writeString(path, yaml);
    }

    private ConfigFolderProperties settings() {
        ConfigFolderProperties settings = new ConfigFolderProperties();
        settings.setDir(config.toString());
        return settings;
    }

    private record Loaded(
            StreamDeclarationProperties streams, SourceBindingProperties sources, SinkBindingProperties sinks) {}

    private Loaded load(ConfigFolderProperties settings, Map<String, String> inline) {
        Binder binder = new Binder(new MapConfigurationPropertySource(inline));
        Loaded loaded = new Loaded(
                binder.bindOrCreate("pravaha", StreamDeclarationProperties.class),
                binder.bindOrCreate("pravaha", SourceBindingProperties.class),
                binder.bindOrCreate("pravaha", SinkBindingProperties.class));
        new ConfigFolders(settings, ENV).loadInto(loaded.streams(), loaded.sources(), loaded.sinks());
        return loaded;
    }

    @Test
    void aStreamFileDeclaresWhatTheSameInlineBlocksDo() throws IOException {
        write("streams/txn.yaml", """
                # the stream, and where it comes from
                schema: "id:INT64,amount:INT64,event_time:TIMESTAMP"
                event-time: event_time
                out-of-orderness: 60
                allowed-lateness: 30s
                source:
                  plugin: filesystem
                  options:
                    path: ${TXN_DIR}/txn.csv
                    follow: true
                """);
        Loaded fromFile = load(settings(), Map.of());
        Loaded inline = load(
                new ConfigFolderProperties(),
                Map.of(
                        "pravaha.streams.txn.schema", "id:INT64,amount:INT64,event_time:TIMESTAMP",
                        "pravaha.streams.txn.event-time", "event_time",
                        "pravaha.streams.txn.out-of-orderness", "60",
                        "pravaha.streams.txn.allowed-lateness", "30s",
                        "pravaha.sources.txn.plugin", "filesystem",
                        "pravaha.sources.txn.options.path", "/srv/in/txn.csv",
                        "pravaha.sources.txn.options.follow", "true"));

        var file = Objects.requireNonNull(fromFile.streams().getStreams().get("txn"));
        var same = Objects.requireNonNull(inline.streams().getStreams().get("txn"));
        assertThat(file.getSchema()).isEqualTo(same.getSchema());
        assertThat(file.getEventTime()).isEqualTo(same.getEventTime());
        assertThat(file.getOutOfOrderness()).isEqualTo(same.getOutOfOrderness()).isEqualTo(Duration.ofSeconds(60));
        assertThat(file.getAllowedLateness()).isEqualTo(same.getAllowedLateness());
        assertThat(fromFile.sources().toBindings()).isEqualTo(inline.sources().toBindings());
    }

    @Test
    void lookupAndSinkFilesDeclareWhatTheirInlineBlocksDo() throws IOException {
        write("lookups/users.yaml", "plugin: jdbc-lookup\noptions:\n  table: users\n  key.columns: id\n");
        write("sinks/audit_trail.yaml", "plugin: filesystem\noptions:\n  path: /srv/out/audit.csv\n");
        Loaded fromFile = load(settings(), Map.of());
        Loaded inline = load(
                new ConfigFolderProperties(),
                Map.of(
                        "pravaha.lookups.users.plugin", "jdbc-lookup",
                        "pravaha.lookups.users.options.table", "users",
                        "pravaha.lookups.users.options.key.columns", "id",
                        "pravaha.sinks.audit_trail.plugin", "filesystem",
                        "pravaha.sinks.audit_trail.options.path", "/srv/out/audit.csv"));
        assertThat(fromFile.sources().toLookupBindings())
                .isEqualTo(inline.sources().toLookupBindings());
        assertThat(fromFile.sinks().toBindings()).isEqualTo(inline.sinks().toBindings());
    }

    @Test
    void onlyYamlFilesAreRead() throws IOException {
        write("streams/a.yml", "schema: \"k:INT64\"\n");
        write("streams/notes.txt", "not a stream");
        write("streams/.b.yaml", "schema: \"k:INT64\"\n");
        write("streams/c.yaml.example", "schema: \"k:INT64\"\n");
        assertThat(load(settings(), Map.of()).streams().getStreams()).containsOnlyKeys("a");
    }

    @Test
    void aNameNeedingBracketsIsRefused() throws IOException {
        write("streams/tx n.yaml", "schema: \"k:INT64\"\n");
        assertThatThrownBy(() -> load(settings(), Map.of()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1061")
                .hasMessageContaining("tx n");
    }

    @Test
    void aLiteralSecretIsRefusedNamingTheSettingNotTheValue() throws IOException {
        write("sinks/out.yaml", "plugin: jdbc\noptions:\n  url: jdbc:postgresql://db/x\n  password: hunter2\n");
        assertThatThrownBy(() -> load(settings(), Map.of()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1063")
                .hasMessageContaining("options.password")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2"));
        write(
                "sinks/out.yaml",
                "plugin: jdbc\noptions:\n  url: jdbc:postgresql://db/x\n  password: ${KAFKA_PASSWORD}\n");
        assertThat(java.util.Objects.requireNonNull(
                                load(settings(), Map.of()).sinks().getSinks().get("out"))
                        .getOptions())
                .containsEntry("password", "s3cret");
    }

    @Test
    void anUnknownKeyIsRefused() throws IOException {
        write("streams/txn.yaml", "schema: \"k:INT64\"\nevent_tme: k\n");
        assertThatThrownBy(() -> load(settings(), Map.of()))
                .hasMessageContaining("PRV-1064")
                .hasMessageContaining("event_tme");
    }

    @Test
    void oneNameInAFileAndInlineIsRefused() throws IOException {
        write("streams/txn.yaml", "schema: \"k:INT64\"\n");
        assertThatThrownBy(() -> load(settings(), Map.of("pravaha.streams.txn.schema", "k:INT64")))
                .hasMessageContaining("PRV-1062")
                .hasMessageContaining("txn.yaml")
                .hasMessageContaining("pravaha.streams");
    }

    @Test
    void aMissingConventionalFolderIsEmptyAndAMissingNamedOneIsRefused() {
        assertThat(load(settings(), Map.of()).streams().getStreams()).isEmpty();
        ConfigFolderProperties named = settings();
        named.setSinksDir(config.resolve("nowhere").toString());
        assertThatThrownBy(() -> load(named, Map.of()))
                .hasMessageContaining("PRV-1060")
                .hasMessageContaining("pravaha.config.sinks-dir");
    }

    @Test
    void aWatchIntervalIsValidated() {
        ConfigFolderProperties settings = settings();
        assertThat(settings.interval()).contains(Duration.ofMinutes(10));
        settings.setWatchInterval("off");
        assertThat(settings.interval()).isEmpty();
        settings.setWatchInterval("10");
        assertThatThrownBy(settings::interval).hasMessageContaining("PRV-1023");
        settings.setWatchInterval("10ms");
        assertThatThrownBy(settings::interval).hasMessageContaining("PRV-1026");
    }

    @Test
    void aConnectionsOptionsAreLaidUnderTheBindingsOwn() throws IOException {
        write("connectors/fs-in.yaml", "plugin: filesystem\noptions:\n  path: /srv/in/default.csv\n  follow: true\n");
        write(
                "streams/txn.yaml",
                "schema: \"k:INT64\"\nsource:\n  connector: fs-in\n  options:\n    path: /srv/in/txn.csv\n");
        var binding = load(settings(), Map.of()).sources().toBindings().get(0);
        assertThat(binding.plugin()).isEqualTo("filesystem");
        assertThat(binding.options()).containsEntry("path", "/srv/in/txn.csv").containsEntry("follow", "true");
    }

    @Test
    void aBindingNamingADifferentPluginThanItsConnectionIsRefused() throws IOException {
        write("connectors/fs-in.yaml", "plugin: filesystem\n");
        write("sinks/out.yaml", "plugin: kafka\nconnector: fs-in\n");
        assertThatThrownBy(() -> load(settings(), Map.of())).hasMessageContaining("PRV-1068");
    }

    @Test
    void aBindingNamingAnUnknownConnectionIsRefusedAtStart() throws IOException {
        assertThatThrownBy(() -> load(settings(), Map.of("pravaha.sinks.out.connector", "nobody")))
                .hasMessageContaining("PRV-1067")
                .hasMessageContaining("nobody");
    }

    @Test
    void secretOptionsAreMaskedInAListing() {
        assertThat(Connectors.masked(
                        Map.of("bootstrap.servers", "b:9092", "ssl.truststore.password", "x", "sasl.token", "y")))
                .containsEntry("bootstrap.servers", "b:9092")
                .containsEntry("ssl.truststore.password", "****")
                .containsEntry("sasl.token", "****");
    }
}

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
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.config.NodeLayout;
import com.ash.messaging.pravaha.connect.PluginDiscovery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-064: every jar in {@code external_lib/java/} is on the server's classpath at start, through the
 * launcher's {@code -Dloader.path}, and a plugin in one is found by the discovery every binding uses.
 */
class ExternalLibTest {

    /** A sink plugin registered only by the services file in a jar dropped into external_lib/java. */
    public static final class ProbeSink implements StreamSinkPlugin {
        @Override
        public String name() {
            return "external-lib-probe";
        }

        @Override
        public Version version() {
            return Version.apiVersion();
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}

        @Override
        public SinkCapabilities capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(List<RowView> batch) {
            return 0;
        }

        @Override
        public void flush() {}
    }

    @TempDir
    Path work;

    private Path pluginJar(Path into) throws IOException {
        Files.createDirectories(into);
        Path jar = into.resolve("probe-connector.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("META-INF/services/" + StreamSinkPlugin.class.getName()));
            out.write((ProbeSink.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("bin/pravaha-server"))) {
            path = path.getParent();
        }
        return java.util.Objects.requireNonNull(path, "bin/pravaha-server above the working directory");
    }

    @Test
    void theLauncherPutsEveryJarInExternalLibJavaOnTheLoaderPathSorted() throws Exception {
        Path ext = work.resolve("external_lib");
        pluginJar(ext.resolve("java"));
        Files.writeString(ext.resolve("java/a-first.jar"), "");
        Files.writeString(ext.resolve("java/notes.txt"), "not a jar");
        Files.createDirectories(ext.resolve("java/nested"));
        Files.writeString(ext.resolve("java/nested/deeper.jar"), "");
        Path fakeServer = Files.writeString(work.resolve("server.jar"), "");

        ProcessBuilder launcher = new ProcessBuilder(
                        "bash", repoRoot().resolve("bin/pravaha-server").toString(), "--spring.profiles.active=dev")
                .redirectErrorStream(true);
        Map<String, String> env = launcher.environment();
        env.remove("PRAVAHA_HOME");
        env.put("PRAVAHA_LAUNCHER_DRY_RUN", "1");
        env.put("PRAVAHA_SERVER_JAR", fakeServer.toString());
        env.put("PRAVAHA_EXTERNAL_LIB", ext.toString());
        Process run = launcher.start();
        List<String> args = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .toList();
        assertThat(run.waitFor()).as("%s", args).isZero();

        Path java = ext.toRealPath().resolve("java");
        assertThat(args)
                .contains("-Dloader.path=" + java.resolve("a-first.jar") + "," + java.resolve("probe-connector.jar"))
                .contains("-Dpravaha.external-lib.dir=" + ext.toRealPath());
    }

    @Test
    void aPluginInAJarThereIsFoundByTheDiscoveryEveryBindingUses() throws Exception {
        Path ext = work.resolve("external_lib");
        pluginJar(ext.resolve("java"));
        List<URL> urls = new ArrayList<>();
        for (Path jar : NodeLayout.externalJars(ext)) {
            urls.add(jar.toUri().toURL());
        }
        try (URLClassLoader classpath =
                new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
            assertThat(PluginDiscovery.find(StreamSinkPlugin.class, "external-lib-probe", classpath)
                            .plugin())
                    .isInstanceOf(ProbeSink.class);
        }
        assertThat(PluginDiscovery.find(StreamSinkPlugin.class, "external-lib-probe")
                        .plugin())
                .as("not without the jar")
                .isNull();
    }

    @Test
    void theStartLineSaysWhichJarsAreOnTheClasspathAndWhichAreNot() throws IOException {
        Path ext = work.resolve("external_lib");
        Path jar = pluginJar(ext.resolve("java"));
        Map<String, String> launched = Map.of(NodeLayout.EXTERNAL_LIB_DIR, ext.toString());
        assertThat(NodeLayout.describeExternalJars(launched::get))
                .contains("NOT on the classpath: [probe-connector.jar]", "a new jar needs a restart");
        Map<String, String> loaded =
                Map.of(NodeLayout.EXTERNAL_LIB_DIR, ext.toString(), NodeLayout.EXTERNAL_LIB_LOADED, jar.toString());
        assertThat(NodeLayout.describeExternalJars(loaded::get))
                .contains("[probe-connector.jar] on the classpath")
                .doesNotContain("NOT");
    }
}

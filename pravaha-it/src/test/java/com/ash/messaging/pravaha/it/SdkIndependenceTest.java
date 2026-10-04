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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The client SDKs are built and shipped apart from the server, and neither side leans on the other.
 *
 * <p>The owner's requirement: the SDK is used by clients, so it must be standalone and
 * self-sufficient, never bundled into the server. It held when this test was written -- by
 * convention, and a convention holds until somebody adds one convenient dependency. So:
 *
 * <ol>
 *   <li>the Java SDK modules reach no Pravaha module but {@code pravaha-api} (and the Flight SDK
 *       {@code pravaha-sdk-java}) at compile or runtime scope, directly or transitively; test scope
 *       is theirs to use, since the Flight SDK's tests start a real server;
 *   <li>no server or engine module -- everything but the two clients, {@code pravaha-cli} and
 *       {@code pravaha-it} -- names an SDK module at any scope, or reaches one transitively;
 *   <li>the server's executable jar, when one has been built, carries no SDK jar and no SDK class;
 *   <li>the Python package imports with the standard library alone (its extras -- pyarrow,
 *       cryptography -- are imported lazily, where they are used) and never imports the console.
 * </ol>
 *
 * <p>The dependency graph is read from the POMs rather than resolved, which is all it needs: every
 * edge in question is between modules of this reactor. tools/build-sdk.sh checks the other half
 * of the promise, that the SDKs build without the server.
 */
class SdkIndependenceTest {

    private static final String GROUP = "com.ash.messaging";
    private static final Set<String> SDK_MODULES = Set.of("pravaha-sdk-java", "pravaha-sdk-java-flight");
    /** What a Java SDK module may reach inside this project, outside test scope. */
    private static final Set<String> SDK_MAY_REACH = Set.of("pravaha-api", "pravaha-sdk-java");
    /** The clients of the SDK that live in this tree: a command-line tool and the test suite. */
    private static final Set<String> CLIENTS = Set.of("pravaha-cli", "pravaha-it");

    private static final Pattern CONSOLE_IMPORT =
            Pattern.compile("^\\s*(from|import)\\s+console(\\.|\\s|$)", Pattern.MULTILINE);

    @Test
    void theJavaSdkReachesNothingOfTheServer() throws Exception {
        Map<String, Module> modules = modules();
        assertThat(modules).as("the reactor's modules were read").containsKeys("pravaha-server", "pravaha-api");
        assertThat(modules.keySet()).containsAll(SDK_MODULES);

        Map<String, Set<String>> offending = new LinkedHashMap<>();
        for (String sdk : SDK_MODULES) {
            Set<String> reached = shippedClosure(sdk, modules);
            reached.removeAll(SDK_MAY_REACH);
            if (!reached.isEmpty()) {
                offending.put(sdk, reached);
            }
        }

        assertThat(offending)
                .as(
                        "a Java SDK module depends, at compile or runtime scope, on a Pravaha module other "
                                + "than %s. A client takes the SDK into its own application; whatever the SDK "
                                + "reaches, the client ships. Server modules belong at test scope only",
                        SDK_MAY_REACH)
                .isEmpty();
    }

    @Test
    void noServerModuleDependsOnAnSdk() throws Exception {
        Map<String, Module> modules = modules();
        List<String> offending = new ArrayList<>();
        for (Module module : modules.values()) {
            if (SDK_MODULES.contains(module.artifactId) || CLIENTS.contains(module.artifactId)) {
                continue;
            }
            for (Dependency dep : module.dependencies) {
                if (SDK_MODULES.contains(dep.artifactId)) {
                    offending.add(module.artifactId + " -> " + dep.artifactId + " (" + dep.scope + ")");
                }
            }
            Set<String> reached = shippedClosure(module.artifactId, modules);
            reached.retainAll(SDK_MODULES);
            for (String sdk : reached) {
                offending.add(module.artifactId + " -> ... -> " + sdk + " (transitively)");
            }
        }

        assertThat(offending)
                .as(
                        "a server or engine module depends on a client SDK. The SDK is built and "
                                + "released for clients, separately; the server must not bundle it. Only %s "
                                + "may use it",
                        CLIENTS)
                .isEmpty();
    }

    @Test
    void theServerJarCarriesNoSdkClass() throws IOException {
        Path target = repoRoot().resolve("pravaha-server/target");
        List<Path> jars;
        try (Stream<Path> files = Files.exists(target) ? Files.list(target) : Stream.empty()) {
            jars = files.filter(p -> p.getFileName().toString().matches("pravaha-server-.*-app\\.jar"))
                    .toList();
        }
        // The graph check above covers a tree where the server has not been packaged yet.
        assumeTrue(!jars.isEmpty(), "pravaha-server has not been packaged; the dependency graph is checked instead");

        List<String> offending = new ArrayList<>();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream()
                        .map(e -> e.getName())
                        .filter(name -> name.startsWith("BOOT-INF/lib/pravaha-sdk-")
                                || name.contains("com/ash/messaging/pravaha/sdk/"))
                        .limit(5)
                        .forEach(name -> offending.add(jar.getFileName() + "!/" + name));
            }
        }
        assertThat(offending)
                .as("the server's executable jar contains the client SDK; the SDK is a separate "
                        + "artefact and the server must not bundle it")
                .isEmpty();
    }

    @Test
    void thePythonSdkNeverImportsTheConsole() throws IOException {
        Path pkg = repoRoot().resolve("sdk/python/pravaha");
        List<String> offending = new ArrayList<>();
        try (Stream<Path> files = Files.walk(pkg)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".py")).toList()) {
                if (CONSOLE_IMPORT
                        .matcher(Files.readString(file, StandardCharsets.UTF_8))
                        .find()) {
                    offending.add(repoRoot().relativize(file).toString());
                }
            }
        }
        assertThat(offending)
                .as("the Python SDK imports the console. The console is a client of the SDK, never "
                        + "the other way round: the wheel ships without it")
                .isEmpty();
    }

    /**
     * Imports every module of the package in an interpreter that can see the standard library and
     * nothing else ({@code -I -S}: no site-packages, no user site, no environment), with a finder
     * that names anything else it is asked for. An optional import inside {@code try/except
     * ImportError} passes, which is exactly what an extra is, and so does the extra's own module
     * ({@code pravaha.client}, the Flight transport) refusing to import with an ImportError that
     * names the extra to install; any other failure, an unguarded import above all, fails here.
     */
    @Test
    void thePythonSdkImportsWithTheStandardLibraryAlone() throws Exception {
        String python = pythonWithStdlibNames();
        assumeTrue(python != null, "no python3 (3.10 or later) on the PATH");

        String script = """
                        import importlib, importlib.abc, pkgutil, sys, traceback
                        sys.path.insert(0, sys.argv[1])
                        allowed = set(sys.stdlib_module_names) | {'pravaha'}
                        asked = set()
                        class Outside(importlib.abc.MetaPathFinder):
                            def find_spec(self, name, path=None, target=None):
                                top = name.split('.')[0]
                                if top not in allowed:
                                    asked.add(top)
                                    raise ModuleNotFoundError('outside the standard library: ' + name, name=name)
                                return None
                        sys.meta_path.insert(0, Outside())
                        failed = []
                        import pravaha
                        for info in pkgutil.walk_packages(pravaha.__path__, 'pravaha.'):
                            try:
                                importlib.import_module(info.name)
                            except ImportError as e:
                                # The extra's own module (pravaha.client is the Flight transport) may refuse
                                # to import without it, as long as it names the extra to install.
                                if 'pravaha[' in str(e):
                                    print('EXTRA ' + info.name)
                                else:
                                    failed.append(info.name + ': ' + repr(e))
                            except BaseException:
                                failed.append(info.name + ': ' + traceback.format_exc(limit=2).strip().splitlines()[-1])
                        for f in failed:
                            print('FAILED ' + f)
                        print('ASKED ' + ','.join(sorted(asked)))
                        sys.exit(1 if failed else 0)\
                        """;

        Process process = new ProcessBuilder(
                        python,
                        "-I",
                        "-S",
                        "-c",
                        script,
                        repoRoot().resolve("sdk/python").toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue())
                .as(
                        "every module of the Python SDK imports with the standard library alone; a "
                                + "dependency belongs behind an extra (pyproject.toml) and is imported where "
                                + "it is used, inside try/except ImportError. Output:%n%s",
                        output)
                .isZero();
    }

    // ---- the reactor, read from its POMs ----

    private record Dependency(String artifactId, String scope) {}

    private record Module(String artifactId, List<Dependency> dependencies) {}

    /** Every module of the reactor by artifactId, with its Pravaha dependencies (profiles included). */
    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    private static Map<String, Module> modules() throws Exception {
        Map<String, Module> modules = new LinkedHashMap<>();
        Deque<Path> todo = new ArrayDeque<>();
        todo.add(repoRoot());
        while (!todo.isEmpty()) {
            Path dir = todo.removeFirst();
            Document pom = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(dir.resolve("pom.xml").toFile());
            Element project = pom.getDocumentElement();
            String artifactId = childText(project, "artifactId");
            List<Dependency> deps = new ArrayList<>();
            for (Element dependencies : descendants(project, "dependencies")) {
                // The project's and its profiles' own. dependencyManagement pins versions and declares
                // no edge; a plugin's dependencies are the build's, not the artifact's.
                String owner = dependencies.getParentNode().getNodeName();
                if (!"project".equals(owner) && !"profile".equals(owner)) {
                    continue;
                }
                for (Element dep : children(dependencies, "dependency")) {
                    if (GROUP.equals(childText(dep, "groupId"))) {
                        String scope = childText(dep, "scope");
                        deps.add(new Dependency(childText(dep, "artifactId"), scope == null ? "compile" : scope));
                    }
                }
            }
            modules.put(artifactId, new Module(artifactId, deps));
            for (Element modulesElement : children(project, "modules")) {
                for (Element module : children(modulesElement, "module")) {
                    todo.add(dir.resolve(module.getTextContent().trim()).normalize());
                }
            }
        }
        return modules;
    }

    /** What a module ships with: its compile, runtime and provided dependencies, transitively. */
    private static Set<String> shippedClosure(String artifactId, Map<String, Module> modules) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(artifactId));
        while (!todo.isEmpty()) {
            Module module = modules.get(todo.removeFirst());
            if (module == null) {
                continue;
            }
            for (Dependency dep : module.dependencies) {
                if (!"test".equals(dep.scope) && seen.add(dep.artifactId)) {
                    todo.add(dep.artifactId);
                }
            }
        }
        return seen;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                found.add(e);
            }
        }
        return found;
    }

    private static List<Element> descendants(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList all = parent.getElementsByTagName(name);
        for (int i = 0; i < all.getLength(); i++) {
            found.add((Element) all.item(i));
        }
        return found;
    }

    private static @Nullable String childText(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }

    /** python3, if it is on the PATH and new enough to list its own standard library (3.10). */
    private static @Nullable String pythonWithStdlibNames() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "import sys; sys.stdlib_module_names")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? "python3" : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }
}

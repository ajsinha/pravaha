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
package com.ash.messaging.pravaha.it.debug;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.DebugSessions;
import com.ash.messaging.pravaha.registry.DebugStep;
import com.ash.messaging.pravaha.registry.FixtureExport;

import static com.ash.messaging.pravaha.it.debug.DebugTestSupport.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W10's acceptance, as a test: a seeded bug is found by replay and exported as a passing JUnit
 * fixture (ADR-048, design section 16.4).
 *
 * <p>The claim "the exported test compiles and passes when dropped into pravaha-it" is worth
 * nothing asserted. So this test asserts it by doing it: it exports a session, compiles the
 * generated source with the JDK's own compiler against this module's classpath, loads the class
 * and invokes its {@code @Test} method. A fixture that did not compile fails here with the
 * compiler's diagnostics; one that compiled and failed its own assertion fails here with that
 * assertion.
 *
 * <p>The seeded bug is the one the design's §16.4 walks through: a user whose running total goes
 * negative because a retraction arrives for a row that was never inserted at this position. The
 * session steps to the predicate that catches it, and the fixture is the permanent record.
 */
@Timeout(300)
final class DebugFixtureExportTest {

    private static final List<String> BEFORE = List.of(
            DebugTestSupport.row("ann", 100, DebugTestSupport.SECOND),
            DebugTestSupport.row("bob", 250, DebugTestSupport.SECOND));

    /** The seeded incident: a correction of -900 arrives and the running total goes negative. */
    private static final List<String> INCIDENT = List.of(
            DebugTestSupport.row("ann", 20, 2 * DebugTestSupport.SECOND),
            DebugTestSupport.row("bob", 40, 2 * DebugTestSupport.SECOND),
            DebugTestSupport.row("cat", 5, 3 * DebugTestSupport.SECOND),
            DebugTestSupport.row("bob", -900, 3 * DebugTestSupport.SECOND),
            DebugTestSupport.row("ann", 3, 4 * DebugTestSupport.SECOND));

    @Test
    void aSessionExportsAJunitFixtureThatCompilesAndPasses(@TempDir Path dir) throws Exception {
        String source;
        String className;
        try (DebugTestSupport engine =
                new DebugTestSupport(dir, DebugTestSupport.TOTAL, List.of(0), BEFORE, INCIDENT)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();

            // Found by replay: step until somebody's total goes negative, which is the whole of
            // the design's "stop when group user_42's SUM goes negative".
            DebugStep caught = sessions.step(id, DebugStep.Request.until("total", "<", "0"), OWNER);
            assertThat(caught.stopped()).contains("total < 0");
            assertThat(caught.rowsConsumed())
                    .as("the fourth appended row is the one that takes bob under")
                    .isEqualTo(4);
            assertThat(sessions.view(id, OWNER).stream().map(Object::toString))
                    .as("350 before the fork, plus 20 + 40 + 5 - 900")
                    .anyMatch(row -> row.contains("-485"));

            FixtureExport export = sessions.export(id, "bob goes negative", OWNER);
            className = export.className();
            source = export.source();
            assertThat(className).isEqualTo("BobGoesNegativeFixtureTest");
            assertThat(export.path())
                    .isEqualTo("pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/" + className + ".java");
            assertThat(export.files()).hasSize(1);

            // Everything the fixture is supposed to carry.
            assertThat(source)
                    .as("the SQL")
                    .contains(DebugTestSupport.TOTAL)
                    .as("the schema of every stream it reads")
                    .contains("StreamSchema.builder(\"txn\")")
                    .contains(".field(\"user_id\", Types.string())")
                    .contains(".field(\"amount\", Types.int64())")
                    .as("the rows it consumed, in order, with their weights")
                    .contains("harness.row(\"txn\", 1L,")
                    .contains("\"bob\", -900L")
                    .as("and the expected view")
                    .contains("private static final List<String> EXPECTED");
            sessions.end(id, OWNER);
        }

        // Compile it exactly as pravaha-it would, then run it.
        Path generated = Files.createDirectories(dir.resolve("gen/com/ash/messaging/pravaha/it/fixtures"));
        Path sourceFile = generated.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        compile(sourceFile, classes);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, DebugFixtureExportTest.class.getClassLoader())) {
            Class<?> fixture = loader.loadClass(FixtureExport.PACKAGE + "." + className);
            Method test = java.util.Arrays.stream(fixture.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Test.class))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the generated fixture has no @Test method"));
            test.setAccessible(true);
            // The generated class is package-private, as every test in this repository is, so its
            // constructor has to be opened before it can be built from another package. JUnit does
            // the same thing; here it is done by hand because the point is to run the method, not
            // to start a second engine.
            java.lang.reflect.Constructor<?> constructor = fixture.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            try {
                test.invoke(instance);
            } catch (java.lang.reflect.InvocationTargetException e) {
                StringWriter trace = new StringWriter();
                e.getCause().printStackTrace(new PrintWriter(trace));
                throw new AssertionError(
                        "the exported fixture compiled and then failed its own assertion, so it is not a "
                                + "regression test anybody could commit:\n" + trace,
                        e.getCause());
            }
        }
    }

    @Test
    void afixtureNameThatCannotBeAClassIsRefused(@TempDir Path dir) throws Exception {
        try (DebugTestSupport engine =
                new DebugTestSupport(dir, DebugTestSupport.TOTAL, List.of(0), BEFORE, INCIDENT)) {
            DebugSessions sessions = engine.registry().debugSessions();
            String id = sessions.fork("spend", engine.checkpoint(), OWNER).id();
            sessions.step(id, DebugStep.Request.rows(2), OWNER);
            assertThatThrownBy(() -> sessions.export(id, "   ", OWNER))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8015")
                    .hasMessageContaining("does not make a Java class name");
            sessions.end(id, OWNER);
        }
    }

    /** Compiles one generated file against this test run's own classpath, into {@code classes}. */
    private static void compile(Path source, Path classes) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("the JDK's compiler: this test needs a JDK rather than a JRE, which the build already is")
                .isNotNull();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of(
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), "-proc:none"));
            boolean compiled = compiler.getTask(
                            null, files, diagnostics, options, null, files.getJavaFileObjects(source.toFile()))
                    .call();
            StringBuilder said = new StringBuilder();
            diagnostics
                    .getDiagnostics()
                    .forEach(d -> said.append(d.getMessage(Locale.ROOT)).append('\n'));
            assertThat(compiled)
                    .as(
                            "the exported fixture must compile when dropped into pravaha-it:\n%s\n--- source ---\n%s",
                            said, Files.readString(source))
                    .isTrue();
        }
    }
}

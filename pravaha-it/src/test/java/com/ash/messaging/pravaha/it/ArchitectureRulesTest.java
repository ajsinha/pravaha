/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design's structural invariants, enforced mechanically.
 *
 * <p>Each of these is a rule the architecture depends on and that code review reliably fails to
 * catch: they are violated one small commit at a time, and each individual violation looks
 * reasonable. Only a machine notices the drift.
 */
class ArchitectureRulesTest {

    private static JavaClasses engine;

    @BeforeAll
    static void importEngineClasses() {
        // Scans the sibling modules' compiled output rather than their installed jars, so the rules
        // always run against the code in the working tree.
        Path root = repoRoot();
        try (Stream<Path> modules = Files.list(root)) {
            List<Path> classDirs = modules.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith("pravaha-"))
                    .map(d -> d.resolve("target/classes"))
                    .filter(Files::isDirectory)
                    .toList();
            engine = new ClassFileImporter().importPaths(classDirs);
        } catch (IOException e) {
            throw new IllegalStateException("cannot enumerate modules under " + root, e);
        }
    }

    @Test
    void theRulesActuallyHaveClassesToCheck() {
        // Without this, a path bug would make every rule below pass vacuously -- which reads as a
        // green architecture suite while enforcing nothing at all.
        assertThat(engine).hasSizeGreaterThan(20);
        assertThat(engine.stream().map(c -> c.getPackageName()))
                .anyMatch(p -> p.startsWith("com.ash.messaging.pravaha.api"))
                .anyMatch(p -> p.startsWith("com.ash.messaging.pravaha.common"));
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

    @Test
    void engineCoreContainsNoSpring() {
        // Design section 22.1. An embedded engine inherits its host application's Spring version; the core
        // dragging in its own would forfeit embeddability, which is a moat the product is sold on.
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(
                        "com.ash.messaging.pravaha.api..",
                        "com.ash.messaging.pravaha.common..",
                        "com.ash.messaging.pravaha.algebra..",
                        "com.ash.messaging.pravaha.catalog..",
                        "com.ash.messaging.pravaha.sql..",
                        "com.ash.messaging.pravaha.runtime..",
                        "com.ash.messaging.pravaha.state..",
                        "com.ash.messaging.pravaha.connect..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..")
                .because("Spring is a bootstrap layer above the engine, never inside it (design section 22.1)");
        rule.check(engine);
    }

    @Test
    void nothingIsJavaSerializable() {
        // Java serialization is slow, insecure and versions badly. The 1.0 draft used it for the
        // record type; it must not reappear as a transport anywhere.
        //
        // Two carve-outs, both unavoidable rather than tolerated: java.lang.Enum implements
        // Serializable, so every enum inherits it, and Throwable does the same for exceptions.
        // Neither is a choice this codebase makes.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.ash.messaging.pravaha.api..")
                .and()
                .areNotEnums()
                .and()
                .haveSimpleNameNotEndingWith("Exception")
                .should()
                .implement(java.io.Serializable.class)
                .because("Java serialization is banned as a transport (design section 8.1)");
        rule.check(engine);
    }

    @Test
    void theApiModuleDependsOnNothingButTheJdk() {
        // Mirrors the maven-enforcer rule, but catches a violation at the class level rather than
        // only at the dependency level -- a shaded or relocated class would slip past the POM check.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.ash.messaging.pravaha.api..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("com.ash.messaging.pravaha.api..", "java..", "javax..")
                .because(
                        "pravaha-api is the plugin-facing contract and must stay dependency-free (design section 7.2)");
        rule.check(engine);
    }

    @Test
    void onlyTheMemoryPackageNamesALowLevelMemoryApi() {
        // The whole value of the MemoryAccess seam (design section 4.6) is that swapping Agrona for FFM is a
        // configuration change. That holds only while nothing else reaches around it.
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("com.ash.messaging.pravaha.common.memory..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.agrona..", "sun.misc..", "jdk.internal..")
                .because("low-level memory APIs are named in exactly one package (design section 4.6)");
        rule.check(engine);
    }
}

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

    /**
     * Everything the embedded engine is built from. The enforcer rule in pravaha-embedded refuses a
     * Spring <em>dependency</em>; this refuses a Spring <em>import</em>, which a dependency arriving
     * some other way -- a shaded jar, a provided scope, a sibling module -- would otherwise let through.
     */
    private static final String[] SPRING_FREE = {
        "com.ash.messaging.pravaha.api..",
        "com.ash.messaging.pravaha.common..",
        "com.ash.messaging.pravaha.algebra..",
        "com.ash.messaging.pravaha.catalog..",
        "com.ash.messaging.pravaha.sql..",
        "com.ash.messaging.pravaha.runtime..",
        "com.ash.messaging.pravaha.state..",
        "com.ash.messaging.pravaha.connect..",
        // What the embedded engine assembles at start (ADR-019, ADR-020): the engine itself, the
        // registry it hosts, the views it serves, the policy types it runs under, and the plugin
        // bindings it shares with the server.
        "com.ash.messaging.pravaha.embedded..",
        "com.ash.messaging.pravaha.registry..",
        "com.ash.messaging.pravaha.serving..",
        "com.ash.messaging.pravaha.security..",
        "com.ash.messaging.pravaha.bindings.."
    };

    @Test
    void engineCoreContainsNoSpring() {
        // Design section 22.1. An embedded engine inherits its host application's Spring version; the core
        // dragging in its own would forfeit embeddability, which is a moat the product is sold on.
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(SPRING_FREE)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..")
                .because("Spring is a bootstrap layer above the engine, never inside it (design section 22.1)");
        rule.check(engine);
    }

    @Test
    void theSpringFreeRuleSeesTheEmbeddedEngineAndItsBindings() {
        // The packages added to the rule above, present in what it checks: a rule over a package that
        // was never imported passes while enforcing nothing.
        for (String pkg : List.of("embedded", "registry", "serving", "security", "bindings")) {
            String prefix = "com.ash.messaging.pravaha." + pkg;
            assertThat(engine.stream().map(c -> c.getPackageName()))
                    .as("classes under %s", prefix)
                    .anyMatch(p -> p.startsWith(prefix));
        }
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

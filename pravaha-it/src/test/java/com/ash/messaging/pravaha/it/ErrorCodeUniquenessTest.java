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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code PRV-} code means exactly one thing.
 *
 * <p>A code is the stable part of an error -- it goes in a runbook, a log filter, a support ticket
 * and a documentation page, and all of those outlive the message text. Two failures answering to one
 * number makes every conversation about it start with "which 5001?", and the documentation cannot be
 * written at all.
 *
 * <p>This found a real collision the first time it ran: the registry's codes were allocated into
 * 5xxx, which belongs to plugins, so {@code REGISTRY_NAME_IN_USE} and {@code PLUGIN_MISSING_SETTING}
 * shared a number.
 */
class ErrorCodeUniquenessTest {

    private static final Pattern DECLARATION = Pattern.compile("new ErrorCode\\(\\s*(\\d+)\\s*,\\s*\"([A-Z0-9_]+)\"");

    /** The subsystem each thousand belongs to. A code outside these is unallocated. */
    private static final Map<Integer, String> RANGES = Map.of(
            1, "config",
            2, "sql",
            3, "runtime and codegen",
            4, "state, backfill and serving",
            5, "plugins",
            6, "the Flight gateway",
            7, "security",
            8, "the query registry",
            9, "cluster coordination");

    @Test
    void noTwoFailuresShareACode() throws IOException {
        Map<Integer, List<String>> byCode = declarations();

        List<String> collisions = new ArrayList<>();
        byCode.forEach((code, names) -> {
            List<String> distinct = names.stream().distinct().toList();
            if (distinct.size() > 1) {
                collisions.add("PRV-" + code + " is used for " + distinct);
            }
        });

        assertThat(collisions)
                .as("a code is what goes in a runbook and a support ticket; it has to mean one thing")
                .isEmpty();
    }

    @Test
    void everyCodeIsInAnAllocatedRange() throws IOException {
        List<String> stray = new ArrayList<>();
        declarations().forEach((code, names) -> {
            int range = code / 1000;
            if (!RANGES.containsKey(range)) {
                stray.add("PRV-" + code + " " + names + " is outside every allocated range " + RANGES);
            }
        });

        assertThat(stray).isEmpty();
    }

    /** Code to the names declared for it, across every module's main sources. */
    private static Map<Integer, List<String>> declarations() throws IOException {
        Map<Integer, List<String>> byCode = new LinkedHashMap<>();
        Path root = repoRoot();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                Matcher matcher = DECLARATION.matcher(Files.readString(file));
                while (matcher.find()) {
                    byCode.computeIfAbsent(Integer.parseInt(matcher.group(1)), ignored -> new ArrayList<>())
                            .add(matcher.group(2));
                }
            }
        }
        assertThat(byCode)
                .as("no error codes found at all -- the pattern has drifted")
                .isNotEmpty();
        return byCode;
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docs/adr"))) {
            path = path.getParent();
        }
        return path;
    }
}

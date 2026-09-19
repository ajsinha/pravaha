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
package com.ash.messaging.pravaha.spring.test;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

/**
 * Boot's own test bootstrap, with {@link PravahaTest}'s properties -- and the three paths the slice
 * keeps out of a test unless the test itself names them.
 */
class PravahaTestContextBootstrapper extends SpringBootTestContextBootstrapper {

    /** Paths an application's configuration may set, and a test should not write to by accident. */
    static final List<String> CLEARED =
            List.of("pravaha.checkpoint.directory", "pravaha.registry.journal", "pravaha.dlq.directory");

    /** Read by {@link PravahaTestAutoConfiguration}: whether to checkpoint into a temporary directory. */
    static final String CHECKPOINTS = "pravaha.test.checkpoints";

    @Override
    protected String[] getProperties(Class<?> testClass) {
        MergedAnnotation<PravahaTest> annotation = MergedAnnotations.from(
                        testClass, SearchStrategy.INHERITED_ANNOTATIONS)
                .get(PravahaTest.class);
        String[] declared = annotation.getValue("properties", String[].class).orElse(new String[0]);
        List<String> properties = new ArrayList<>(List.of(declared));
        for (String key : CLEARED) {
            if (!sets(declared, key)) {
                // Empty, which the auto-configuration reads as unset, and which outranks
                // application.yaml: inlined test properties are the highest property source.
                properties.add(key + "=");
            }
        }
        properties.add(CHECKPOINTS + "="
                + annotation.getValue("checkpoints", Boolean.class).orElse(false));
        return properties.toArray(String[]::new);
    }

    /** Whether one of {@code declared} sets {@code key}, in any of the spellings Spring accepts. */
    static boolean sets(String[] declared, String key) {
        for (String property : declared) {
            String trimmed = property.strip();
            if (trimmed.startsWith(key)
                    && trimmed.length() > key.length()
                    && "=: ".indexOf(trimmed.charAt(key.length())) >= 0) {
                return true;
            }
        }
        return false;
    }
}

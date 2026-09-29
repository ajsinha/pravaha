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
package com.ash.messaging.pravaha.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.codegen.FilterProjectStageGenerator;
import com.ash.messaging.pravaha.runtime.exec.GeneratedChains;

/**
 * {@code pravaha.codegen.enabled}: whether a node offers its filter and projection chains to the
 * code generator (C-7, CODEGENPROP-1).
 *
 * <p>Under Spring the value is bound like every other {@code pravaha.*} key, so it can be set in
 * {@code application.yaml}, as an environment variable ({@code PRAVAHA_CODEGEN_ENABLED}) or as a
 * JVM system property; Spring's usual order applies, and a {@code -Dpravaha.codegen.enabled} on
 * the JVM wins over the YAML file. A node built without Spring ({@link PravahaNode#builder()})
 * reads the system property alone, unless the builder is told otherwise. Default on.
 */
final class CodegenSwitch {

    private static final Logger log = LoggerFactory.getLogger(CodegenSwitch.class);

    private CodegenSwitch() {}

    /** The system property's value, or {@code true} when it is not set: the default without Spring. */
    static boolean fromSystemProperty() {
        return Boolean.parseBoolean(System.getProperty(PravahaNode.CODEGEN_PROPERTY, "true"));
    }

    /**
     * Installs the generator, or takes it out, for every query registered from now on. Process-wide,
     * and so set before the first query registers: a lane offers its chains when it compiles.
     */
    static void apply(boolean enabled) {
        if (enabled) {
            FilterProjectStageGenerator.install();
        } else {
            GeneratedChains.install(null);
            log.info("{}=false: every registered query runs interpreted", PravahaNode.CODEGEN_PROPERTY);
        }
    }
}

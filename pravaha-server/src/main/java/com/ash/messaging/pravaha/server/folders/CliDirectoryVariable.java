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

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;

/**
 * ENVCONFIGDIR-1. Refuses a {@code pravaha.config.dir} that came only from the environment variable
 * {@code PRAVAHA_CONFIG_DIR} -- the name the command line documented for its own directory (its token,
 * contexts and assistant configuration) until it became {@code PRAVAHA_CLI_DIR}.
 *
 * <p>Spring's relaxed binding reads that variable as {@code pravaha.config.dir}, and the system environment
 * outranks the YAML. So a shell that exported it for the command line and then started a server read its
 * streams, lookups, sinks, connections and AI settings from the command line's folder -- and, finding none
 * there, silently declared nothing.
 *
 * <p>Not refused: a value set by a higher-priority source (a {@code --pravaha.config.dir} argument, a
 * system property), or one a lower source (the YAML) sets to the same path -- either is a deliberate
 * choice, and the variable then changes nothing.
 */
public final class CliDirectoryVariable {

    /** The command line's old variable, which Spring binds to {@code pravaha.config.dir}. */
    public static final String VARIABLE = "PRAVAHA_CONFIG_DIR";

    private static final String PROPERTY = "pravaha.config.dir";

    /** Spring Boot's view over every other source; it holds nothing of its own. */
    private static final String ATTACHED = "configurationProperties";

    private CliDirectoryVariable() {}

    /** Throws {@code PRV-1069} when {@code pravaha.config.dir} is the variable's value and nothing else's. */
    public static void refuseIfAlone(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        PropertySource<?> system =
                configurable.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (system == null || !(system.getProperty(VARIABLE) instanceof String value) || value.isBlank()) {
            return;
        }
        boolean higher = true;
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source.getName().equals(system.getName())) { // names are unique in MutablePropertySources
                higher = false;
                continue;
            }
            if (ATTACHED.equals(source.getName())) {
                continue;
            }
            Object set = source.getProperty(PROPERTY);
            if (set != null && (higher || value.strip().equals(set.toString().strip()))) {
                return;
            }
        }
        throw new PravahaException(
                ConfigErrors.CONFIG_DIR_FROM_CLI_VARIABLE,
                "the environment variable " + VARIABLE + "=" + value + " is set, and Spring reads it as "
                        + PROPERTY + " -- so this node would take its streams, lookups, sinks, connections and "
                        + "AI settings from there. It is the command line's old name for its own directory. "
                        + "Unset it before starting the server (the command line now reads PRAVAHA_CLI_DIR), "
                        + "or, if the folder is meant, set " + PROPERTY + " explicitly: --" + PROPERTY + "="
                        + value + " or in the YAML.");
    }
}

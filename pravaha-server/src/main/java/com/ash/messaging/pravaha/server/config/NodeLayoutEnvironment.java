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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import com.ash.messaging.pravaha.common.config.NodeLayout;

/**
 * Puts {@link NodeLayout}'s conventions into the environment, after every configuration file and argument
 * has been read and before anything binds: {@code pravaha.registry.journal} becomes
 * {@code ${pravaha.data.dir}/registry.journal} when nothing else says where it is, and so on (ADR-064).
 *
 * <p>Only settings left unset or blank are supplied, so a value written anywhere -- YAML, an argument, an
 * environment variable -- wins. The data root is created here too, so the stores the convention places in
 * it can be opened, and a root that cannot be is refused ({@code PRV-1066}) before the node is built.
 */
public class NodeLayoutEnvironment implements EnvironmentPostProcessor, Ordered {

    /** The name of the property source holding what the layout supplied. */
    static final String SOURCE = "pravaha-node-layout";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> derived = NodeLayout.derived(environment::getProperty, NodeLayout.DEFAULT_DATA_DIR);
        NodeLayout.prepare(environment::getProperty, NodeLayout.DEFAULT_DATA_DIR);
        if (!derived.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, new LinkedHashMap<>(derived)));
        }
    }

    @Override
    public int getOrder() {
        // After Spring's own config-data processing, which reads application.yaml and the arguments.
        return Ordered.LOWEST_PRECEDENCE;
    }
}

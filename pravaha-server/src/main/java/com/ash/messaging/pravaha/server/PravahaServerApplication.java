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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

/**
 * The engine node.
 *
 * <p>Spring Boot is a <strong>bootstrap layer</strong> here, not a layer inside the engine
 * (ADR-019). It owns the HTTP surface and the engine's lifecycle; the engine itself carries no
 * Spring, which is what lets the same engine be embedded in a host application on whatever Spring
 * version that application already runs.
 */
@SpringBootApplication
public class PravahaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PravahaServerApplication.class, args);
    }

    /**
     * The engine, as one bean.
     *
     * <p>Configuration is bound from Spring's environment into the engine's own immutable
     * {@code Configuration} type -- one configuration model, two ways of filling it, never two
     * sources of truth (design section 22.5).
     */
    @Bean
    public PravahaEngine pravahaEngine(Environment environment) {
        Configuration configuration = Configuration.builder()
                .set("pravaha.node.id", environment.getProperty("pravaha.node.id", "pravaha-node-01"))
                .build();
        return PravahaEngine.create(configuration);
    }

    /**
     * Starts the engine after the web layer can serve health, and stops it before the web layer
     * goes away.
     *
     * <p>Ordered explicitly through {@link SmartLifecycle} rather than left to Spring's bean
     * destruction order: lanes must drain before the HTTP surface stops accepting, and bean order
     * does not express that.
     */
    @Bean
    public SmartLifecycle pravahaLifecycle(PravahaEngine engine) {
        return new SmartLifecycle() {
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 1000;
            }

            @Override
            public void start() {
                engine.start();
            }

            @Override
            public void stop() {
                engine.stop();
            }

            @Override
            public boolean isRunning() {
                return engine.state() == com.ash.messaging.pravaha.api.EngineState.RUNNING;
            }
        };
    }
}

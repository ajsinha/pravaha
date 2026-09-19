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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.spring.PravahaAutoConfiguration;
import com.ash.messaging.pravaha.spring.PravahaListenerProcessor;
import com.ash.messaging.pravaha.spring.PravahaProperties;

/**
 * What {@link PravahaTest} adds to the starter's auto-configuration: a {@link PravahaTester}, and a
 * temporary checkpoint directory when the test asks for one.
 *
 * <p>Imported by {@link PravahaTest} alone. It is deliberately absent from the starter's {@code
 * AutoConfiguration.imports}, so an application never gets a tester, or a temporary directory, by
 * having the starter on its classpath.
 */
@AutoConfiguration(after = PravahaAutoConfiguration.class)
public class PravahaTestAutoConfiguration {

    @Bean
    @ConditionalOnBean(PravahaEngine.class)
    @ConditionalOnMissingBean
    public PravahaTester pravahaTester(PravahaEngine engine, ObjectProvider<PravahaListenerProcessor> listeners) {
        return new PravahaTester(engine, listeners.getIfAvailable());
    }

    /** Static, as a {@code BeanPostProcessor} must be: it changes the properties the engine is built from. */
    @Bean
    static TemporaryCheckpoints pravahaTemporaryCheckpoints() {
        return new TemporaryCheckpoints();
    }

    /**
     * Points {@code pravaha.checkpoint.directory} at a fresh temporary directory when the test asked
     * for checkpoints and named no directory, and deletes it when the context closes -- after the
     * engine, which it was created before.
     */
    static final class TemporaryCheckpoints implements BeanPostProcessor, EnvironmentAware, DisposableBean {

        private static final Log LOG = LogFactory.getLog(TemporaryCheckpoints.class);

        private Environment environment;
        private Path directory;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof PravahaProperties properties
                    && environment.getProperty(PravahaTestContextBootstrapper.CHECKPOINTS, Boolean.class, false)
                    && isBlank(properties.getCheckpoint().getDirectory())) {
                try {
                    directory = Files.createTempDirectory("pravaha-test-checkpoints-");
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot create a temporary checkpoint directory", e);
                }
                properties.getCheckpoint().setDirectory(directory.toString());
            }
            return bean;
        }

        /** The directory made, if one was. */
        Path directory() {
            return directory;
        }

        @Override
        public void destroy() {
            if (directory == null || !Files.exists(directory)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                LOG.warn("could not delete the temporary checkpoint directory " + directory, e);
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}

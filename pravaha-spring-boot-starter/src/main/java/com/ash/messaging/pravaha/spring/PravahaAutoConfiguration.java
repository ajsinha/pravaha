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
package com.ash.messaging.pravaha.spring;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.config.ConfigurationBuilder;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.spring.actuate.PravahaEndpoint;
import com.ash.messaging.pravaha.spring.actuate.PravahaHealthIndicator;

/**
 * An embedded {@link PravahaEngine} as a Spring bean (ADR-020, mode B of design section 22.2).
 *
 * <p><strong>A bootstrap, not a second engine.</strong> Everything here translates {@code pravaha.*}
 * into the engine's own configuration and hands it to {@link PravahaEngine#create}; the engine parses
 * its streams, bindings and queries itself, exactly as it would from a plain Java program. Nothing
 * under {@code com.ash.messaging.pravaha.embedded} knows this class exists, and the build refuses a
 * Spring dependency there (ADR-019).
 *
 * <p>The engine is started when its bean is created and closed when the context closes. Started
 * eagerly rather than as a {@code SmartLifecycle} so that a bean depending on it can register a
 * query in its own initialisation: a lifecycle start would come after every such bean, and each
 * would find an engine not yet running. {@code @PravahaListener} subscriptions, which need those
 * queries to exist, are the part that waits for the context to finish starting.
 */
@AutoConfiguration
@ConditionalOnClass(PravahaEngine.class)
@ConditionalOnProperty(prefix = "pravaha", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(PravahaProperties.class)
public class PravahaAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PravahaEngine pravahaEngine(
            PravahaProperties properties, ObjectProvider<PravahaEngineCustomizer> customizers) {
        PravahaEngine engine = PravahaEngine.create(configurationOf(properties));
        try {
            customizers.orderedStream().forEach(customizer -> customizer.customize(engine));
            engine.start();
        } catch (RuntimeException e) {
            // A bean whose factory method throws is never destroyed by the container, so whatever
            // start() had opened is released here or not at all.
            engine.close();
            throw e;
        }
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean
    public PravahaTemplate pravahaTemplate(PravahaEngine engine) {
        return new PravahaTemplate(engine);
    }

    /** Static, as a {@code BeanPostProcessor} must be: it exists before the beans it inspects. */
    @Bean
    public static PravahaListenerProcessor pravahaListenerProcessor(
            ObjectProvider<PravahaEngine> engine, ObjectProvider<PravahaProperties> properties) {
        return new PravahaListenerProcessor(engine, properties);
    }

    /**
     * The actuator contributions (ADR-020): a {@code pravaha} health indicator and a read-only
     * {@code pravaha} endpoint. Only when the application has Spring Boot Actuator -- the starter
     * does not bring it -- and each under Boot's own switches: the indicator obeys {@code
     * management.health.pravaha.enabled}, and the endpoint is created only once it is exposed, which
     * over the web means {@code management.endpoints.web.exposure.include} names it.
     *
     * <p>Nested, and guarded by class name, so an application without Actuator never loads a class
     * that refers to it.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.springframework.boot.actuate.health.HealthIndicator",
                "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint"
            })
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "pravahaHealthIndicator")
        @ConditionalOnEnabledHealthIndicator("pravaha")
        PravahaHealthIndicator pravahaHealthIndicator(
                PravahaEngine engine, ObjectProvider<PravahaListenerProcessor> listeners) {
            return new PravahaHealthIndicator(engine, listeners.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnAvailableEndpoint
        PravahaEndpoint pravahaEndpoint(PravahaEngine engine, ObjectProvider<PravahaListenerProcessor> listeners) {
            return new PravahaEndpoint(engine, listeners.getIfAvailable());
        }
    }

    /**
     * The engine's configuration, in the engine's own keys.
     *
     * <p>Streams, bindings and queries are passed through as {@code pravaha.streams.*} and friends
     * rather than declared by calling the engine, so the embedded engine's one parser of those blocks
     * is the only one -- a Spring application and a plain Java one given the same values get the same
     * engine.
     */
    static Configuration configurationOf(PravahaProperties properties) {
        ConfigurationBuilder builder = Configuration.builder();
        builder.set("pravaha.node.id", properties.getNode().getId());
        properties.getStreams().forEach((name, stream) -> {
            String prefix = "pravaha.streams." + name + ".";
            setIfPresent(builder, prefix + "schema", stream.getSchema());
            setIfPresent(builder, prefix + "event-time", stream.getEventTime());
            setIfPresent(builder, prefix + "out-of-orderness", nanos(stream.getOutOfOrderness()));
        });
        bindings(builder, "pravaha.sources.", properties.getSources());
        bindings(builder, "pravaha.lookups.", properties.getLookups());
        bindings(builder, "pravaha.sinks.", properties.getSinks());
        properties.getQueries().forEach((name, query) -> {
            String prefix = "pravaha.queries." + name + ".";
            setIfPresent(builder, prefix + "sql", query.getSql());
            setIfPresent(builder, prefix + "keys", String.join(",", query.getKeys()));
            setIfPresent(builder, prefix + "sink", query.getSink());
            setIfPresent(builder, prefix + "retention", nanos(query.getRetention()));
        });
        setIfPresent(
                builder, "pravaha.registry.journal", properties.getRegistry().getJournal());
        PravahaProperties.Checkpoint checkpoint = properties.getCheckpoint();
        setIfPresent(builder, "pravaha.checkpoint.directory", checkpoint.getDirectory());
        setIfPresent(builder, "pravaha.checkpoint.interval", nanos(checkpoint.getInterval()));
        builder.set("pravaha.checkpoint.keep", String.valueOf(checkpoint.getKeep()));
        setIfPresent(builder, "pravaha.checkpoint.timeout", nanos(checkpoint.getTimeout()));
        setIfPresent(builder, "pravaha.dlq.directory", properties.getDlq().getDirectory());
        setIfPresent(
                builder,
                "pravaha.watermark.idle-after",
                nanos(properties.getWatermark().getIdleAfter()));
        setIfPresent(
                builder,
                "pravaha.watermark.tick",
                nanos(properties.getWatermark().getTick()));
        PravahaProperties.Read read = properties.getServing().getRead();
        String reads = "pravaha.serving.read.";
        setIfPresent(builder, reads + "max-concurrent", stringOf(read.getMaxConcurrent()));
        setIfPresent(builder, reads + "max-queued", stringOf(read.getMaxQueued()));
        setIfPresent(builder, reads + "queue-timeout", nanos(read.getQueueTimeout()));
        setIfPresent(builder, reads + "tenant-share", stringOf(read.getTenantShare()));
        setIfPresent(builder, reads + "deadline", nanos(read.getDeadline()));
        return builder.build();
    }

    private static void bindings(
            ConfigurationBuilder builder, String block, java.util.Map<String, PravahaProperties.Binding> bindings) {
        bindings.forEach((name, binding) -> {
            setIfPresent(builder, block + name + ".plugin", binding.getPlugin());
            binding.getOptions().forEach((key, value) -> builder.set(block + name + ".options." + key, value));
        });
    }

    private static void setIfPresent(ConfigurationBuilder builder, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            builder.set(key, value);
        }
    }

    private static @Nullable String stringOf(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    private static @Nullable String nanos(@Nullable Duration duration) {
        return duration == null ? null : duration.toNanos() + "ns";
    }
}

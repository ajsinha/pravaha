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
package com.ash.messaging.pravaha.spring.actuate;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.spring.PravahaAutoConfiguration;
import com.ash.messaging.pravaha.spring.PravahaListener;
import com.ash.messaging.pravaha.spring.PravahaListenerProcessor;
import com.ash.messaging.pravaha.spring.test.PravahaTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actuator contributions: present only with Actuator, the endpoint only once exposed, the
 * health indicator under Boot's switch, and both reporting what the engine is doing.
 */
class PravahaActuatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
            .withPropertyValues(
                    "pravaha.node.id=actuated",
                    "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                    "pravaha.queries.big_txn.sql=SELECT user_id, amount FROM txn WHERE amount > 100",
                    "pravaha.queries.big_txn.keys=user_id");

    static class Failing {
        @PravahaListener(query = "big_txn")
        void on(java.util.Map<String, Object> row, boolean retraction) {
            throw new IllegalStateException("listener broke");
        }
    }

    @Test
    void theHealthIndicatorIsThereWithActuatorAndTheEndpointOnlyOnceExposed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PravahaHealthIndicator.class);
            assertThat(context.containsBean("pravahaHealthIndicator")).isTrue();
            assertThat(context)
                    .as("not exposed, so not created: Boot's default web exposure is health alone")
                    .doesNotHaveBean(PravahaEndpoint.class);
        });
        runner.withPropertyValues("management.endpoints.web.exposure.include=pravaha")
                .run(context -> assertThat(context).hasSingleBean(PravahaEndpoint.class));
        runner.withPropertyValues(
                        "management.endpoints.web.exposure.include=*",
                        "management.endpoints.web.exposure.exclude=pravaha")
                .run(context -> assertThat(context).doesNotHaveBean(PravahaEndpoint.class));
        runner.withPropertyValues("management.health.pravaha.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PravahaHealthIndicator.class));
    }

    @Test
    void withoutActuatorNeitherIsCreatedAndTheEngineStillIs() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .withPropertyValues("management.endpoints.web.exposure.include=pravaha")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PravahaEngine.class);
                    assertThat(context).doesNotHaveBean(PravahaHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(PravahaEndpoint.class);
                });
    }

    @Test
    void theEndpointHasNoOperationThatChangesAnything() {
        for (Method method : PravahaEndpoint.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(WriteOperation.class)
                            || method.isAnnotationPresent(DeleteOperation.class))
                    .as("%s is read-only", method.getName())
                    .isFalse();
        }
        assertThat(Arrays.stream(PravahaEndpoint.class.getDeclaredMethods())
                        .filter(m -> m.isAnnotationPresent(
                                org.springframework.boot.actuate.endpoint.annotation.ReadOperation.class)))
                .hasSize(2);
    }

    @Test
    void theEndpointDescribesEachQueryItsSinkAndItsListeners(@TempDir Path dir) {
        runner.withPropertyValues(
                        "management.endpoints.web.exposure.include=pravaha",
                        "pravaha.sinks.large.plugin=filesystem",
                        "pravaha.sinks.large.options.path=" + dir.resolve("large.csv"),
                        "pravaha.sinks.large.options.schema=user_id:STRING,amount:INT64",
                        "pravaha.queries.big_txn.sink=large")
                .withBean(Failing.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    new PravahaTester(
                                    context.getBean(PravahaEngine.class),
                                    context.getBean(PravahaListenerProcessor.class))
                            .push("txn", new Object[] {"u1", 300L})
                            .awaitListeners();

                    PravahaEndpoint endpoint = context.getBean(PravahaEndpoint.class);
                    PravahaEndpoint.EngineDescriptor engine = endpoint.engine();
                    assertThat(engine.instanceId()).isEqualTo("actuated");
                    assertThat(engine.state()).isEqualTo("RUNNING");
                    assertThat(engine.queries()).containsOnlyKeys("big_txn");

                    PravahaEndpoint.QueryDescriptor query = endpoint.query("big_txn");
                    assertThat(query.state()).isEqualTo("RUNNING");
                    assertThat(query.lane()).isEqualTo("own");
                    assertThat(query.rowsIn()).isEqualTo(1);
                    assertThat(query.sql()).contains("amount > 100");
                    assertThat(query.failure()).isNull();
                    assertThat(query.feed().state())
                            .as("nothing is bound: the application pushes its rows")
                            .isEqualTo("NONE");
                    assertThat(query.watermarkLagSeconds())
                            .as("no event time, so no lag -- not a lag of zero")
                            .isNull();
                    assertThat(query.sink().name()).isEqualTo("large");
                    assertThat(query.sink().guarantee()).startsWith("at-least-once");
                    assertThat(query.sink().failure()).isNull();
                    assertThat(query.listeners()).singleElement().satisfies(listener -> {
                        assertThat(listener.listener()).endsWith(".on");
                        assertThat(listener.running()).isTrue();
                        assertThat(listener.failures()).isEqualTo(1);
                        assertThat(listener.delivered()).isZero();
                        assertThat(listener.pending()).isZero();
                        assertThat(listener.lastFailure()).contains("listener broke");
                    });

                    assertThat(endpoint.query("nope")).as("a 404 over the web").isNull();

                    Health health =
                            context.getBean(PravahaHealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry("instanceId", "actuated")
                            .containsEntry("queries", 1)
                            .containsEntry("failedQueries", 0)
                            .containsEntry("listeners", 1)
                            .containsEntry("listenerFailures", 1L);
                });
    }

    @Test
    void aSourceThatStopsIsInTheEndpointAndDegradesTheHealth(@TempDir Path dir) throws Exception {
        // FEED-1: a followed file gains a line it cannot decode while the query is running.
        Path incoming = dir.resolve("txn.csv");
        java.nio.file.Files.writeString(incoming, "u1,300\n");
        runner.withPropertyValues(
                        "management.endpoints.web.exposure.include=pravaha",
                        "pravaha.sources.txn.plugin=filesystem",
                        "pravaha.sources.txn.options.path=" + incoming,
                        "pravaha.sources.txn.options.schema=user_id:STRING,amount:INT64",
                        "pravaha.sources.txn.options.follow=true")
                .run(context -> {
                    PravahaEngine engine = context.getBean(PravahaEngine.class);
                    PravahaEndpoint endpoint = context.getBean(PravahaEndpoint.class);
                    PravahaHealthIndicator indicator = context.getBean(PravahaHealthIndicator.class);
                    awaitFeed(endpoint, "RUNNING");
                    assertThat(endpoint.query("big_txn").feed().sources())
                            .singleElement()
                            .satisfies(source -> {
                                assertThat(source.stream()).isEqualTo("txn");
                                assertThat(source.state()).isEqualTo("RUNNING");
                                assertThat(source.code()).isNull();
                            });
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

                    java.nio.file.Files.writeString(incoming, "u2,lots\n", java.nio.file.StandardOpenOption.APPEND);
                    awaitFeed(endpoint, "STOPPED");

                    PravahaEndpoint.QueryDescriptor query = endpoint.query("big_txn");
                    assertThat(query.state()).as("the state keeps its meaning").isEqualTo("RUNNING");
                    assertThat(query.feed().sources()).singleElement().satisfies(source -> {
                        assertThat(source.state()).isEqualTo("STOPPED");
                        assertThat(source.code()).isEqualTo("PRV-5040");
                        assertThat(source.stoppedAt()).isNotNull();
                    });
                    Health health = indicator.health();
                    assertThat(health.getStatus()).isEqualTo(PravahaHealthIndicator.DEGRADED);
                    assertThat(health.getDetails().get("stoppedFeeds"))
                            .asString()
                            .contains("big_txn: PRV-5040 reading txn#0");
                    assertThat(engine.state().name()).isEqualTo("RUNNING");
                });
    }

    private static void awaitFeed(PravahaEndpoint endpoint, String state) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (state.equals(endpoint.query("big_txn").feed().state())) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(endpoint.query("big_txn").feed().state()).isEqualTo(state);
    }

    @Test
    void theHealthIsDownOnceTheEngineIsNotRunning() {
        runner.run(context -> {
            PravahaHealthIndicator indicator = context.getBean(PravahaHealthIndicator.class);
            context.getBean(PravahaEngine.class).stop();
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("state", "STOPPED");
        });
    }

    @Test
    void aStoppedListenerIsNamedInTheHealth() {
        runner.withPropertyValues("pravaha.listener.on-error=stop")
                .withBean(Failing.class)
                .run(context -> {
                    new PravahaTester(
                                    context.getBean(PravahaEngine.class),
                                    context.getBean(PravahaListenerProcessor.class))
                            .push("txn", new Object[] {"u1", 300L})
                            .awaitListeners();
                    Health health =
                            context.getBean(PravahaHealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails().get("stoppedListeners"))
                            .asString()
                            .contains("on 'big_txn'");
                });
    }
}

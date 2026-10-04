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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.ReadLimits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter's auto-configuration: an engine from {@code pravaha.*}, started with the context and
 * closed with it, and out of the way when the application brings its own.
 */
class PravahaAutoConfigurationTest {

    static final String TXN = "user_id:STRING,amount:INT64";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class));

    record Big(String userId, long amount) {}

    @Test
    void anEngineIsBuiltFromPropertiesStartedWithTheContextAndClosedWithIt() {
        AtomicReference<PravahaEngine> seen = new AtomicReference<>();
        runner.withPropertyValues(
                        "pravaha.node.id=orders-service",
                        "pravaha.streams.txn.schema=" + TXN,
                        "pravaha.queries.big_txn.sql=SELECT user_id, amount FROM txn WHERE amount > 100",
                        "pravaha.queries.big_txn.keys=user_id")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PravahaEngine.class);
                    assertThat(context).hasSingleBean(PravahaTemplate.class);
                    PravahaEngine engine = context.getBean(PravahaEngine.class);
                    seen.set(engine);
                    assertThat(engine.state()).isEqualTo(EngineState.RUNNING);
                    assertThat(engine.instanceId()).isEqualTo("orders-service");
                    assertThat(engine.queries()).containsExactly("big_txn");

                    PravahaTemplate template = context.getBean(PravahaTemplate.class);
                    template.push("txn", new Object[] {"u1", 300L}, new Object[] {"u2", 50L});
                    template.push("txn", Map.of("user_id", "u3", "amount", 700L));
                    assertThat(template.query(Big.class, "SELECT * FROM big_txn"))
                            .containsExactlyInAnyOrder(new Big("u1", 300L), new Big("u3", 700L));
                    assertThat(template.queryForList("SELECT amount FROM big_txn WHERE user_id = ?", "u3"))
                            .containsExactly(Map.of("amount", 700L));
                    assertThat(template.query("SELECT * FROM big_txn").size()).isEqualTo(2);
                });
        assertThat(java.util.Objects.requireNonNull(seen.get()).state())
                .as("closing the context closes the engine")
                .isEqualTo(EngineState.STOPPED);
    }

    @Test
    void everyBlockBindsToTheKeyTheEngineReads(@TempDir Path dir) throws Exception {
        Path incoming = dir.resolve("in/txn.csv");
        Files.createDirectories(incoming.getParent());
        Files.writeString(incoming, "u1,300\nu2,20\n");
        Path out = dir.resolve("out/large.csv");
        runner.withPropertyValues(
                        "pravaha.streams.txn.schema=" + TXN,
                        "pravaha.streams.clicks.schema=page:STRING,ts:TIMESTAMP",
                        "pravaha.streams.clicks.event-time=ts",
                        "pravaha.streams.clicks.out-of-orderness=5s",
                        "pravaha.sources.txn.plugin=filesystem",
                        "pravaha.sources.txn.options.path=" + incoming,
                        "pravaha.sources.txn.options.schema=" + TXN,
                        "pravaha.sinks.large.plugin=filesystem",
                        "pravaha.sinks.large.options.path=" + out,
                        "pravaha.sinks.large.options.schema=" + TXN,
                        "pravaha.queries.big_txn.sql=SELECT user_id, amount FROM txn WHERE amount > 100",
                        "pravaha.queries.big_txn.keys[0]=user_id",
                        "pravaha.queries.big_txn.sink=large",
                        "pravaha.registry.journal=" + dir.resolve("journal/registry.log"),
                        "pravaha.checkpoint.directory=" + dir.resolve("checkpoints"),
                        "pravaha.checkpoint.interval=250ms",
                        "pravaha.checkpoint.keep=2",
                        "pravaha.watermark.idle-after=10s",
                        "pravaha.watermark.tick=500ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PravahaEngine engine = context.getBean(PravahaEngine.class);
                    assertThat(engine.configuration().getDuration("pravaha.checkpoint.interval"))
                            .contains(Duration.ofMillis(250));
                    assertThat(engine.configuration().getInt("pravaha.checkpoint.keep", 0))
                            .isEqualTo(2);
                    assertThat(engine.configuration().getDuration("pravaha.watermark.tick"))
                            .contains(Duration.ofMillis(500));
                    StreamSchema clicks = engine.streams().stream()
                            .filter(s -> s.name().equals("clicks"))
                            .findFirst()
                            .orElseThrow();
                    assertThat(clicks.eventTimeOrdinal()).hasValue(1);
                    assertThat(clicks.outOfOrderness()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(engine.registry().sinkOf("big_txn")).contains("large");

                    // The bound source feeds the query, and the bound sink receives its output.
                    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (!(Files.exists(out) && Files.readAllLines(out).contains("u1,300"))) {
                        assertThat(System.nanoTime())
                                .as("the source's row in the sink")
                                .isLessThan(deadline);
                        Thread.sleep(20);
                    }
                    assertThat(Files.exists(dir.resolve("journal/registry.log")))
                            .isTrue();
                });
    }

    /** STARTERREAD-1: {@code pravaha.serving.read.*} reaches the embedded engine's read admission. */
    @Test
    void readAdmissionSettingsReachTheEngine() {
        runner.withPropertyValues(
                        "pravaha.serving.read.max-concurrent=3",
                        "pravaha.serving.read.max-queued=5",
                        "pravaha.serving.read.queue-timeout=750ms",
                        "pravaha.serving.read.tenant-share=0.5",
                        "pravaha.serving.read.deadline=20s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReadLimits limits =
                            ReadLimits.from(context.getBean(PravahaEngine.class).configuration());
                    assertThat(limits)
                            .isEqualTo(new ReadLimits(3, 5, 0.5, Duration.ofMillis(750), Duration.ofSeconds(20)));
                    assertThat(limits.admission()).isNotSameAs(ReadAdmission.UNLIMITED);
                });

        runner.run(context -> assertThat(
                        ReadLimits.from(context.getBean(PravahaEngine.class).configuration()))
                .as("unset, the engine's own defaults")
                .isEqualTo(ReadLimits.NONE));

        runner.withPropertyValues("pravaha.serving.read.tenant-share=1.5").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("PRV-1026")
                    .hasMessageContaining("pravaha.serving.read.tenant-share");
        });
    }

    @Test
    void anApplicationsOwnEngineIsUsedAndACustomizerRunsBeforeStart() {
        PravahaEngine own = PravahaEngine.createDefault();
        runner.withBean(PravahaEngine.class, () -> own).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PravahaEngine.class)).isSameAs(own);
            assertThat(context.getBean(PravahaTemplate.class).engine()).isSameAs(own);
        });

        runner.withBean(
                        PravahaEngineCustomizer.class,
                        () -> engine -> engine.declareStream(StreamSchema.builder("readings")
                                .field("sensor", Types.string())
                                .field("reading", Types.float64())
                                .build()))
                .run(context -> {
                    PravahaEngine engine = context.getBean(PravahaEngine.class);
                    assertThat(engine.streams()).extracting(StreamSchema::name).containsExactly("readings");
                    assertThat(engine.push("readings", new Object[] {"s1", 1.5}))
                            .isZero();
                });
    }

    @Test
    void itCanBeSwitchedOffAndABadConfigurationFailsTheStartup() {
        runner.withPropertyValues("pravaha.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PravahaEngine.class);
        });

        runner.withPropertyValues("pravaha.streams.txn.event-time=ts").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("PRV-8104")
                    .hasMessageContaining("no schema");
        });

        runner.withPropertyValues(
                        "pravaha.streams.txn.schema=" + TXN,
                        "pravaha.queries.bad.sql=SELECT user_id FROM txn",
                        "pravaha.queries.bad.keys=nope")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("[user_id]");
                });
    }

    @Test
    void theTemplateIsTheEnginesCallsUnderTheirOwnNames() {
        runner.withPropertyValues("pravaha.streams.txn.schema=" + TXN).run(context -> {
            PravahaTemplate template = context.getBean(PravahaTemplate.class);
            template.register("big_txn", "SELECT user_id, amount FROM txn WHERE amount > 100", "user_id");
            List<Object> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            try (var _ = template.subscribe("big_txn", change -> seen.add(change.get("user_id")))) {
                template.push("txn", new Object[] {"u9", 900L});
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (seen.isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(seen).containsExactly("u9");
            }
            template.pause("big_txn");
            template.resume("big_txn");
            template.drop("big_txn");
            assertThat(template.engine().queries()).isEmpty();
            template.register(com.ash.messaging.pravaha.embedded.ContinuousQuery.named("again")
                    .sql("SELECT user_id FROM txn")
                    .keyedBy("user_id")
                    .build());
            assertThat(template.engine().queries()).containsExactly("again");
        });
    }
}
